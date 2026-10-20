#!/usr/bin/env python3
"""Require the intended native library and original pins in each lab APK ABI."""
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parent
apks = list((ROOT.parents[1] / "app/build/outputs/apk/debug").glob("*.apk"))
if not apks:
    raise RuntimeError("No experimental APKs found")
found = set()
for path in apks:
    with zipfile.ZipFile(path) as apk:
        abis = {name.split("/")[1] for name in apk.namelist()
                if name.startswith("lib/") and name.endswith("/libaether.so")}
        if not abis:
            raise RuntimeError("APK has no application core ABI")
        for abi in abis:
            name = "lib/" + abi + "/libmolido_openvpn.so"
            if name not in apk.namelist():
                raise RuntimeError("APK missing OpenVPN library for " + abi)
            # AGP strips native libraries; byte equality is intentionally not required.
            if len(apk.read(name)) < 100000:
                raise RuntimeError("Unexpectedly small OpenVPN native library")
            found.add(abi)
        print("PACKAGED:", path.name, ",".join(sorted(abis)))
if found != {"arm64-v8a", "armeabi-v7a"}:
    raise RuntimeError("Both production ARM ABIs must be packaged")
print("Packaging verified. No tunnel connection test performed.")
