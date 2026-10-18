#!/usr/bin/env python3
"""Build-time refresh of the offline emergency set (app/src/main/assets/emergency-configs.txt).

Takes the first N servers of the worker's ranked subscription (the worker already orders them by
Iran success reports and hides dead ones), falling back to the GitHub/jsDelivr copies. Also copies
remote/resilience.json into the bundled defaults. The owner's own "VIP" servers are left out unless
EMERGENCY_INCLUDE_VIP=1 (an APK is easy to unpack, and a server baked into it can never be recalled).
Never fails the build: on any error the files that
are already there are kept.   Usage: python3 tools/refresh-emergency-configs.py [N]
"""
import base64, datetime, os, shutil, sys, urllib.parse, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
OUT = os.path.join(ASSETS, "emergency-configs.txt")
N = int(sys.argv[1]) if len(sys.argv) > 1 else 40
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
    try:
        shutil.copyfile(os.path.join(ROOT, "remote", "resilience.json"), os.path.join(ASSETS, "resilience-defaults.json"))
    except Exception as e:  # noqa: BLE001
        print("resilience.json not copied:", e)
    for url in SOURCES:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "MolidoVPN-build"})
            got = lines(urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "replace"))
        except Exception as e:  # noqa: BLE001
            print("skip", url, e)
            continue
        seen, picked = set(), []
        include_vip = os.environ.get("EMERGENCY_INCLUDE_VIP") == "1"
        for l in got:
            if not include_vip and "VIP" in name_of(l):
                continue
            key = l.split("#", 1)[0]
            if key not in seen:
                seen.add(key)
                picked.append(l)
            if len(picked) >= N:
                break
        if len(picked) < 5:
            print("too few from", url, len(picked))
            continue
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
        with open(OUT, "w", encoding="utf-8", newline="\n") as f:
            f.write(f"# MolidoVPN offline emergency set — {len(picked)} servers from {url} at {stamp}\n")
            f.write("# Generated at build time by tools/refresh-emergency-configs.py; used only when no list can be fetched.\n")
            f.write("\n".join(picked) + "\n")
        print(f"wrote {len(picked)} configs from {url}")
        return
    print("no source reachable; keeping the existing emergency set")


if __name__ == "__main__":
    main()
