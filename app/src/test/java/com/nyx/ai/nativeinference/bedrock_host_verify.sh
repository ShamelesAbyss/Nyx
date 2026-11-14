#!/usr/bin/env bash
# Isolated native-package host verification. No Gradle, Ollama, Android runtime, or bundled model.
# NYX_API_CLASSES must point to compiled surrounding-app API types; this is not a whole-app build.
set -euo pipefail
ROOT=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
: "${NYX_API_CLASSES:?Set to compiled surrounding-app API classes (fixture provenance is logged)}"
: "${KOTLINC:?Set to a compatible Kotlin compiler executable}"
: "${ANDROID_JAR:?Set to a compatible Android SDK android.jar (compile-only)}"
CACHE=${GRADLE_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}
onejar() { find "$CACHE/$1" -name '*.jar' -print -quit; }
CP="$(onejar org.jetbrains.kotlin/kotlin-stdlib/2.2.10):$(onejar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0):$(onejar junit/junit/4.13.2):$(onejar org.json/json/20240303):$(onejar org.hamcrest/hamcrest-core/1.3):$(onejar androidx.annotation/annotation-jvm/1.9.1):$ANDROID_JAR:$NYX_API_CLASSES"
OUT=$(mktemp -d "${TMPDIR:-/tmp}/nyx-bedrock-native-fix.XXXXXX")
printf 'OUT=%s\nROOT=%s\nAPI_FIXTURE=%s\nANDROID_COMPILE_ONLY=%s\n' "$OUT" "$ROOT" "$NYX_API_CLASSES" "$ANDROID_JAR" | tee "$OUT/provenance.txt"
printf '%s\n' "$CP" > "$OUT/classpath.txt"
MAIN="$ROOT/app/src/main/java/com/nyx/ai/nativeinference"
TEST="$ROOT/app/src/test/java/com/nyx/ai/nativeinference"
mkdir "$OUT/classes"
printf 'Compiling current native Kotlin package and tests\n'
"$KOTLINC" -jvm-target 17 -cp "$CP" -d "$OUT/classes" "$MAIN"/*.kt "$TEST"/*.kt > "$OUT/kotlin-compile.log" 2>&1 || { cat "$OUT/kotlin-compile.log"; exit 1; }
printf 'Running fake-backend and synthetic-GGUF JUnit regressions\n'
java -cp "$OUT/classes:$CP" org.junit.runner.JUnitCore \
    com.nyx.ai.nativeinference.GgufMetadataParserTest \
    com.nyx.ai.nativeinference.NativeRequestOptionsTest \
    com.nyx.ai.nativeinference.NativeTokenSinkTest \
    com.nyx.ai.nativeinference.NativeContextPolicyTest \
    com.nyx.ai.nativeinference.NativeModelSidecarTest \
    com.nyx.ai.nativeinference.NativeSessionRuntimeTest | tee "$OUT/junit.log"
printf 'Compiling and running app-owned C++ context policy (no model)\n'
c++ -std=c++17 -Wall -Wextra -Werror -I "$ROOT/app/src/main/cpp" \
    "$ROOT/app/src/main/cpp/tests/bedrock_context_test.cpp" -o "$OUT/context-test"
"$OUT/context-test" | tee "$OUT/context-test.log"
if [[ -n ${NYX_REAL_GGUF:-} ]]; then
    : "${JAVA_HOME:?Set Java home for fresh host JNI build}"
    printf 'Fresh Linux host build and explicit real GGUF acceptance (not Android)\n'
    sha256sum "$NYX_REAL_GGUF" > "$OUT/model-before.sha256"
    cmake -S "$ROOT/app/src/main/cpp" -B "$OUT/cmake" -DCMAKE_BUILD_TYPE=Release -DNYX_BUILD_HOST_TEST=ON > "$OUT/cmake-configure.log" 2>&1
    cmake --build "$OUT/cmake" --target nyx_llama nyx_bedrock_real_smoke -j "${NYX_NATIVE_BUILD_JOBS:-2}" > "$OUT/cmake-build.log" 2>&1
    timeout 180 java -Djava.library.path="$OUT/cmake" -cp "$OUT/classes:$CP" com.nyx.ai.nativeinference.NativeHostJniSmoke "$NYX_REAL_GGUF" > "$OUT/real-jni.log" 2>&1
    timeout 180 java -Djava.library.path="$OUT/cmake" -cp "$OUT/classes:$CP" com.nyx.ai.nativeinference.NativeBedrockHostJniSmoke "$NYX_REAL_GGUF" > "$OUT/bedrock-real-jni.log" 2>&1
    timeout 180 "$OUT/cmake/nyx_bedrock_real_smoke" "$NYX_REAL_GGUF" > "$OUT/bedrock-real-cpp.log" 2>&1
    sha256sum "$NYX_REAL_GGUF" > "$OUT/model-after.sha256"
    cmp "$OUT/model-before.sha256" "$OUT/model-after.sha256"
    sha256sum "$OUT/cmake/libnyx_llama.so" > "$OUT/library.sha256"
    grep -E 'REAL_.*OK' "$OUT/real-jni.log" "$OUT/bedrock-real-jni.log" "$OUT/bedrock-real-cpp.log"
else
    printf 'Real GGUF/JNI acceptance NOT EXECUTED (set NYX_REAL_GGUF to an authorized external file)\n'
fi
printf 'BEDROCK_NATIVE_HOST_DONE output=%s\n' "$OUT" | tee "$OUT/completed.txt"
