from __future__ import annotations
import base64, hashlib, json, os, re, sqlite3, time
from contextlib import contextmanager
from ipaddress import ip_address
from pathlib import Path
from fastapi import FastAPI, Header, HTTPException, Response
from pydantic import BaseModel, ConfigDict, Field, field_validator
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat, PrivateFormat, NoEncryption

DB = Path(os.getenv("MOLIDO_DISCOVERY_DB", "community-discovery.sqlite3"))
PEPPER = os.getenv("MOLIDO_TOKEN_PEPPER", "")
KEY = os.getenv("MOLIDO_DIRECTORY_SIGNING_KEY", "")
MIN_REPORTERS = max(3, int(os.getenv("MOLIDO_MIN_REPORTERS", "3")))
HOST_RE = re.compile(r"^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$")
app = FastAPI(title="Molido Community Discovery", version="1.0.0", docs_url=None, redoc_url=None)

class Observation(BaseModel):
    model_config = ConfigDict(extra="forbid")
    schema_version: int = Field(default=1, ge=1, le=1)
    protocol: str = Field(pattern="^(ikev2|wireguard|shadowsocks|vmess|vless|trojan)$")
    endpoint_host: str = Field(min_length=3, max_length=253)
    port: int = Field(ge=1, le=65535)
    result: str = Field(pattern="^(connected|timeout|refused|tls_error|auth_rejected|unknown)$")
    rtt_ms: int | None = Field(default=None, ge=0, le=120000)
    loss_percent: float | None = Field(default=None, ge=0, le=100)
    observed_at: int = Field(ge=1)

    @field_validator("endpoint_host")
    @classmethod
    def host_is_public(cls, value):
        value = value.strip().rstrip(".").lower()
        try:
            ip = ip_address(value)
            if not ip.is_global:
                raise ValueError("non-public IP")
        except ValueError as exc:
            if "non-public" in str(exc) or not HOST_RE.fullmatch(value):
                raise ValueError("invalid public endpoint host")
        return value

@contextmanager
def database():
    con = sqlite3.connect(DB, timeout=10)
    con.row_factory = sqlite3.Row
    con.execute("""CREATE TABLE IF NOT EXISTS observations(
      endpoint_id TEXT, protocol TEXT, host TEXT, port INTEGER, result TEXT,
      rtt INTEGER, loss REAL, reporter TEXT, observed INTEGER, received INTEGER,
      UNIQUE(endpoint_id, reporter, observed))""")
    con.execute("CREATE INDEX IF NOT EXISTS obs_recent ON observations(received)")
    try:
        yield con
        con.commit()
    finally:
        con.close()

def signer():
    try:
        return Ed25519PrivateKey.from_private_bytes(base64.b64decode(KEY, validate=True))
    except Exception:
        return None

@app.get("/health")
def health():
    return {"status":"ok", "directory_signing": signer() is not None}

@app.post("/v1/observations", status_code=202)
def report(o: Observation, token: str = Header(default="", alias="X-Molido-Report-Token")):
    if len(PEPPER) < 32:
        raise HTTPException(503, "ingestion not configured")
    if not 24 <= len(token) <= 256:
        raise HTTPException(401, "anonymous rotating token required")
    now = int(time.time())
    if abs(now-o.observed_at) > 900:
        raise HTTPException(422, "stale observation")
    reporter = hashlib.sha256((PEPPER+token).encode()).hexdigest()
    eid = hashlib.sha256(f"{o.protocol}|{o.endpoint_host}|{o.port}".encode()).hexdigest()
    with database() as con:
        count = con.execute("SELECT COUNT(*) FROM observations WHERE reporter=? AND received>?", (reporter, now-3600)).fetchone()[0]
        if count >= 30:
            raise HTTPException(429, "quota exceeded")
        try:
            con.execute("INSERT INTO observations VALUES(?,?,?,?,?,?,?,?,?,?)",
              (eid,o.protocol,o.endpoint_host,o.port,o.result,o.rtt_ms,o.loss_percent,reporter,o.observed_at,now))
        except sqlite3.IntegrityError:
            raise HTTPException(409, "duplicate observation")
    return {"accepted":True,"endpoint_id":eid,"status":"pending_quorum"}

@app.get("/v1/directory")
def get_directory(response: Response):
    key = signer()
    if key is None:
        raise HTTPException(503, "directory signing key unavailable")
    now = int(time.time())
    with database() as con:
        rows = con.execute("""SELECT endpoint_id,protocol,host,port,COUNT(DISTINCT reporter) reporters,
          SUM(CASE WHEN result='connected' THEN 1 ELSE 0 END) ok, COUNT(*) total,
          AVG(rtt) avg_rtt, MAX(received) last_seen FROM observations
          WHERE received>? GROUP BY endpoint_id HAVING COUNT(DISTINCT reporter)>=?""",
          (now-86400,MIN_REPORTERS)).fetchall()
    entries=[]
    for r in rows:
        entries.append({"id":r["endpoint_id"],"protocol":r["protocol"],"host":r["host"],
          "port":r["port"],"confidence":"community_reported","success_rate":round(r["ok"]/max(1,r["total"]),3),
          "avg_rtt_ms":round(r["avg_rtt"]) if r["avg_rtt"] is not None else None,
          "last_seen":r["last_seen"],"expires_at":r["last_seen"]+86400})
    payload={"schema_version":1,"generated_at":now,"expires_at":now+900,"entries":entries}
    canonical=json.dumps(payload,sort_keys=True,separators=(",",":")).encode()
    pub=key.public_key().public_bytes(Encoding.Raw,PublicFormat.Raw)
    response.headers["Cache-Control"]="public, max-age=60, stale-while-revalidate=300"\n    response.headers["X-Content-Type-Options"]="nosniff"
    return {"payload":payload,"signature":base64.b64encode(key.sign(canonical)).decode(),
      "algorithm":"Ed25519","public_key":base64.b64encode(pub).decode()}
