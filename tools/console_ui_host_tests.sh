#!/usr/bin/env bash
# Opt-in Compose/Android simulation. Does not change app dependencies, ABI or SDK policy.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE=${1:-current}
[[ "$MODE" == current || "$MODE" == --baseline ]] || { echo 'Use current or --baseline [NEW_OUTPUT_DIR]' >&2; exit 2; }
OUT=${2:-"$ROOT/verification-output/console-ui-host-$(date +%s)"}
[[ ! -e "$OUT" ]] || { echo "Refusing to overwrite evidence: $OUT" >&2; exit 1; }
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
TEST_DIR="$ROOT/app/src/test/java/com/nyx/ai/ui"
GENERATED=(ConsolePhoneHostRegressionTest.kt ConsoleExpandedHostRegressionTest.kt ConsoleShortHostRegressionTest.kt BaselineConsoleUi.kt BaselineConsoleViewportHostTest.kt)
for name in "${GENERATED[@]}"; do
  [[ ! -e "$TEST_DIR/$name" ]] || { echo "Refusing to overwrite test source: $name" >&2; exit 1; }
done
cleanup() { for name in "${GENERATED[@]}"; do rm -f -- "$TEST_DIR/$name"; done; }
trap cleanup EXIT
export NYX_CONSOLE_UI_HOST_OUT="$OUT"
python3 - "$ROOT" "$OUT" "$MODE" <<'PY'
from pathlib import Path
import sys, subprocess, json, hashlib
root, out = map(Path, sys.argv[1:3]); mode = sys.argv[3]
dest = root / 'app/src/test/java/com/nyx/ai/ui'
dest.mkdir(parents=True, exist_ok=True)
if mode == '--baseline':
    baseline = subprocess.check_output(['git', 'show', '8c736932a6f8832c443ab776f502306a9236f41f:app/src/main/java/com/nyx/ai/ui/WorkerConsoleUi.kt'], cwd=root, text=True)
    for name in ['WorkerConsoleDialog', 'WorkerActivityPreview', 'WorkerActivityEntry']:
        baseline = baseline.replace('fun '+name+'(', 'fun Baseline'+name+'(')
    (dest/'BaselineConsoleUi.kt').write_text(baseline)
    (dest/'BaselineConsoleViewportHostTest.kt').write_text((root/'tools/fixtures/console-ui/BaselineConsoleViewportHostTest.kt').read_text())
else:
    original = (root/'app/src/androidTest/java/com/nyx/ai/ui/WorkerConsoleScrollingInstrumentedTest.kt').read_text()
    for name, size in [('Phone', 'w360dp-h640dp-mdpi'), ('Expanded', 'w840dp-h600dp-mdpi'), ('Short', 'w640dp-h360dp-mdpi')]:
        text = original.replace('class WorkerConsoleScrollingInstrumentedTest', f'class Console{name}HostRegressionTest')
        text = text.replace('@RunWith(AndroidJUnit4::class)', '@RunWith(org.robolectric.RobolectricTestRunner::class)\n'
            f'@org.robolectric.annotation.Config(sdk = [35], qualifiers = "{size}")\n'
            '@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)')
        # PixelCopy has no real display surface in Robolectric; draw its native Canvas instead.
        text = text.replace('check(compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))',
            "compose.runOnIdle { val view = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView; "
            "val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888); "
            "view.draw(android.graphics.Canvas(bitmap)); check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) }")
        (dest/f'Console{name}HostRegressionTest.kt').write_text(text)
(out/'host.init.gradle').write_text('''gradle.beforeProject { p ->
    p.pluginManager.withPlugin('com.android.application') {
        p.extensions.getByName('android').testOptions.unitTests.includeAndroidResources = true
        p.dependencies.add('testImplementation', 'org.robolectric:robolectric:4.16.1')
        p.dependencies.add('testImplementation', 'androidx.compose.ui:ui-test-junit4')
        p.dependencies.add('testImplementation', 'androidx.test.espresso:espresso-core:3.6.1')
        p.tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
            maxHeapSize = '2g'
            systemProperty 'robolectric.dependency.repo.url', 'https://repo.maven.apache.org/maven2'
            systemProperty 'nyx.console.screenshots', System.getenv('NYX_CONSOLE_UI_HOST_OUT') + '/screenshots'
        }
    }
}
''')
inputs = ['app/src/main/java/com/nyx/ai/ui/WorkerConsoleUi.kt', 'app/src/main/java/com/nyx/ai/ui/ConsoleTimelinePolicy.kt',
          'app/src/androidTest/java/com/nyx/ai/ui/WorkerConsoleScrollingInstrumentedTest.kt']
(out/'inputs.json').write_text(json.dumps({'mode': mode, 'scope': 'Robolectric API 35 Android simulation, not physical Android/fold validation; production compile/target remain 37',
    'sha256': {path: hashlib.sha256((root/path).read_bytes()).hexdigest() for path in inputs}}, indent=2)+'\n')
PY
cd "$ROOT"
FILTER='*Console*HostRegressionTest'
[[ "$MODE" != --baseline ]] || FILTER='*BaselineConsoleViewportHostTest'
set +e
./gradlew --no-daemon --max-workers=2 -I "$OUT/host.init.gradle" testDebugUnitTest --tests "$FILTER" 2>&1 | tee "$OUT/build.log"
RESULT=${PIPESTATUS[0]}
set -e
mkdir -p "$OUT/test-results"
find app/build/test-results/testDebugUnitTest -maxdepth 1 -name 'TEST-*.xml' -exec cp -- {} "$OUT/test-results/" \;
echo 'Host simulation only. Baseline mode intentionally fails when the old viewport collapses.'
exit "$RESULT"
