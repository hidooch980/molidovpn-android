# Molido Community Discovery API

Runnable first-stage report ingestion and signed-directory service.

## Run
Python 3.10+:
```sh
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
export MOLIDO_TOKEN_PEPPER="$(python -c 'import secrets; print(secrets.token_urlsafe(48))')"
export MOLIDO_DIRECTORY_SIGNING_KEY="$(python -c 'import base64,secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())')"
uvicorn app:app --host 127.0.0.1 --port 8787
```
Keep both secrets in a secret manager. Never commit them. Place behind HTTPS.

## Endpoints
- GET /health
- POST /v1/observations with X-Molido-Report-Token: rotating random anonymous token.
- GET /v1/directory: Ed25519-signed snapshot; only endpoints with at least three distinct reporters in 24 hours are published.

The strict schema excludes credentials, private keys, full profiles, URLs, client IP fields, DNS queries, traffic content and arbitrary free text. Reporter tokens are hashed with a server-side pepper before storage; max 30 reports per token/hour. Stale and duplicate reports are rejected. This API does not actively probe endpoints and self-reported success is not proof of a complete VPN handshake.

SQLite is a single-instance starter store, not a horizontally scalable production database. Before public rollout add TLS termination, request size limits, managed database/backups, monitoring, retention controls, abuse response, signing-key rotation, and client-side signature verification. Do not publish or trust unsigned snapshots.
