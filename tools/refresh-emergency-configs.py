#!/usr/bin/env python3
"""Build-time refresh of the offline emergency set (app/src/main/assets/emergency-configs.txt).

Takes the first N servers of the worker's ranked subscription (the worker already orders them by
Iran success reports and hides dead ones), falling back to the GitHub/jsDelivr copies. Also copies
remote/resilience.json into the bundled defaults. The owner's own "VIP" servers are left out unless
EMERGENCY_INCLUDE_VIP=1 (an APK is easy to unpack, and a server baked into it can never be recalled).
By default, developer builds keep the last-known-good files if every source is down.
Release CI passes --strict: resilience defaults must be valid and at least five usable
emergency configs must be refreshed, otherwise the release is blocked.
Usage: python3 tools/refresh-emergency-configs.py [N] [--strict]
"""
import base64, datetime, json, os, sys, tempfile, urllib.parse, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
OUT = os.path.join(ASSETS, "emergency-configs.txt")
ARGS = [arg for arg in sys.argv[1:] if arg != "--strict"]
STRICT = "--strict" in sys.argv[1:]
N = int(ARGS[0]) if ARGS else 40
if N < 5:
    raise SystemExit("N must be at least 5")
SOURCES = [
    "https://molido-sub.hidooch980.workers.dev/sub/1",
    "https://raw.githubusercontent.com/hidooch980/vpn-sub/sub/sub.txt",
    "https://cdn.jsdelivr.net/gh/hidooch980/vpn-sub@sub/sub.txt",
]
SCHEMES = ("vless://", "vmess://", "trojan://", "ss://", "hysteria2://", "hy2://", "tuic://")


def lines(body: str):
    text = body.strip()
    if "://" not in text:
        try:
            text = base64.b64decode(text + "=" * (-len(text) % 4)).decode("utf-8", "replace")
        except Exception:
            return []
    return [l.strip() for l in text.splitlines() if l.strip().startswith(SCHEMES)]


def name_of(line: str) -> str:
    """Display name: the #fragment, or for vmess the "ps" field inside the base64 JSON payload."""
    if line.startswith("vmess://"):
        payload = line[len("vmess://"):].split("#", 1)[0]
        try:
            import json
            return str(json.loads(base64.b64decode(payload + "=" * (-len(payload) % 4))).get("ps", ""))
        except Exception:  # noqa: BLE001
            pass
    return urllib.parse.unquote(line.split("#", 1)[1]) if "#" in line else ""


def main():
    resilience_source = os.path.join(ROOT, "remote", "resilience.json")
    resilience_target = os.path.join(ASSETS, "resilience-defaults.json")
    resilience_ok = False
    try:
        with open(resilience_source, "r", encoding="utf-8") as source:
            resilience = json.load(source)
        if not isinstance(resilience, dict):
            raise ValueError("resilience defaults must be a JSON object")
        os.makedirs(ASSETS, exist_ok=True)
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", newline="\\n",
                                         dir=ASSETS, delete=False) as tmp:
            json.dump(resilience, tmp, ensure_ascii=False, separators=(",", ":"))
            tmp.write("\\n")
            temp_path = tmp.name
        os.replace(temp_path, resilience_target)
        resilience_ok = True
        print("validated and refreshed resilience defaults")
    except Exception as e:  # noqa: BLE001
        print("ERROR: resilience defaults were not refreshed:", e)
        if STRICT:
            raise SystemExit(2)

    for url in SOURCES:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "MolidoVPN-build"})
            with urllib.request.urlopen(req, timeout=30) as response:
                got = lines(response.read().decode("utf-8", "replace"))
        except Exception as e:  # noqa: BLE001
            print("source unavailable:", url, e)
            continue
        seen, picked = set(), []
        include_vip = os.environ.get("EMERGENCY_INCLUDE_VIP") == "1"
        for line in got:
            if not include_vip and "VIP" in name_of(line):
                continue
            key = line.split("#", 1)[0]
            if key not in seen:
                seen.add(key)
                picked.append(line)
            if len(picked) >= N:
                break
        if len(picked) < 5:
            print("ERROR: fewer than five usable configs from", url, len(picked))
            continue
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
        os.makedirs(ASSETS, exist_ok=True)
        temp_path = None
        try:
            with tempfile.NamedTemporaryFile("w", encoding="utf-8", newline="\\n",
                                             dir=ASSETS, delete=False) as tmp:
                temp_path = tmp.name
                tmp.write(f"# MolidoVPN offline emergency set — {len(picked)} servers from {url} at {stamp}\\n")
                tmp.write("# Generated at build time; used only when no live list can be fetched.\\n")
                tmp.write("\\n".join(picked) + "\\n")
            os.replace(temp_path, OUT)
        except Exception:
            if temp_path and os.path.exists(temp_path):
                os.unlink(temp_path)
            raise
        print(f"wrote {len(picked)} validated configs from {url}")
        if STRICT and not resilience_ok:
            raise SystemExit("ERROR: release blocked because resilience defaults are invalid")
        return

    if STRICT:
        raise SystemExit("ERROR: release blocked; no source supplied at least five usable emergency configs")
    print("WARNING: no source reachable; keeping the last-known-good emergency set")

if __name__ == "__main__":
    main()
