#!/usr/bin/env python3
"""Check runtime assets before installing an NWIA Android debug preview."""
import argparse
import hashlib
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("apk", type=Path)
args = parser.parse_args()
with zipfile.ZipFile(args.apk) as archive:
    for name in (
        "assets/flutter_assets/kernel_blob.bin",
        "assets/flutter_assets/isolate_snapshot_data",
        "lib/arm64-v8a/libnwia_crypto.so",
    ):
        if archive.getinfo(name).file_size == 0:
            raise SystemExit(f"Empty required runtime asset: {name}")
    archive.getinfo("assets/flutter_assets/vm_snapshot_data")
    bad = archive.testzip()
    if bad:
        raise SystemExit(f"Invalid APK archive entry: {bad}")
with args.apk.open("rb") as stream:
    checksum = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        checksum.update(chunk)
    digest = checksum.hexdigest()
print("PASS NWIA arm64 debug APK runtime assets and archive integrity")
print(f"SHA256 {digest}")
