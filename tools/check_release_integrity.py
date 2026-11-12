#!/usr/bin/env python3
"""Check that a NYX delivery's version labels agree across its release inputs.

The checker uses Python's standard library only. APK metadata is read directly
from the binary AndroidManifest.xml inside the APK; no Android SDK tool is
required. Before assembling an APK, use --no-apk to validate Gradle, delivery
filenames, and the current release docs/report.

Example (pre-build):
  python3 tools/check_release_integrity.py --no-apk \
    --delivery-file NYX-v0.3.0.apk --delivery-file NYX-v0.3.0-source.zip \
    --release-doc CHANGELOG.md --release-doc NYX_DEVELOPMENT_REPORT.md \
    --release-doc TEST_RESULTS.md

Example (after build):
  python3 tools/check_release_integrity.py --apk app/build/outputs/apk/release/app-release.apk \
    --delivery-file NYX-v0.3.0.apk --delivery-file NYX-v0.3.0-source.zip \
    --release-doc CHANGELOG.md --release-doc NYX_DEVELOPMENT_REPORT.md
"""

from __future__ import annotations

import argparse
import re
import struct
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

DEFAULT_GRADLE_FILE = Path(__file__).resolve().parents[1] / "app" / "build.gradle.kts"
VERSION_RE = re.compile(r"(?<![A-Za-z0-9])v?(\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?)(?![A-Za-z0-9])")
CORE_VERSION_RE = re.compile(r"(?<![A-Za-z0-9])v?(\d+\.\d+\.\d+)(?![A-Za-z0-9])")
HEADING_RE = re.compile(r"^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$")
CODE_RE = re.compile(r"\bversionCode\s*(?:=|:)?\s*(\d+)\b", re.IGNORECASE)


class CheckError(Exception):
    """An actionable release-integrity failure."""


@dataclass(frozen=True)
class GradleVersion:
    version_name: str
    version_code: int
    application_id: str | None


@dataclass(frozen=True)
class ApkMetadata:
    package_name: str
    version_name: str
    long_version_code: int


def _single_match(pattern: re.Pattern[str], text: str, label: str, required: bool = True) -> str | None:
    matches = pattern.findall(text)
    if not matches:
        if required:
            raise CheckError(f"could not find {label}")
        return None
    if len(matches) != 1:
        raise CheckError(f"expected exactly one {label}; found {len(matches)}")
    return matches[0]


def read_gradle_version(path: Path) -> GradleVersion:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        raise CheckError(f"cannot read Gradle config {path}: {error}") from error
    # These settings are expected as literal assignments in defaultConfig.
    code = _single_match(
        re.compile(r"(?m)^\s*versionCode\s*=\s*(\d+)\s*(?://.*)?$"), text, "literal Gradle versionCode"
    )
    name = _single_match(
        re.compile(r'(?m)^\s*versionName\s*=\s*[\"\']([^\"\']+)[\"\']\s*(?://.*)?$'),
        text,
        "literal Gradle versionName",
    )
    app_id = _single_match(
        re.compile(r'(?m)^\s*applicationId\s*=\s*[\"\']([^\"\']+)[\"\']\s*(?://.*)?$'),
        text,
        "literal Gradle applicationId",
        required=False,
    )
    return GradleVersion(name, int(code), app_id)


def _u16(data: bytes, offset: int) -> int:
    return struct.unpack_from("<H", data, offset)[0]


def _u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def _length8(data: bytes, offset: int) -> tuple[int, int]:
    first = data[offset]
    if first & 0x80:
        return (((first & 0x7F) << 8) | data[offset + 1], offset + 2)
    return first, offset + 1


def _length16(data: bytes, offset: int) -> tuple[int, int]:
    first = _u16(data, offset)
    if first & 0x8000:
        return (((first & 0x7FFF) << 16) | _u16(data, offset + 2), offset + 4)
    return first, offset + 2


def _string_pool(data: bytes, chunk: int, header_size: int, chunk_size: int) -> list[str]:
    if header_size < 28 or chunk_size < header_size:
        raise CheckError("APK manifest has a malformed string pool")
    string_count = _u32(data, chunk + 8)
    flags = _u32(data, chunk + 16)
    strings_start = _u32(data, chunk + 20)
    offsets_start = chunk + header_size
    if string_count > 1_000_000 or offsets_start + string_count * 4 > chunk + chunk_size:
        raise CheckError("APK manifest string-pool offsets are invalid")
    utf8 = bool(flags & 0x100)
    strings: list[str] = []
    for index in range(string_count):
        offset = _u32(data, offsets_start + index * 4)
        cursor = chunk + strings_start + offset
        if cursor >= chunk + chunk_size:
            raise CheckError("APK manifest string offset is out of bounds")
        if utf8:
            _, cursor = _length8(data, cursor)  # UTF-16 character count
            byte_length, cursor = _length8(data, cursor)
            end = cursor + byte_length
            if end > chunk + chunk_size:
                raise CheckError("APK manifest UTF-8 string is truncated")
            strings.append(data[cursor:end].decode("utf-8", errors="replace"))
        else:
            char_length, cursor = _length16(data, cursor)
            end = cursor + char_length * 2
            if end > chunk + chunk_size:
                raise CheckError("APK manifest UTF-16 string is truncated")
            strings.append(data[cursor:end].decode("utf-16le", errors="replace"))
    return strings


def _pool_string(strings: list[str], index: int) -> str | None:
    return strings[index] if 0 <= index < len(strings) else None


def parse_binary_manifest(data: bytes) -> ApkMetadata:
    """Extract package, versionName, and longVersionCode from Android binary XML."""
    if len(data) < 8 or _u16(data, 0) != 0x0003:
        raise CheckError("APK AndroidManifest.xml is not Android binary XML")
    declared_size = _u32(data, 4)
    if declared_size > len(data) or declared_size < 8:
        raise CheckError("APK AndroidManifest.xml has an invalid declared size")

    strings: list[str] = []
    package_name: str | None = None
    version_name: str | None = None
    version_code: int | None = None
    version_code_major = 0
    cursor = 8
    while cursor + 8 <= declared_size:
        chunk_type = _u16(data, cursor)
        header_size = _u16(data, cursor + 2)
        chunk_size = _u32(data, cursor + 4)
        if chunk_size < 8 or cursor + chunk_size > declared_size:
            raise CheckError("APK AndroidManifest.xml contains an invalid chunk")
        if chunk_type == 0x0001:
            strings = _string_pool(data, cursor, header_size, chunk_size)
        elif chunk_type == 0x0102:  # RES_XML_START_ELEMENT_TYPE
            if header_size < 16 or chunk_size < 36:
                raise CheckError("APK manifest start-element chunk is truncated")
            element_name = _pool_string(strings, _u32(data, cursor + 20))
            attribute_start = _u16(data, cursor + 24)
            attribute_size = _u16(data, cursor + 26)
            attribute_count = _u16(data, cursor + 28)
            attributes = cursor + 16 + attribute_start
            if attribute_size < 20 or attributes + attribute_count * attribute_size > cursor + chunk_size:
                raise CheckError("APK manifest attributes are malformed")
            values: dict[str, str | int] = {}
            for index in range(attribute_count):
                attr = attributes + index * attribute_size
                attr_name = _pool_string(strings, _u32(data, attr + 4))
                raw_index = _u32(data, attr + 8)
                value_type = data[attr + 15]
                typed_data = _u32(data, attr + 16)
                raw_value = _pool_string(strings, raw_index)
                if value_type == 0x03:
                    value: str | int | None = _pool_string(strings, typed_data)
                elif value_type in (0x10, 0x11):
                    value = typed_data
                else:
                    value = raw_value
                if attr_name and value is not None:
                    values[attr_name] = value
            if element_name == "manifest":
                raw_package = values.get("package")
                if isinstance(raw_package, str):
                    package_name = raw_package
                raw_name = values.get("versionName")
                if isinstance(raw_name, str):
                    version_name = raw_name
                raw_code = values.get("versionCode")
                if isinstance(raw_code, int):
                    version_code = raw_code
                raw_major = values.get("versionCodeMajor")
                if isinstance(raw_major, int):
                    version_code_major = raw_major
        cursor += chunk_size

    if package_name is None or version_name is None or version_code is None:
        raise CheckError("APK manifest is missing package, versionName, or versionCode metadata")
    long_code = (version_code_major << 32) | (version_code & 0xFFFFFFFF)
    return ApkMetadata(package_name, version_name, long_code)


def read_apk_metadata(path: Path) -> ApkMetadata:
    try:
        with zipfile.ZipFile(path) as archive:
            manifest = archive.read("AndroidManifest.xml")
    except (OSError, zipfile.BadZipFile, KeyError) as error:
        raise CheckError(f"cannot read APK manifest from {path}: {error}") from error
    return parse_binary_manifest(manifest)


def check_delivery_name(name: str, expected_version: str) -> None:
    filename = Path(name).name
    exact_version = re.compile(
        rf"(?<![A-Za-z0-9])v?{re.escape(expected_version)}(?![A-Za-z0-9])"
    )
    if exact_version.search(filename) is None:
        raise CheckError(
            f"delivery filename {filename!r} must contain configured versionName {expected_version!r}"
        )
    expected_core = expected_version.split("+", 1)[0].split("-", 1)[0]
    versions = CORE_VERSION_RE.findall(filename)
    mismatches = sorted(set(versions) - {expected_core})
    if mismatches:
        raise CheckError(f"delivery filename {filename!r} also labels another version: {', '.join(mismatches)}")


def check_release_doc(path: Path, expected_version: str, expected_code: int) -> None:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        raise CheckError(f"cannot read release document {path}: {error}") from error
    lines = text.splitlines()
    first_versioned_heading: str | None = None
    heading_index = 0
    for index, line in enumerate(lines):
        match = HEADING_RE.match(line)
        if match:
            versions = VERSION_RE.findall(match.group(1))
            if versions:
                first_versioned_heading = versions[0]
                heading_index = index
                break
    if first_versioned_heading is None:
        raise CheckError(f"{path}: no versioned Markdown heading identifies the current release")
    if first_versioned_heading != expected_version:
        raise CheckError(
            f"{path}: first versioned heading is {first_versioned_heading}, expected {expected_version}"
        )

    # Only the current release section is checked; historical Changelog sections
    # must remain untouched and must not fail the current release's code check.
    section_end = len(lines)
    for index in range(heading_index + 1, len(lines)):
        if lines[index].startswith("## "):
            section_end = index
            break
    for match in CODE_RE.finditer("\n".join(lines[heading_index:section_end])):
        documented_code = int(match.group(1))
        if documented_code != expected_code:
            raise CheckError(
                f"{path}: current-release versionCode {documented_code} disagrees with Gradle {expected_code}"
            )


def run(args: argparse.Namespace) -> None:
    configured = read_gradle_version(args.gradle_file)
    print(f"Gradle: versionName={configured.version_name}, versionCode={configured.version_code}")

    for delivery_name in args.delivery_file:
        check_delivery_name(delivery_name, configured.version_name)
        print(f"OK delivery filename: {Path(delivery_name).name}")

    for release_doc in args.release_doc:
        check_release_doc(release_doc, configured.version_name, configured.version_code)
        print(f"OK current-release document: {release_doc}")

    if args.apk:
        metadata = read_apk_metadata(args.apk)
        if configured.application_id and metadata.package_name != configured.application_id:
            raise CheckError(
                f"APK package {metadata.package_name!r} disagrees with Gradle applicationId {configured.application_id!r}"
            )
        if metadata.version_name != configured.version_name or metadata.long_version_code != configured.version_code:
            raise CheckError(
                "APK metadata disagrees with Gradle: "
                f"APK versionName={metadata.version_name!r}, longVersionCode={metadata.long_version_code}; "
                f"Gradle versionName={configured.version_name!r}, versionCode={configured.version_code}"
            )
        print(
            f"OK APK metadata: package={metadata.package_name}, versionName={metadata.version_name}, "
            f"longVersionCode={metadata.long_version_code} ({args.apk})"
        )
    else:
        print("OK pre-build mode: APK metadata check skipped (--no-apk)")

    print("Release integrity check passed.")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--gradle-file", type=Path, default=DEFAULT_GRADLE_FILE, help="app/build.gradle.kts")
    apk_mode = parser.add_mutually_exclusive_group(required=True)
    apk_mode.add_argument("--apk", type=Path, help="built APK whose embedded manifest must match Gradle")
    apk_mode.add_argument("--no-apk", action="store_true", help="pre-build check: skip APK metadata inspection")
    parser.add_argument(
        "--delivery-file",
        action="append",
        required=True,
        metavar="PATH_OR_NAME",
        help="delivery APK/archive path or filename; repeat for each artifact (basename is checked)",
    )
    parser.add_argument(
        "--release-doc",
        action="append",
        required=True,
        type=Path,
        help="current release Markdown doc/report; repeat for each document",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        run(args)
    except CheckError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
