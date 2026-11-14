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
preflight() {
  python3 tools/check_sdk_policy.py --self-test
  python3 tools/check_sdk_policy.py --json
  python3 tools/check_release_integrity.py --self-test
  python3 tools/check_release_integrity.py --check-repository
  python3 tools/check_release_integrity.py --no-apk \
    --delivery-file NYX-v0.9.0.apk --delivery-file NYX-Source-v0.9.0.zip \
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
    --delivery-file NYX-v0.9.0.apk --delivery-file NYX-Source-v0.9.0.zip --release-doc CHANGELOG.md
}
package_built() {
  # Packaging is separate so a parent can perform its own combined clean build.
  # CI verification artifacts must never be mistaken for upgrade candidates.
  [[ "$SIGNING" == "" ]] || { echo 'Ephemeral CI signer cannot package distribution files.' >&2; exit 1; }
  [[ ! -e "$OUT" ]] || { echo "Refusing to overwrite output: $OUT" >&2; exit 1; }
  mkdir -p "$OUT"
  verify_built
  cp app/build/outputs/apk/debug/app-debug.apk "$OUT/NYX-v0.9.0.apk"
  cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "$OUT/NYX-v0.9.0-androidTest.apk"
  python3 tools/check_release_integrity.py --make-source "$OUT/NYX-Source-v0.9.0.zip"
  (cd "$OUT" && sha256sum NYX-v0.9.0.apk NYX-v0.9.0-androidTest.apk NYX-Source-v0.9.0.zip > NYX-v0.9.0-SHA256SUMS.txt)
  python3 tools/check_release_integrity.py --verify-delivery-dir "$OUT"
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
