#!/usr/bin/env bash
# Finite Linux terminal regressions; current app classes are READ-ONLY inputs. Never invokes Gradle.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../../../../../.." && pwd)"
OUT="${BEDROCK_TERMINAL_OUT:-/home/ubuntu/bedrock-terminal-tests}"
KOTLINC="${KOTLINC:-/tmp/nyx-toolchain-tests/kotlin-home/bin/kotlinc}"
JAVA="${JAVA:-/usr/lib/jvm/java-21-openjdk-amd64/bin/java}"
BASELINE="${BEDROCK_APP_CLASSES:-$ROOT/app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes}"
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
jar() { find "$CACHE/$1/$2/$3" -type f -name '*.jar' -print -quit; }
STDLIB="$(jar org.jetbrains.kotlin kotlin-stdlib 2.2.10)"
COROUTINES="$(jar org.jetbrains.kotlinx kotlinx-coroutines-core-jvm 1.9.0)"
JUNIT="$(jar junit junit 4.13.2)"
HAMCREST="$(jar org.hamcrest hamcrest-core 1.3)"
JSON="$(jar org.json json 20240303)"
ANDROID="${ANDROID_JAR:-/home/ubuntu/Android/Sdk/platforms/android-37.0/android.jar}"
for file in "$KOTLINC" "$JAVA" "$STDLIB" "$COROUTINES" "$JUNIT" "$HAMCREST" "$JSON" "$ANDROID"; do
  test -f "$file" || { echo "Missing isolated dependency: $file" >&2; exit 1; }
done
test -d "$BASELINE/com/nyx/ai/terminal" || { echo "Missing compiled Bedrock app classes: $BASELINE" >&2; exit 1; }
mkdir -p "$OUT/classes"
MAIN="$ROOT/app/src/main/java/com/nyx/ai"
TEST="$ROOT/app/src/test/java/com/nyx/ai"
CP="$STDLIB:$COROUTINES:$JUNIT:$HAMCREST:$JSON:$BASELINE:$ANDROID"
printf '%s\n' "$CP" > "$OUT/classpath.txt"
echo 'Compiling current TerminalAgentTools overlay and terminal tests; existing app classes are read-only.'
"$KOTLINC" -no-stdlib -no-reflect -jvm-target 17 -Xfriend-paths="$BASELINE" -classpath "$CP" -d "$OUT/classes" \
  "$MAIN/terminal/TerminalAgentTools.kt" "$MAIN/terminal/WorkspaceProvisioner.kt" \
  "$MAIN/terminal/TerminalCommandIdentity.kt" "$MAIN/terminal/TerminalOutputSnapshot.kt" \
  "$TEST/development/DevelopmentOrchestratorTest.kt" \
  "$TEST/terminal/"*.kt 2>&1 | tee "$OUT/compile.log"
DEFAULT_TESTS=(
  com.nyx.ai.terminal.CommandPolicyTest
  com.nyx.ai.terminal.ModelCommandPolicyTest
  com.nyx.ai.terminal.SourceBatchReaderTest
  com.nyx.ai.terminal.TerminalAgentToolsSecurityTest
  com.nyx.ai.terminal.TerminalProjectDetectionTest
  com.nyx.ai.terminal.TerminalTaskIsolationTest
  com.nyx.ai.terminal.BedrockTerminalSecurityTest
  com.nyx.ai.terminal.BedrockFixedDevelopmentTest
)
TESTS=("${DEFAULT_TESTS[@]}")
if (( $# )); then TESTS=("$@"); fi
echo 'Running isolated security regressions and real fixed Kotlin compile/test/snapshot acceptance.'
"$JAVA" -Dbedrock.kotlin.compiler="$KOTLINC" -Dbedrock.terminal.evidence="$OUT/real-kotlin-evidence.json" \
  -cp "$OUT/classes:$CP" org.junit.runner.JUnitCore "${TESTS[@]}" 2>&1 | tee "$OUT/results.log"
