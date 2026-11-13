#!/usr/bin/env python3
"""Inspect release APK entries and ELF LOAD alignment; does not prove runtime compatibility."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile


def elf_loads(data):
    if data[:4] != b"\x7fELF" or data[4] not in (1, 2) or data[5] not in (1, 2):
        raise ValueError("Invalid ELF")
    order = "<" if data[5] == 1 else ">"
    if data[4] == 2:
        offset = struct.unpack_from(order + "Q", data, 32)[0]
        entry_size, count = struct.unpack_from(order + "HH", data, 54)
        layout = order + "IIQQQQQQ"
    else:
        offset = struct.unpack_from(order + "I", data, 28)[0]
        entry_size, count = struct.unpack_from(order + "HH", data, 42)
        layout = order + "IIIIIIII"
    if entry_size < struct.calcsize(layout) or offset + count * entry_size > len(data):
        raise ValueError("Invalid ELF program headers")
    loads = []
    for index in range(count):
        fields = struct.unpack_from(layout, data, offset + index * entry_size)
        if fields[0] != 1:
            continue
        file_offset, address = (fields[2], fields[3]) if data[4] == 2 else (fields[1], fields[2])
        alignment = fields[-1]
        loads.append({"offset": file_offset, "virtualAddress": address, "alignment": alignment,
                      "aligned16KiB": alignment >= 16384 and alignment & (alignment - 1) == 0
                      and (address - file_offset) % 16384 == 0})
    if not loads:
        raise ValueError("No ELF LOAD segments")
    return loads


def audit(apk):
    apk = Path(apk)
    with apk.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    result = {"apk": str(apk.resolve()), "sha256": digest,
              "bytes": apk.stat().st_size, "nativeLibraries": [],
              "limitation": "ELF alignment is a static prerequisite only; verify ZIP alignment, merged manifest, signing, and real 16 KB runtime separately."}
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        result["dexFiles"] = [name for name in names if name.endswith(".dex")]
        result["flutterAssetCount"] = sum(name.startswith("assets/flutter_assets/") for name in names)
        result["hasKernelBlob"] = "assets/flutter_assets/kernel_blob.bin" in names
        app_abis = {name.split("/")[1] for name in names if name.startswith("lib/") and name.endswith("/libapp.so")}
        engine_abis = {name.split("/")[1] for name in names if name.startswith("lib/") and name.endswith("/libflutter.so")}
        result["completeFlutterAbis"] = sorted(app_abis & engine_abis)
        for name in names:
            if name.startswith("lib/") and name.endswith(".so"):
                data = archive.read(name)
                loads = elf_loads(data)
                result["nativeLibraries"].append({"path": name, "sha256": hashlib.sha256(data).hexdigest(),
                    "bytes": len(data), "loadSegments": loads, "allLoadsAligned16KiB": all(s["aligned16KiB"] for s in loads)})
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(json.dumps(audit(args.apk), indent=2) + "\n")
