#!/usr/bin/env python3
"""Check NYX's literal Android SDK policy without resolving Gradle dependencies.

The dated policy is deliberately checked into this script, not fetched during a
build. Review the official URL before a release and update this script, the
independent Gradle guard, and docs/ANDROID_COMPATIBILITY.md together. An API 36
fallback is NOT automatic: it requires reproducible SDK 37 unavailability proof.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys

POLICY = {
    "verified_on_utc": "2026-11-12",
    "official_url": "https://support.google.com/googleplay/android-developer/answer/11926878?hl=en",
    "submission_target_floor": 36,
    "preferred_sdk": 37,
    "sdk37_available": True,
    "minimum_sdk": 26,
}
ROOT = Path(__file__).resolve().parents[1]


def check_text(text: str) -> dict[str, int]:
    """Reject ambiguous/nonliteral settings instead of guessing their values."""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    values: dict[str, int] = {}
    for name in ("compileSdk", "targetSdk", "minSdk"):
        declarations = re.findall(rf"(?m)^\s*{name}\s*=\s*([^\n]+)", text)
        if len(declarations) != 1 or not re.fullmatch(r"\d+\s*;?\s*", declarations[0]):
            raise ValueError(f"{name} must have exactly one literal integer assignment")
        values[name] = int(declarations[0].strip().rstrip(";").strip())
    if values["targetSdk"] < POLICY["submission_target_floor"]:
        raise ValueError("targetSdk must be >= 36; a lower target is not permitted")
    if values["compileSdk"] < values["targetSdk"]:
        raise ValueError("compileSdk must be >= targetSdk")
    if values["minSdk"] != POLICY["minimum_sdk"]:
        raise ValueError("minSdk must remain 26")
    if POLICY["sdk37_available"] and min(values["compileSdk"], values["targetSdk"]) < POLICY["preferred_sdk"]:
        raise ValueError("SDK 37 is available: compileSdk/targetSdk must be >= 37; fallback requires documented unavailability and policy review")
    return values


def self_test() -> None:
    good = "compileSdk = 37\ntargetSdk = 37\nminSdk = 26\n"
    assert check_text(good) == {"compileSdk": 37, "targetSdk": 37, "minSdk": 26}
    bad_cases = [
        good.replace("targetSdk = 37", "targetSdk = 35"),
        good.replace("compileSdk = 37", "compileSdk = 36"),
        good.replace("minSdk = 26", "minSdk = 27"),
        good.replace("37", "36"),
        good.replace("targetSdk = 37", "targetSdk = 38"),
        good.replace("targetSdk = 37", "targetSdk = sdkTarget"),
        good + "targetSdk = 35\n",
        good.replace("targetSdk = 37", "// targetSdk = 37"),
    ]
    for text in bad_cases:
        try:
            check_text(text)
        except ValueError:
            continue
        raise AssertionError(f"Invalid SDK configuration was accepted: {text!r}")
    print(f"SDK policy self-test PASS: one valid and {len(bad_cases)} rejected configurations")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-file", type=Path, default=ROOT / "app/build.gradle.kts")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--json", action="store_true", help="Print checked configuration and policy as JSON")
    args = parser.parse_args()
    try:
        if args.self_test:
            self_test()
            return 0
        values = check_text(args.build_file.read_text(encoding="utf-8"))
        if args.json:
            print(json.dumps({"configuration": values, "policy": POLICY}, indent=2))
        else:
            print("SDK policy PASS: " + ", ".join(f"{key}={value}" for key, value in values.items())
                  + f"; official policy checked {POLICY['verified_on_utc']} UTC")
        return 0
    except (OSError, ValueError) as exc:
        print(f"SDK policy FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
