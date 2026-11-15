#!/usr/bin/env bash
# One coordinated build; never publishes, installs, makes an AAB, or changes keys.
set -euo pipefail
cd "$(dirname "$0")/.."
export PYTHONDONTWRITEBYTECODE=1
MODE=${1:---preflight}
OUT=${2:-verification-output/candidate}
SIGNING=${3:-}
if [[ "$SIGNING" != "" && "$SIGNING" != "--ci-ephemeral-signer" ]]; then
  echo 'Unknown signing scope; use established local debug signer by default.' >&2; exit 2
fi

VERSION=$(python3 - <<'PY'
from pathlib import Path
import re
text = Path("app/build.gradle.kts").read_text(encoding="utf-8")
matches = re.findall(r'(?m)^\s*versionName\s*=\s*["\']([^"\']+)["\']\s*(?://.*)?$', text)
if len(matches) != 1:
    raise SystemExit(f"expected exactly one literal versionName, found {len(matches)}")
print(matches[0])
PY
)
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?$ ]] || {
  echo "Unsupported versionName for release packaging: $VERSION" >&2; exit 2;
}
TAG="v${VERSION}"
DELIVERY_APK="NYX-${TAG}.apk"
DELIVERY_TEST="NYX-${TAG}-androidTest.apk"
SOURCE_ZIP="NYX-Source-${TAG}.zip"
NOTES_FILE=${NYX_RELEASE_NOTES:-"RELEASE_NOTES_${TAG}.md"}
NOTES_ASSET="NYX-${TAG}-RELEASE_NOTES.md"
REPORT_FILE=${NYX_IMPLEMENTATION_REPORT:-}
REPORT_ASSET="NYX-${TAG}-IMPLEMENTATION_REPORT.md"
EVIDENCE_ASSET="NYX-${TAG}-VERIFICATION.zip"
SUMS="NYX-${TAG}-SHA256SUMS.txt"

preflight() {
  [[ -f "$NOTES_FILE" ]] || { echo "Missing release notes: $NOTES_FILE" >&2; exit 1; }
  if [[ -n "$REPORT_FILE" ]]; then
    [[ -f "$REPORT_FILE" ]] || { echo "Missing implementation report: $REPORT_FILE" >&2; exit 1; }
  fi
  python3 tools/check_sdk_policy.py --self-test
  python3 tools/check_sdk_policy.py --json
  python3 tools/check_release_integrity.py --self-test
  python3 tools/check_release_integrity.py --check-repository
  python3 tools/check_release_integrity.py --no-apk \
    --delivery-file "$DELIVERY_APK" --delivery-file "$SOURCE_ZIP" \
    --release-doc CHANGELOG.md --release-doc BUILD.md
  command -v kotlinc >/dev/null || { echo 'Real kotlinc on PATH is required; fixture skips are not compiler acceptance.' >&2; exit 1; }
  kotlinc -version
  java -version
}
verify_built() {
  : "${ANDROID_HOME:?Set ANDROID_HOME to the SDK with Build Tools 36.0.0 and NDK 28.2.13676358}"
  local signer=()
  [[ "$SIGNING" == "" ]] || signer+=("$SIGNING")
  python3 tools/check_release_integrity.py --verify-artifacts \
    --apk app/build/outputs/apk/debug/app-debug.apk \
    --android-test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    --sdk-root "$ANDROID_HOME" --report-dir "$OUT/artifact-checks" "${signer[@]}"
  python3 tools/check_release_integrity.py --apk app/build/outputs/apk/debug/app-debug.apk \
    --delivery-file "$DELIVERY_APK" --delivery-file "$SOURCE_ZIP" --release-doc CHANGELOG.md
}
package_built() {
  # Packaging is separate so a parent can perform its own combined clean build.
  # CI verification artifacts must never be mistaken for upgrade candidates.
  [[ "$SIGNING" == "" ]] || { echo 'Ephemeral CI signer cannot package distribution files.' >&2; exit 1; }
  [[ ! -e "$OUT" ]] || { echo "Refusing to overwrite output: $OUT" >&2; exit 1; }
  mkdir -p "$OUT"
  verify_built
  cp app/build/outputs/apk/debug/app-debug.apk "$OUT/$DELIVERY_APK"
  cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "$OUT/$DELIVERY_TEST"
  cp "$NOTES_FILE" "$OUT/$NOTES_ASSET"
  local extras=("$NOTES_ASSET" "$EVIDENCE_ASSET")
  if [[ -n "$REPORT_FILE" ]]; then
    cp "$REPORT_FILE" "$OUT/$REPORT_ASSET"
    extras+=("$REPORT_ASSET")
  fi
  local bundle_args=(--artifact-checks "$OUT/artifact-checks")
  if [[ -n "${NYX_BUILD_OUT:-}" ]]; then
    bundle_args+=(--build-output "$NYX_BUILD_OUT")
  fi
  if [[ -d app/build/test-results/testDebugUnitTest ]]; then
    bundle_args+=(--test-results app/build/test-results/testDebugUnitTest)
  fi
  python3 tools/make_verification_bundle.py "$OUT/$EVIDENCE_ASSET" "${bundle_args[@]}"
  python3 tools/check_release_integrity.py --make-source "$OUT/$SOURCE_ZIP"
  (cd "$OUT" && sha256sum "$DELIVERY_APK" "$DELIVERY_TEST" "$SOURCE_ZIP" "$NOTES_ASSET" "$EVIDENCE_ASSET" ${REPORT_FILE:+"$REPORT_ASSET"} > "$SUMS")
  local extra_args=()
  for asset in "${extras[@]}"; do extra_args+=(--extra-delivery-file "$asset"); done
  python3 tools/check_release_integrity.py --verify-delivery-dir "$OUT" "${extra_args[@]}"
}
case "$MODE" in
  --preflight) preflight ;;
  --verify-built) verify_built ;;
  --package) preflight; package_built ;;
  --build)
    [[ ! -e "$OUT" ]] || { echo "Refusing to overwrite output: $OUT" >&2; exit 1; }
    mkdir -p "$OUT"
    preflight
    : "${ANDROID_HOME:?Set ANDROID_HOME}"
    export CMAKE_BUILD_PARALLEL_LEVEL=2
    # All owners must finish edits and the shared-workspace build barrier must be released first.
    ./gradlew --no-daemon --max-workers=2 --console=plain clean testDebugUnitTest assembleDebug assembleDebugAndroidTest \
      :app:dependencies --configuration debugRuntimeClasspath 2>&1 | tee "$OUT/build-and-dependencies.log"
    verify_built
    # Archive verification exercises source correspondence without delivering CI APKs.
    python3 tools/check_release_integrity.py --make-source "$OUT/verification-source.zip"
    ;;
  *) echo 'Usage: bash tools/build_release.sh --preflight|--build|--verify-built|--package [NEW_OUTPUT_DIR] [--ci-ephemeral-signer]' >&2; exit 2 ;;
esac
