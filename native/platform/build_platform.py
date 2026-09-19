#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Build the isolated arm64 Platform message-type compatibility library."""
import argparse
import hashlib
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

from native.vrapi.build import ndk_root


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path)
    parser.add_argument("--output", type=Path, required=True, help="resource root, not a .so filename")
    args = parser.parse_args()

    source = Path(__file__).resolve().parent
    notices = [
        source / "licenses/OCULUS-PLATFORM-SDK.txt",
        REPOSITORY_ROOT / "native/vrapi/licenses/ANDROID-NDK.txt",
    ]
    for notice in notices:
        if not notice.is_file():
            raise SystemExit(f"Native dependency notice is missing: {notice}")

    host = {
        "Windows": "windows-x86_64",
        "Linux": "linux-x86_64",
        "Darwin": "darwin-x86_64",
    }.get(platform.system())
    if not host:
        raise SystemExit(f"Unsupported NDK host: {platform.system()}")

    toolchain = ndk_root(args.ndk) / "toolchains/llvm/prebuilt" / host
    compiler = toolchain / "bin" / ("clang++.exe" if os.name == "nt" else "clang++")
    if not compiler.is_file():
        raise SystemExit(f"NDK compiler is missing: {compiler}")

    build = source / "build"
    build.mkdir(parents=True, exist_ok=True)
    library = build / "libovrplatformcompat.so"
    flags = [
        "--target=aarch64-linux-android29",
        f"--sysroot={toolchain / 'sysroot'}",
        "-std=c++17",
        "-O2",
        "-fPIC",
        "-fvisibility=hidden",
        "-fno-exceptions",
        "-fno-rtti",
        "-ffunction-sections",
        "-fdata-sections",
        "-Wall",
        "-Wextra",
        "-Werror=return-type",
    ]
    subprocess.run(
        [
            str(compiler),
            *flags,
            "-shared",
            "-nostdlib++",
            str(source / "message_type.cpp"),
            "-Wl,--no-undefined",
            "-Wl,--gc-sections",
            "-Wl,-z,max-page-size=16384",
            "-Wl,-soname,libovrplatformcompat.so",
            f"-Wl,--version-script={source / 'exports.map'}",
            "-o",
            str(library),
        ],
        check=True,
    )

    resource_root = args.output.resolve()
    destination = resource_root / "platform/arm64-v8a/libovrplatformcompat.bin"
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(library, destination)

    notice_directory = resource_root / "platform/licenses"
    notice_directory.mkdir(parents=True, exist_ok=True)
    for notice in notices:
        shutil.copyfile(notice, notice_directory / notice.name)

    print(f"Platform compatibility payload: {destination}")
    print(f"Dependency notices: {notice_directory}")
    print(f"SHA-256: {hashlib.sha256(destination.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
