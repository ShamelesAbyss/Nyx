#!/usr/bin/env bash
# Opt-in Compose/Android simulation. Does not change app dependencies, ABI or SDK policy.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE=${1:-current}
[[ "$MODE" == current ]] || { echo 'Use current [NEW_OUTPUT_DIR]' >&2; exit 2; }
OUT=${2:-"$ROOT/verification-output/prism-ui-host-$(date +%s)"}
[[ ! -e "$OUT" ]] || { echo "Refusing to overwrite evidence: $OUT" >&2; exit 1; }
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
TEST_DIR="$ROOT/app/src/test/java/com/nyx/ai/ui"
GENERATED=(PrismPhoneHostRegressionTest.kt PrismExpandedHostRegressionTest.kt PrismShortHostRegressionTest.kt PrismLargeTextHostRegressionTest.kt)
for name in "${GENERATED[@]}"; do
  [[ ! -e "$TEST_DIR/$name" ]] || { echo "Refusing to overwrite test source: $name" >&2; exit 1; }
done
cleanup() { for name in "${GENERATED[@]}"; do rm -f -- "$TEST_DIR/$name"; done; }
trap cleanup EXIT
export NYX_PRISM_UI_HOST_OUT="$OUT"
python3 - "$ROOT" "$OUT" "$MODE" <<'PY'
from pathlib import Path
import sys, subprocess, json, hashlib
root, out = map(Path, sys.argv[1:3]); mode = sys.argv[3]
dest = root / 'app/src/test/java/com/nyx/ai/ui'
dest.mkdir(parents=True, exist_ok=True)
original = (root/'app/src/androidTest/java/com/nyx/ai/ui/prism/PrismScreensInstrumentedTest.kt').read_text()
for name, size in [('Phone', 'w360dp-h640dp-mdpi'), ('Expanded', 'w1000dp-h800dp-mdpi'), ('Short', 'w640dp-h360dp-mdpi'), ('LargeText', 'w360dp-h640dp-mdpi')]:
    text = original.replace('package com.nyx.ai.ui.prism', 'package com.nyx.ai.ui\nimport com.nyx.ai.ui.prism.*')
    text = text.replace('class PrismScreensInstrumentedTest', f'class Prism{name}HostRegressionTest')
    text = text.replace('@RunWith(AndroidJUnit4::class)', '@RunWith(org.robolectric.RobolectricTestRunner::class)\n'
        f'@org.robolectric.annotation.Config(sdk = [35], qualifiers = "{size}")\n'
        '@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)')
    if name == 'LargeText':
        text = text.replace('@get:Rule val compose', '@org.junit.Before fun largeText() { org.robolectric.RuntimeEnvironment.setFontScale(1.7f) }\n    @get:Rule val compose')
    # Native host Canvas screenshots capture actual production layout after each section selection.
    text = text.replace('compose.onNodeWithTag("prism_screen_$title").assertIsDisplayed()', 'compose.onNodeWithTag("prism_screen_$title").assertIsDisplayed()\n        compose.runOnIdle {\n            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first()\n            val view = activity.window.decorView\n            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)\n            view.draw(android.graphics.Canvas(bitmap))\n            val file = java.io.File(System.getProperty("nyx.console.screenshots"), "${javaClass.simpleName}_$title.png").apply { parentFile.mkdirs() }\n            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }\n        }')
    text = text.replace('compose.onNodeWithTag("mission_result_summary").assertTextContains("incomplete", substring = true)', 'compose.onNodeWithTag("mission_result_summary").assertTextContains("incomplete", substring = true)\n        compose.runOnIdle {\n            val view = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first().window.decorView\n            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)\n            view.draw(android.graphics.Canvas(bitmap))\n            val file = java.io.File(System.getProperty("nyx.console.screenshots"), "${javaClass.simpleName}_MissionResults.png").apply { parentFile.mkdirs() }\n            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }\n        }\n')
    (dest/f'Prism{name}HostRegressionTest.kt').write_text(text)
(out/'host.init.gradle').write_text('''gradle.beforeProject { p ->
    p.pluginManager.withPlugin('com.android.application') {
        p.extensions.getByName('android').testOptions.unitTests.includeAndroidResources = true
        p.dependencies.add('testImplementation', 'org.robolectric:robolectric:4.16.1')
        p.dependencies.add('testImplementation', 'androidx.compose.ui:ui-test-junit4')
        p.dependencies.add('testImplementation', 'androidx.test.espresso:espresso-core:3.6.1')
        p.tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
            maxHeapSize = '2g'
            systemProperty 'robolectric.dependency.repo.url', 'https://repo.maven.apache.org/maven2'
            systemProperty 'nyx.console.screenshots', System.getenv('NYX_PRISM_UI_HOST_OUT') + '/screenshots'
        }
    }
}
''')
inputs = ['app/src/androidTest/java/com/nyx/ai/ui/prism/PrismScreensInstrumentedTest.kt',
          'app/src/main/java/com/nyx/ai/ui/prism/PrismShell.kt', 'app/src/main/java/com/nyx/ai/ui/prism/MissionResultsUi.kt']
(out/'inputs.json').write_text(json.dumps({'mode': mode, 'scope': 'Robolectric API 35 Android simulation, not physical Android/fold validation; production compile/target remain 37',
    'sha256': {path: hashlib.sha256((root/path).read_bytes()).hexdigest() for path in inputs}}, indent=2)+'\n')
PY
cd "$ROOT"
FILTER='*Prism*HostRegressionTest'
set +e
./gradlew --no-daemon --max-workers=2 -I "$OUT/host.init.gradle" testDebugUnitTest --tests "$FILTER" 2>&1 | tee "$OUT/build.log"
RESULT=${PIPESTATUS[0]}
set -e
mkdir -p "$OUT/test-results"
find app/build/test-results/testDebugUnitTest -maxdepth 1 -name 'TEST-*.xml' -exec cp -- {} "$OUT/test-results/" \;
echo 'PRISM host simulation only; physical device acceptance remains separate.'
exit "$RESULT"
