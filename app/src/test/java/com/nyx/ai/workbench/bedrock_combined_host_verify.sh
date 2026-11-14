#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../../../../../.." && pwd)"
# Override only with an explicitly selected worktree, never a protected-baseline output path.
ROOT="${NYX_BEDROCK_ROOT:-$ROOT}"
OUT="$(mktemp -d /tmp/nyx-bedrock-combined.XXXXXXXX)"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
KOTLINC="${KOTLINC:-/tmp/nyx-toolchain-tests/kotlin-home/bin/kotlinc}"
ANDROID="${ANDROID_JAR:-/home/ubuntu/Android/Sdk/platforms/android-37.0/android.jar}"
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
API="${NYX_API_CLASSES:-/home/ubuntu/nyx-bedrock/app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes}"
jar() { find "$CACHE/$1/$2/$3" -type f -name '*.jar' -print -quit; }
CP="$(jar org.jetbrains.kotlin kotlin-stdlib 2.2.10):$(jar org.jetbrains.kotlinx kotlinx-coroutines-core-jvm 1.9.0):$(jar org.jetbrains annotations 13.0):$(jar junit junit 4.13.2):$(jar org.hamcrest hamcrest-core 1.3):$(jar org.json json 20240303):$ANDROID:$API"
mkdir "$OUT/aar" "$OUT/classes" "$OUT/typecheck"
while IFS= read -r file; do
    case "$file" in */androidx.room/*/2.6.1/*) continue;; esac
    id="$(printf '%s' "$file" | sha256sum | cut -d' ' -f1)"
    target="$OUT/aar/$id.jar"
    if unzip -p "$file" classes.jar > "$target" 2>/dev/null; then CP="$CP:$target"; else rm "$target"; fi
done < <(find "$CACHE" -type f -name '*.aar' | sort)
while IFS= read -r file; do CP="$CP:$file"; done < <(find "$CACHE/com.squareup.okhttp3" "$CACHE/com.squareup.okio" -name '*.jar' | sort)
CP="$CP:$(jar androidx.room room-common-jvm 2.8.5):$(jar androidx.annotation annotation-jvm 1.9.1)"
R="$(find "$ROOT/app/build/intermediates/compile_r_class_jar" -name R.jar -print -quit 2>/dev/null || true)"
if [[ -z "$R" ]]; then R=/home/ubuntu/nyx/app/build/intermediates/compile_r_class_jar/debug/generateDebugRFile/R.jar; fi
CP="$CP:$R"
printf 'Worktree: %s\nOutput: %s\nJava: %s\nCompiler: %s\nRead-only surrounding API classes: %s\nAndroid API jar is compile-only, not hardware/IPC\n' "$ROOT" "$OUT" "$JAVA_HOME" "$KOTLINC" "$API" | tee "$OUT/provenance.txt"
printf '%s\n' "$CP" > "$OUT/classpath.txt"
MAIN="$ROOT/app/src/main/java/com/nyx/ai"
mapfile -t SOURCES < <(find "$MAIN" -name '*.kt' ! -path '*/ui/*' ! -name MainActivity.kt ! -name NyxViewModel.kt | sort)
# Compile current engine/service/terminal/store/capability implementations together. No old versions
# of those implementation classes are used at runtime; retained API classes supply untouched UI/Room APIs.
"$KOTLINC" -no-stdlib -no-reflect -jvm-target 17 -classpath "$CP" -d "$OUT/classes" "${SOURCES[@]}" \
    "$ROOT/app/src/test/java/com/nyx/ai/workbench/CombinedDevelopmentBedrockTest.kt" \
    "$ROOT/app/src/test/java/com/nyx/ai/workbench/CombinedAgentValidationBedrockTest.kt" \
    2>&1 | tee "$OUT/compile.log"
"$JAVA_HOME/bin/java" -Dbedrock.combined.evidence="$OUT/acceptance.json" -Dbedrock.kotlin.compiler="$KOTLINC" \
    -cp "$OUT/classes:$CP" org.junit.runner.JUnitCore \
    com.nyx.ai.workbench.CombinedDevelopmentBedrockTest com.nyx.ai.workbench.CombinedAgentValidationBedrockTest \
    2>&1 | tee "$OUT/results.log"
printf '%s\n' "$OUT" > /home/ubuntu/bedrock-combined-evidence-path.txt
sha256sum "${SOURCES[@]}" "$ROOT/app/src/test/java/com/nyx/ai/workbench/CombinedDevelopmentBedrockTest.kt" \
    "$ROOT/app/src/test/java/com/nyx/ai/workbench/CombinedAgentValidationBedrockTest.kt" > "$OUT/source-inputs.sha256"
echo "Combined host verification completed: $OUT"
