#!/usr/bin/env python3
"""Stage an inspection-only APK fixture; no test private key is packaged."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--abi", choices=["x86_64", "arm64-v8a", "armeabi-v7a"], required=True)
    args = parser.parse_args()
    assets = ROOT / "probe/assets"
    libs = ROOT / "probe/jniLibs" / args.abi
    assets.mkdir(parents=True, exist_ok=True)
    libs.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT / "dist" / args.abi / "libmolido_openvpn.so", libs)
    # Test-only, short-lived CA. Key remains in a temporary directory and is deleted.
    with tempfile.TemporaryDirectory() as temporary:
        key = Path(temporary) / "ca.key"
        cert = Path(temporary) / "ca.crt"
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                        "-days", "2", "-subj", "/CN=Molido native inspection fixture",
                        "-keyout", str(key), "-out", str(cert)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        profile = ("client\ndev tun\nproto udp\nremote 192.0.2.1 1194\n"
                   "remote-cert-tls server\nauth-user-pass\n<ca>\n"
                   + cert.read_text() + "</ca>\n")
        (assets / "auth.ovpn").write_text(profile)
    pins = json.loads((ROOT / "deps.lock.json").read_text())
    (assets / "revision.txt").write_text(pins["openvpn"]["sha"] + "\n")
    print("Probe staged. Local profile parsing only; no VPN connection.")

if __name__ == "__main__":
    main()
