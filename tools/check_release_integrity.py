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
import hashlib
import json
import os
import re
import stat
import struct
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Sequence

DEFAULT_GRADLE_FILE = Path(__file__).resolve().parents[1] / "app" / "build.gradle.kts"
ROOT = DEFAULT_GRADLE_FILE.parents[1]
ESTABLISHED_SIGNER = "aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55"
WRAPPER_SHA256 = "b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13"
GRADLE_SHA256 = "b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06"
VENDOR = "app/src/main/cpp/llama.cpp/"
LEGACY = {"NYX.apk", "NYX-Source.zip"}
SOURCE_MANIFEST = "SOURCE-MANIFEST.sha256"
NOTICE_INPUTS = {
    "LICENSE-Apache-2.0.txt": "LICENSE",
    "LICENSE-llama.cpp.txt": VENDOR + "LICENSE",
    "LICENSE-jsonhpp.txt": VENDOR + "licenses/LICENSE-jsonhpp",
    "LICENSE-cpp-httplib.txt": VENDOR + "vendor/cpp-httplib/LICENSE",
    "NDK-NOTICE.txt": "third_party/ndk-r28c/NOTICE",
    "NDK-NOTICE.toolchain.txt": "third_party/ndk-r28c/NOTICE.toolchain",
    "THIRD_PARTY_NOTICES.md": "THIRD_PARTY_NOTICES.md",
}
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


def require(condition: bool, message: str) -> None:
    if not condition:
        raise CheckError(message)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def tool(argv: list[str], report: Path | None = None) -> str:
    """No shell, bounded process wait; nonzero tool exits are always failures."""
    try:
        completed = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, timeout=120, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise CheckError(f"cannot complete {argv[0]}: {error}") from error
    if report:
        report.write_text(completed.stdout, encoding="utf-8")
    require(completed.returncode == 0, f"tool failed ({completed.returncode}): {argv[0]}\n{completed.stdout[-4000:]}")
    return completed.stdout


def safe_member(name: str) -> None:
    path = PurePosixPath(name)
    require(bool(name) and not path.is_absolute() and "\\" not in name
            and not any(part in {"", ".", ".."} for part in name.split("/"))
            and not re.match(r"^[A-Za-z]:", name) and not any(ord(c) < 32 for c in name),
            f"unsafe archive path: {name!r}")


def sensitive_path(name: str) -> bool:
    path = PurePosixPath(name)
    parts = {part.lower() for part in path.parts}
    base = path.name.lower()
    # A retained upstream Keynote .key document is not a private signing key.
    return (bool(parts & {".git", ".gradle", ".kotlin", ".cxx", ".externalnativebuild", ".android", ".ssh"})
            or base in {"local.properties", "keystore.properties", "id_rsa", "id_ed25519", ".env"}
            or base.startswith(".env.")
            or path.suffix.lower() in {".jks", ".keystore", ".gguf", ".safetensors", ".apk", ".aab", ".pyc"})


def sensitive_content(data: bytes) -> bool:
    # Deliberately narrow evidence, not a complete secret/history scanner.
    private_key = re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----")
    github_token = re.compile(rb"\b(?:gh[pousr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{60,255})\b")
    return bool(private_key.search(data) or github_token.search(data))


def source_files(root: Path) -> list[str]:
    raw = subprocess.run(["git", "-C", str(root), "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    require(raw.returncode == 0, "git source inventory failed")
    files = sorted(set(raw.stdout.decode("utf-8").strip("\0").split("\0")) - LEGACY)
    for name in files:
        safe_member(name)
        path = root / name
        require(path.exists(), f"missing inventoried source file: {name}")
        require(not path.is_symlink() and path.is_file(), f"nonregular source input: {name}")
        require(not any((root / parent).is_symlink() for parent in PurePosixPath(name).parents),
                f"symlink source ancestor: {name}")
        require(path.resolve().is_relative_to(root.resolve()), f"source input escapes root: {name}")
        public_upstream_example = name == VENDOR + "tools/ui/.env.example"
        require(not sensitive_path(name) or public_upstream_example, f"sensitive/generated source input: {name}")
        require(not any(p.lower() in {"build", "__pycache__"} for p in PurePosixPath(name).parts),
                f"build/cache input: {name}")
        if not name.startswith(VENDOR):
            require(not sensitive_content(path.read_bytes()), f"private-key/token marker in first-party source: {name}")
    return files


def check_repository(root: Path) -> None:
    require(read_gradle_version(root / "app/build.gradle.kts") == GradleVersion("0.9.0", 23, "com.nyx.ai"),
            "candidate must be literal com.nyx.ai / versionCode 23 / versionName 0.9.0")
    wrapper = root / "gradle/wrapper/gradle-wrapper.jar"
    require(sha256(wrapper.read_bytes()) == WRAPPER_SHA256, "root Gradle wrapper JAR checksum mismatch")
    properties = (root / "gradle/wrapper/gradle-wrapper.properties").read_text()
    for line in (f"distributionSha256Sum={GRADLE_SHA256}",
                 "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.3.1-bin.zip",
                 "validateDistributionUrl=true"):
        require(properties.splitlines().count(line) == 1, f"missing/ambiguous wrapper pin: {line}")
    require(os.access(root / "gradlew", os.X_OK), "root gradlew must remain executable")
    require(b"\r" not in (root / "gradlew").read_bytes(), "gradlew must use LF")
    for name in LEGACY:
        require(not (root / name).exists(), f"retired root bundle remains: {name}")
    for output, source in NOTICE_INPUTS.items():
        require((root / source).is_file() and (root / source).stat().st_size > 0, f"missing notice input: {source}")
        asset = root / "app/src/main/assets/third_party" / output
        require(asset.read_bytes() == (root / source).read_bytes(), f"stale/missing packaged notice source: {output}")
    funding = json.loads((root / "funding.json").read_text())
    require(funding["projects"][0]["licenses"] == ["spdx:Apache-2.0"], "funding license changed")
    files = source_files(root)
    print(f"Repository gate PASS: wrapper/identity/notices; {len(files)} safe source inputs")
    print("Secret check scope: current first-party private-key/GitHub-token markers and all source paths; vendor content/history not scanned.")


def parse_badging(text: str, instrumentation: bool = False) -> None:
    package = re.findall(r"^package: name='([^']+)' versionCode='([^']*)' versionName='([^']*)'", text, re.M)
    require(len(package) == 1, "missing/ambiguous aapt2 package metadata")
    expected = ("com.nyx.ai.test", "23", "0.9.0") if instrumentation else ("com.nyx.ai", "23", "0.9.0")
    # AGP's test APK does not inherit the app's version metadata; enforce identity
    # and target/instrumentation binding, not an invented test version guarantee.
    require(package[0][0] == expected[0], f"unexpected APK package: {package[0][0]}")
    if not instrumentation:
        require(package[0] == expected, f"unexpected APK identity/version: {package[0]}")
    for key, expected_api in (("(?:minSdkVersion|sdkVersion)", "26"), ("targetSdkVersion", "37")):
        require(re.findall(rf"^{key}:'([^']+)'", text, re.M) == [expected_api], f"unexpected {key}")


def parse_signer(text: str, expected: str | None) -> str:
    signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9A-Fa-f]{64})\s*$", text, re.M)
    require(len(signers) == 1, "must have exactly one verified APK signer")
    signer = signers[0].lower()
    require(expected is None or signer == expected.lower(), f"signer continuity failure: {signer}")
    return signer


def check_elf(data: bytes) -> None:
    """Inspect every ELF64 LOAD alignment, independently of external tool output."""
    require(len(data) >= 64 and data[:6] == b"\x7fELF\x02\x01", "native object must be little-endian ELF64")
    require(_u16(data, 18) == 183, "native object must be AArch64")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phsize, phnum = _u16(data, 54), _u16(data, 56)
    require(phsize >= 56 and 0 < phnum <= 256 and phoff + phsize * phnum <= len(data), "invalid ELF program headers")
    loads = 0
    for index in range(phnum):
        offset = phoff + index * phsize
        if _u32(data, offset) != 1:
            continue
        loads += 1
        file_offset, virtual = struct.unpack_from("<QQ", data, offset + 8)
        alignment = struct.unpack_from("<Q", data, offset + 48)[0]
        require(alignment >= 16384 and alignment & (alignment - 1) == 0, "ELF LOAD alignment below 16 KB or invalid")
        require(file_offset % alignment == virtual % alignment, "ELF LOAD offset/address alignment mismatch")
    require(loads > 0, "ELF has no LOAD segments")


def check_apk_entries(archive: zipfile.ZipFile, root: Path, instrumentation: bool = False) -> list[str]:
    entries = archive.infolist()
    names = [info.filename for info in entries]
    require(len(names) == len(set(names)), "duplicate APK entries")
    for info in entries:
        safe_member(info.filename.rstrip("/"))
        require(stat.S_IFMT(info.external_attr >> 16) != stat.S_IFLNK, "symlink in APK")
    libraries = [name for name in names if name.endswith(".so")]
    require(all(re.fullmatch(r"lib/arm64-v8a/[^/]+\.so", name) for name in libraries), "unexpected production native ABI/path")
    if not instrumentation:
        require({"lib/arm64-v8a/libnyx_llama.so", "lib/arm64-v8a/libc++_shared.so"} <= set(libraries), "missing ARM64 JNI/runtime library")
        for output, source in NOTICE_INPUTS.items():
            name = "assets/third_party/" + output
            require(name in names and archive.read(name) == (root / source).read_bytes(), f"missing/stale APK notice: {output}")
    for name in libraries:
        require(archive.getinfo(name).compress_type == zipfile.ZIP_STORED, f"compressed native library: {name}")
        check_elf(archive.read(name))
    return libraries


def verify_artifacts(args: argparse.Namespace) -> None:
    require(args.apk is not None and args.android_test_apk is not None and args.sdk_root is not None
            and args.report_dir is not None, "artifact gate requires both APKs, --sdk-root and new --report-dir")
    args.report_dir.mkdir(parents=True, exist_ok=False)
    bt = args.sdk_root / "build-tools/36.0.0"
    objdump = args.sdk_root / "ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump"
    expected = None if args.ci_ephemeral_signer else ESTABLISHED_SIGNER
    signers = []
    for label, apk, instrumentation in (("app", args.apk, False), ("androidTest", args.android_test_apk, True)):
        require(apk.is_file() and not apk.is_symlink(), f"missing/nonregular APK: {apk}")
        parse_badging(tool([str(bt / "aapt2"), "dump", "badging", str(apk)], args.report_dir / f"{label}-badging.txt"), instrumentation)
        if instrumentation:
            manifest = tool([str(bt / "aapt2"), "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml"],
                            args.report_dir / "androidTest-manifest.txt")
            require(re.search(r'android:targetPackage[^\n]*="com\.nyx\.ai"', manifest) is not None,
                    "instrumentation APK must target com.nyx.ai")
        signers.append(parse_signer(tool([str(bt / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)],
                                        args.report_dir / f"{label}-signature.txt"), expected))
        tool([str(bt / "zipalign"), "-v", "-c", "-P", "16", "4", str(apk)], args.report_dir / f"{label}-zipalign.txt")
        with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory(prefix="nyx-elf-") as temp:
            for index, name in enumerate(check_apk_entries(archive, args.root, instrumentation)):
                library = Path(temp) / f"library-{index}.so"
                library.write_bytes(archive.read(name))
                tool([str(objdump), "-p", str(library)], args.report_dir / f"{label}-{Path(name).name}-elf.txt")
    require(signers[0] == signers[1], "app and instrumentation APK signers differ")
    result = {"package": "com.nyx.ai", "versionCode": 23, "versionName": "0.9.0", "signerSha256": signers[0],
              "signingScope": "ephemeral CI verification only" if args.ci_ephemeral_signer else "established debug distribution identity",
              "androidRuntime": "UNVERIFIED", "apkSha256": sha256(args.apk.read_bytes()),
              "androidTestApkSha256": sha256(args.android_test_apk.read_bytes())}
    (args.report_dir / "artifact-verification.json").write_text(json.dumps(result, indent=2) + "\n")
    print("APK gate PASS: independent identity/SDK/signature/ARM64/notices/ZIP and ELF alignment; no runtime claim")


def make_source(root: Path, destination: Path) -> None:
    files = source_files(root)
    with zipfile.ZipFile(destination, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        lines = []
        for name in files:
            data = (root / name).read_bytes()
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            mode = 0o755 if os.access(root / name, os.X_OK) else 0o644
            info.external_attr = (stat.S_IFREG | mode) << 16
            archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED)
            lines.append(f"{sha256(data)}  {name}\n")
        archive.writestr(SOURCE_MANIFEST, "".join(lines))
    verify_source(root, destination)


def verify_source(root: Path, source: Path) -> None:
    files = source_files(root)
    with zipfile.ZipFile(source) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "duplicate source ZIP entries")
        require(set(names) == set(files) | {SOURCE_MANIFEST}, "source archive does not match current safe source inventory")
        expected_lines = []
        for info in archive.infolist():
            safe_member(info.filename)
            require(stat.S_IFMT(info.external_attr >> 16) != stat.S_IFLNK, "source archive symlink")
        for name in files:
            data = (root / name).read_bytes()
            require(archive.read(name) == data, f"source archive bytes differ: {name}")
            expected_lines.append(f"{sha256(data)}  {name}\n")
        require(archive.read(SOURCE_MANIFEST) == "".join(expected_lines).encode(), "source manifest coverage/bytes mismatch")
    print(f"Source ZIP PASS: {len(files)} exact current working-tree inputs; legacy bundles excluded; not a tagged-tree claim")


def verify_delivery(directory: Path, extras: list[str]) -> None:
    names = {"NYX-v0.9.0.apk", "NYX-v0.9.0-androidTest.apk", "NYX-Source-v0.9.0.zip"}
    for name in extras:
        safe_member(name)
        require(PurePosixPath(name).name == name and "0.9.0" in name, "extra delivery must be a candidate basename")
        names.add(name)
    text = (directory / "NYX-v0.9.0-SHA256SUMS.txt").read_text()
    records = []
    for line in text.splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([^\n]+)", line)
        require(match is not None, "invalid checksum manifest record")
        records.append(match.groups())
    require(len(records) == len(names) and {name for _, name in records} == names, "checksum manifest coverage mismatch")
    for digest, name in records:
        path = directory / name
        require(path.is_file() and not path.is_symlink(), f"missing/nonregular delivery: {name}")
        require(sha256(path.read_bytes()) == digest, f"delivery checksum mismatch: {name}")
    print("Delivery checksum PASS: exact required bytes and coverage (manifest does not hash itself)")


def self_test() -> None:
    """Deterministic negative/positive parser and artifact regressions, no Android/model runtime."""
    def rejected(callback, label):
        try:
            callback()
        except (CheckError, OSError, zipfile.BadZipFile):
            print("PASS rejected: " + label)
            return
        raise AssertionError("invalid fixture accepted: " + label)

    badging = "package: name='com.nyx.ai' versionCode='23' versionName='0.9.0'\nsdkVersion:'26'\ntargetSdkVersion:'37'\n"
    parse_badging(badging)
    parse_badging(badging.replace("sdkVersion:", "minSdkVersion:"))
    parse_badging(badging.replace("name='com.nyx.ai' versionCode='23' versionName='0.9.0'",
        "name='com.nyx.ai.test' versionCode='' versionName=''"), instrumentation=True)
    rejected(lambda: parse_badging(badging + "minSdkVersion:'26'\n"), "ambiguous minimum SDK")
    rejected(lambda: parse_badging(badging.replace("versionCode='23'", "versionCode=''")), "missing app version")
    for old, new in (("com.nyx.ai", "com.evil.ai"), ("'23'", "'22'"), ("'0.9.0'", "'0.8.9'"), ("'37'", "'36'")):
        rejected(lambda old=old, new=new: parse_badging(badging.replace(old, new)), "wrong APK metadata " + new)
    signer = "Signer #1 certificate SHA-256 digest: " + ESTABLISHED_SIGNER + "\n"
    parse_signer(signer, ESTABLISHED_SIGNER)
    rejected(lambda: parse_signer(signer, "0" * 64), "wrong signer")
    rejected(lambda: parse_signer(signer + signer, ESTABLISHED_SIGNER), "multiple signers")
    for name in ("../key", "/private", "a/../b", "a\\b", "C:/key", "a//b"):
        rejected(lambda name=name: safe_member(name), "unsafe ZIP member " + name)
    require(sensitive_path("debug.keystore") and sensitive_path(".env.local"), "secret path fixture")
    require(not sensitive_path("vendor/slides.key"), "Keynote must not be called a signing key")
    require(sensitive_content(b"-----BEGIN " + b"PRIVATE KEY-----\nsecret"), "private-key marker fixture")
    require(not sensitive_content(b"public maintainer contact and ordinary text"), "nonsecret content fixture")
    elf = bytearray(120)
    elf[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", elf, 18, 183)
    struct.pack_into("<Q", elf, 32, 64)
    struct.pack_into("<HH", elf, 54, 56, 1)
    struct.pack_into("<I", elf, 64, 1)
    struct.pack_into("<Q", elf, 112, 16384)
    check_elf(bytes(elf))
    struct.pack_into("<Q", elf, 112, 4096)
    rejected(lambda: check_elf(bytes(elf)), "4 KB ELF LOAD")
    struct.pack_into("<Q", elf, 112, 16384)
    with tempfile.TemporaryDirectory(prefix="nyx-release-regression-") as temp:
        root = Path(temp)
        for output, source in NOTICE_INPUTS.items():
            (root / source).parent.mkdir(parents=True, exist_ok=True)
            (root / source).write_bytes(b"notice " + output.encode())
        apk = root / "fixture.zip"
        def write_apk(missing_notice=False, bad_abi=False, compressed=False):
            with zipfile.ZipFile(apk, "w") as archive:
                for output, source in NOTICE_INPUTS.items():
                    if missing_notice and output == "LICENSE-llama.cpp.txt":
                        continue
                    archive.writestr("assets/third_party/" + output, (root / source).read_bytes())
                for name in ("libnyx_llama.so", "libc++_shared.so"):
                    archive.writestr("lib/" + ("x86_64" if bad_abi else "arm64-v8a") + "/" + name, elf,
                                     compress_type=zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED)
        def inspect():
            with zipfile.ZipFile(apk) as archive:
                check_apk_entries(archive, root)
        write_apk()
        inspect()
        for options, label in (({"missing_notice": True}, "missing MIT notice"), ({"bad_abi": True}, "foreign ABI"),
                               ({"compressed": True}, "compressed native packaging")):
            write_apk(**options)
            rejected(inspect, label)
        # Real subprocess failures, not synthetic successful alignment output.
        rejected(lambda: tool(["sh", "-c", "exit 7"]), "external verifier nonzero")
        names = ["NYX-v0.9.0.apk", "NYX-v0.9.0-androidTest.apk", "NYX-Source-v0.9.0.zip"]
        for name in names:
            (root / name).write_bytes(name.encode())
        sums = root / "NYX-v0.9.0-SHA256SUMS.txt"
        sums.write_text("".join(f"{sha256((root / name).read_bytes())}  {name}\n" for name in names))
        verify_delivery(root, [])
        (root / names[0]).write_bytes(b"changed")
        rejected(lambda: verify_delivery(root, []), "changed delivery bytes")
        sums.write_text("")
        rejected(lambda: verify_delivery(root, []), "missing checksum coverage")
        doc = root / "CHANGELOG.md"
        doc.write_text("# History\n## 0.9.0 candidate\nversionCode 23\n## 0.8.9 historical\nversionCode 22\n")
        check_release_doc(doc, "0.9.0", 23)
        rejected(lambda: check_release_doc(doc, "0.8.9", 22), "stale current release heading")
        source_root = root / "source-fixture"
        source_root.mkdir()
        tool(["git", "init", "--quiet", str(source_root)])
        source_file = source_root / "main.txt"
        source_file.write_text("verified source bytes\n")
        source_zip = root / "source.zip"
        make_source(source_root, source_zip)
        source_file.write_text("changed after archive\n")
        rejected(lambda: verify_source(source_root, source_zip), "stale source archive bytes")
        source_file.write_text("verified source bytes\n")
        with zipfile.ZipFile(source_zip, "a") as archive:
            archive.writestr("../private", "unselected bytes")
        rejected(lambda: verify_source(source_root, source_zip), "unselected/traversal source ZIP member")
        link = source_root / "linked.txt"
        link.symlink_to(source_file)
        rejected(lambda: source_files(source_root), "symlink source input")
        link.unlink()
        secret = source_root / "private.keystore"
        secret.write_bytes(b"fixture, not a real key")
        rejected(lambda: source_files(source_root), "signing material source input")
    print("Release checker self-test PASS; fake APK/ELF/parser fixtures only, not Android execution")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--gradle-file", type=Path, default=DEFAULT_GRADLE_FILE, help="app/build.gradle.kts")
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--check-repository", action="store_true")
    parser.add_argument("--verify-artifacts", action="store_true")
    parser.add_argument("--android-test-apk", type=Path)
    parser.add_argument("--sdk-root", type=Path)
    parser.add_argument("--report-dir", type=Path)
    parser.add_argument("--ci-ephemeral-signer", action="store_true", help="verification only; NEVER distribute these CI APKs")
    parser.add_argument("--make-source", type=Path)
    parser.add_argument("--verify-source", type=Path)
    parser.add_argument("--verify-delivery-dir", type=Path)
    parser.add_argument("--extra-delivery-file", action="append", default=[])
    apk_mode = parser.add_mutually_exclusive_group()
    apk_mode.add_argument("--apk", type=Path, help="built APK whose embedded manifest must match Gradle")
    apk_mode.add_argument("--no-apk", action="store_true", help="pre-build check: skip APK metadata inspection")
    parser.add_argument(
        "--delivery-file",
        action="append",
        default=[],
        metavar="PATH_OR_NAME",
        help="delivery APK/archive path or filename; repeat for each artifact (basename is checked)",
    )
    parser.add_argument(
        "--release-doc",
        action="append",
        default=[],
        type=Path,
        help="current release Markdown doc/report; repeat for each document",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        modes = [args.self_test, args.check_repository, args.verify_artifacts, args.make_source,
                 args.verify_source, args.verify_delivery_dir]
        require(sum(bool(mode) for mode in modes) <= 1, "select exactly one extended verification mode")
        if args.self_test:
            self_test()
        elif args.check_repository:
            check_repository(args.root)
        elif args.verify_artifacts:
            verify_artifacts(args)
        elif args.make_source:
            make_source(args.root, args.make_source)
        elif args.verify_source:
            verify_source(args.root, args.verify_source)
        elif args.verify_delivery_dir:
            verify_delivery(args.verify_delivery_dir, args.extra_delivery_file)
        else:
            require((args.apk is not None or args.no_apk) and args.delivery_file and args.release_doc,
                    "metadata mode requires --apk/--no-apk, --delivery-file and --release-doc")
            run(args)
    except (CheckError, OSError, ValueError, zipfile.BadZipFile, struct.error) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
