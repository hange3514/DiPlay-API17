"""Build the pinned fixed-point libopus uplink encoder for Android API 17."""

import argparse
import hashlib
import os
import re
import subprocess
import tarfile
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
TOOLS = Path(os.environ.get("DIPLAY_TOOLCHAIN", ROOT.parents[1] / "toolchain")).expanduser().resolve()
OPUS_NAME = "opus-1.5.2"
OPUS_SHA256 = "65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1"
LIBRARY = ROOT / "build/libdiplay_mic_opus.so"
JNI_EXPORTS = tuple(
    "Java_local_headunit_diplay_NativeUplinkOpus_" + method
    for method in ("nativeCreate", "nativeEncode", "nativeSetComplexity", "nativeDestroy")
)


def source_list(source, filename, variables):
    text = (source / filename).read_text(encoding="utf-8").replace("\\\n", " ")
    entries = []
    for variable in variables:
        match = re.search(r"^" + variable + r"\s*=([^\n]+)", text, re.MULTILINE)
        if not match:
            raise ValueError("Missing libopus source list: " + variable)
        entries.extend(source / entry for entry in match[1].split())
    return entries


def build(archive):
    if hashlib.sha256(archive.read_bytes()).hexdigest() != OPUS_SHA256:
        raise ValueError("libopus archive SHA-256 does not match the Xiph release")
    LIBRARY.parent.mkdir(exist_ok=True)
    zig = TOOLS / "zig/ziglang/zig.exe"
    jdk = next((TOOLS / "jdk21").glob("*/include/jni.h"))

    with tempfile.TemporaryDirectory(prefix="diplay-native-opus-") as scratch:
        scratch = Path(scratch)
        with tarfile.open(archive) as tar:
            tar.extractall(scratch, filter="data")
        source = scratch / OPUS_NAME
        jni = jdk.read_text(encoding="utf-8").replace("#include <stdio.h>", "")
        (scratch / "jni.h").write_text(jni, encoding="utf-8")
        exports = scratch / "exports.map"
        exports.write_text("{ global: " + "; ".join(JNI_EXPORTS) + "; local: *; };\n", encoding="ascii")
        sources = [
            *source_list(source, "opus_sources.mk", ["OPUS_SOURCES"]),
            *source_list(source, "celt_sources.mk", ["CELT_SOURCES"]),
            *source_list(source, "silk_sources.mk", ["SILK_SOURCES", "SILK_SOURCES_FIXED"]),
        ]
        includes = [ROOT / "native", scratch, source, *(source / part for part in
                    ("include", "celt", "silk", "silk/fixed"))]
        libc_headers = zig.parent / "lib/libc/include"
        command = [
            zig, "cc", "-target", "arm-linux-android", "-D__ANDROID_API__=17",
            "-U__ANDROID__", "-mcpu=cortex_a9", "-mfloat-abi=softfp", "-O2",
            "-fwrapv", "-fno-strict-aliasing", "-fno-stack-protector",
            "-fno-vectorize", "-fno-slp-vectorize", "-fPIC", "-fvisibility=hidden",
            "-ffunction-sections", "-fdata-sections", "-fno-unwind-tables",
            "-fno-asynchronous-unwind-tables", "-nostdlib", "-shared",
            "-DOPUS_BUILD", "-DFIXED_POINT=1", "-DDISABLE_FLOAT_API", "-DVAR_ARRAYS",
            '-DPACKAGE_VERSION="1.5.2"', "-Wl,--gc-sections", "-Wl,--no-undefined",
            "-Wl,--hash-style=sysv", "-Wl,-soname,libdiplay_mic_opus.so",
            "-Wl,-s", "-Wl,--version-script," + str(exports), "-Wno-cpp",
            *(flag for include in includes for flag in ("-I", include)),
            *(flag for system in ("generic-musl", "arm-linux-musl", "any-linux-any")
              for flag in ("-idirafter", libc_headers / system)),
            ROOT / "native/mic_opus_jni.c", *sources, TOOLS / "libc-api17-arm.so",
        ]
        output = scratch / LIBRARY.name
        subprocess.run([*map(str, command), "-o", str(output)], check=True)
        data = output.read_bytes()
        header = b"\x7fELF\x01\x01"
        if not data.startswith(header) or data[18:20] != b"\x28\x00":
            raise ValueError("Unexpected native Opus ELF class or machine")
        for symbol in JNI_EXPORTS:
            if symbol.encode("ascii") + b"\x00" not in data:
                raise ValueError("Missing JNI export " + symbol)
        LIBRARY.write_bytes(data)
        print(f"{LIBRARY.name}: {len(data)} bytes, ARM ELF32, 4 JNI methods")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-archive", type=Path, default=TOOLS / (OPUS_NAME + ".tar.gz"))
    options = parser.parse_args()
    if not options.source_archive.is_file():
        parser.error("Fetch https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz "
                     "and pass it as --source-archive")
    build(options.source_archive)


if __name__ == "__main__":
    main()
