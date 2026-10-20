#!/usr/bin/env python3
"""Cross-build a pinned OpenVPN JNI inspection library. Does not package or publish an APK."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent
PINS = json.loads((ROOT / "deps.lock.json").read_text())
WORK = ROOT / ".work"

def run(args, cwd=None, env=None):
    subprocess.run([str(arg) for arg in args], cwd=cwd, env=env, check=True)

def checkout(name):
    pin = PINS[name]
    if not re.fullmatch(r"[0-9a-f]{40}", pin["sha"]):
        raise ValueError("A full dependency commit is required")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", pin["repo"]):
        raise ValueError("Invalid dependency repository")
    path = WORK / "sources" / name
    if not (path / ".git").exists():
        path.mkdir(parents=True, exist_ok=True)
        run(["git", "init", "-q", path])
        run(["git", "-C", path, "remote", "add", "origin",
             "https://github.com/" + pin["repo"] + ".git"])
    run(["git", "-C", path, "fetch", "--depth=1", "origin", pin["sha"]])
    run(["git", "-C", path, "checkout", "--detach", "FETCH_HEAD"])
    actual = subprocess.check_output(["git", "-C", str(path), "rev-parse", "HEAD"], text=True).strip()
    if actual != pin["sha"]:
        raise RuntimeError("Dependency revision mismatch")
    return path

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--abi", choices=["arm64-v8a", "armeabi-v7a"], required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    args = parser.parse_args()
    ndk = args.ndk.resolve()
    tools = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    if not (tools / "clang").is_file():
        raise RuntimeError("Linux Android NDK is required")
    for name in ["openvpn", "openssl", "asio", "lz4", "fmt"]:
        checkout(name)
    build_root = WORK / args.abi
    prefix = build_root / "prefix"
    prefix.mkdir(parents=True, exist_ok=True)
    ssl = WORK / "sources/openssl"
    env = os.environ.copy()
    env["ANDROID_NDK_ROOT"] = str(ndk)
    env["PATH"] = str(tools) + os.pathsep + env["PATH"]
    target = "android-arm64" if args.abi == "arm64-v8a" else "android-arm"
    run(["perl", "Configure", target, "no-shared", "no-tests", "no-module",
         "--prefix=" + str(prefix), "--libdir=lib", "-D__ANDROID_API__=26", "-fPIC"], cwd=ssl, env=env)
    run(["make", "-j2", "build_libs"], cwd=ssl, env=env)
    run(["make", "install_dev"], cwd=ssl, env=env)
    build = build_root / "cmake"
    run(["cmake", "-S", ROOT, "-B", build, "-G", "Ninja",
         "-DCMAKE_TOOLCHAIN_FILE=" + str(ndk / "build/cmake/android.toolchain.cmake"),
         "-DANDROID_ABI=" + args.abi, "-DANDROID_PLATFORM=android-26",
         "-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release",
         "-DMOLIDO_SOURCES=" + str(WORK / "sources"),
         "-DMOLIDO_PREFIX=" + str(prefix),
         "-DMOLIDO_OPENVPN_COMMIT=" + PINS["openvpn"]["sha"]])
    run(["cmake", "--build", build, "--parallel", "2"])
    library = build / "libmolido_openvpn.so"
    dynamic = subprocess.check_output([str(tools / "llvm-readelf"), "-d", str(library)], text=True)
    allowed = {"libc.so", "libm.so", "libdl.so", "liblog.so"}
    needed = set(re.findall(r"\(NEEDED\).*?\[(.*?)\]", dynamic))
    if not needed <= allowed:
        raise RuntimeError("Unpackaged dynamic dependency: " + repr(needed - allowed))
    symbols = subprocess.check_output([str(tools / "llvm-nm"), "-D", "--defined-only", str(library)], text=True)
    for method in ["nativeEvaluate", "nativeRevision"]:
        if "Java_com_molido_vpn_OpenVpnCore_" + method not in symbols:
            raise RuntimeError("Missing JNI entry point")
    headers = subprocess.check_output([str(tools / "llvm-readelf"), "-lW", str(library)], text=True)
    loads = [line.split()[-1] for line in headers.splitlines() if line.strip().startswith("LOAD")]
    if not loads or any(int(value, 16) < 16384 for value in loads):
        raise RuntimeError("Library requires 16 KB compatible load alignment")
    out = ROOT / "dist" / args.abi
    out.mkdir(parents=True, exist_ok=True)
    shutil.copy2(library, out)
    shutil.copy2(ROOT / "deps.lock.json", out)
    (out / "elf.txt").write_text(headers + "\n" + dynamic + "\n" + symbols)
    licenses = out / "licenses"
    licenses.mkdir(exist_ok=True)
    patterns = {"openvpn": ["LICENSE*"], "openssl": ["LICENSE*"],
                "asio": ["asio/LICENSE*"], "lz4": ["lib/LICENSE*"], "fmt": ["LICENSE*"]}
    for name, globs in patterns.items():
        for pattern in globs:
            for license_file in (WORK / "sources" / name).glob(pattern):
                if license_file.is_file():
                    shutil.copy2(license_file, licenses / (name + "-" + license_file.name))
    print("BUILT AND ELF-CHECKED:", args.abi, library.name)
    print("No tunnel connection or device runtime test performed.")

if __name__ == "__main__":
    main()
