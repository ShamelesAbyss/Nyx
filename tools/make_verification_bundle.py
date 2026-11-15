#!/usr/bin/env python3
"""Create a bounded, deterministic release-verification evidence ZIP.

The bundle contains the independent artifact checks and, when available, the
build/test logs from the same clean build. It never contains signing material,
credentials, model weights, or APKs.
"""
from __future__ import annotations

import argparse
import os
import stat
import zipfile
from pathlib import Path


def _files(root: Path, prefix: str) -> list[tuple[Path, str]]:
    if not root.is_dir():
        return []
    found: list[tuple[Path, str]] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.is_symlink():
            continue
        relative = path.relative_to(root).as_posix()
        found.append((path, f"{prefix}/{relative}"))
    return found


def _write(archive: zipfile.ZipFile, source: Path, name: str) -> None:
    data = source.read_bytes()
    info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
    info.external_attr = (stat.S_IFREG | 0o644) << 16
    archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("destination", type=Path)
    parser.add_argument("--artifact-checks", type=Path, required=True)
    parser.add_argument("--build-output", type=Path)
    parser.add_argument("--test-results", type=Path)
    args = parser.parse_args()

    if args.destination.exists() or args.destination.is_symlink():
        raise SystemExit(f"refusing to overwrite {args.destination}")

    entries: list[tuple[Path, str]] = []
    entries.extend(_files(args.artifact_checks, "artifact-checks"))
    if args.build_output:
        log = args.build_output / "build-and-dependencies.log"
        if log.is_file() and not log.is_symlink():
            entries.append((log, "build-and-dependencies.log"))
    if args.test_results:
        entries.extend(_files(args.test_results, "test-results"))

    if not entries:
        raise SystemExit("no verification evidence found")
    with zipfile.ZipFile(args.destination, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for source, name in entries:
            _write(archive, source, name)
    print(f"Verification bundle PASS: {len(entries)} evidence files -> {args.destination}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
