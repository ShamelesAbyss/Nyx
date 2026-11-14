#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${BEDROCK_LIFECYCLE_OUT:-/home/ubuntu/bedrock-lifecycle-tests}"
KOTLINC="${KOTLINC:-/tmp/nyx-toolchain-tests/kotlin-home/bin/kotlinc}"
JAVA="${JAVA:-/usr/lib/jvm/java-21-openjdk-amd64/bin/java}"
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
jar() { find "$CACHE/$1/$2/$3" -type f -name '*.jar' -print -quit; }
STDLIB="$(jar org.jetbrains.kotlin kotlin-stdlib 2.2.10)"
COROUTINES="$(jar org.jetbrains.kotlinx kotlinx-coroutines-core-jvm 1.9.0)"
JUNIT="$(jar junit junit 4.13.2)"
HAMCREST="$(jar org.hamcrest hamcrest-core 1.3)"
for file in "$KOTLINC" "$JAVA" "$STDLIB" "$COROUTINES" "$JUNIT" "$HAMCREST"; do test -f "$file" || { echo "Missing test dependency: $file" >&2; exit 1; }; done
mkdir -p "$OUT/classes"
ANDROID="${ANDROID_JAR:-/home/ubuntu/Android/Sdk/platforms/android-37.0/android.jar}"
test -f "$ANDROID" || { echo "Missing Android compile-only API jar: $ANDROID" >&2; exit 1; }
CP="$STDLIB:$COROUTINES:$JUNIT:$HAMCREST:$ANDROID"
MAIN="$ROOT/app/src/main/java/com/nyx/ai"
TEST="$ROOT/app/src/test/java/com/nyx/ai"
echo 'Compiling current production lifecycle policies/registry/recovery with a fake transactional DAO (not Android Room).'
"$KOTLINC" -no-stdlib -no-reflect -jvm-target 17 -classpath "$CP" -d "$OUT/classes" \
  "$MAIN/service/ServiceOperationSlot.kt" "$MAIN/autonomy/ExecutionLeasePolicy.kt" \
  "$MAIN/autonomy/ExecutionRecovery.kt" "$MAIN/terminal/TerminalOwnershipPolicy.kt" \
  "$MAIN/terminal/TerminalOperationRegistry.kt" \
  "$MAIN/terminal/TerminalCommandIdentity.kt" "$MAIN/terminal/TerminalDispatchBinding.kt" \
  "$MAIN/terminal/TerminalProtocol.kt" "$MAIN/terminal/TerminalExecutionScope.kt" \
  "$MAIN/terminal/ControlledShell.kt" "$MAIN/terminal/HostShell.kt" "$MAIN/terminal/ShellCommandBuilder.kt" \
  "$TEST/service/ServiceOperationSlotBedrockTest.kt" "$TEST/autonomy/ExecutionLeasePolicyBedrockTest.kt" \
  "$TEST/terminal/TerminalOwnershipBedrockTest.kt" "$TEST/terminal/TerminalDispatchBindingBedrockTest.kt" \
  "$ROOT/tools/fixtures/bedrock-lifecycle/RecoveryStoreFixture.kt" \
  "$ROOT/tools/fixtures/bedrock-lifecycle/ControlledDispatchFixture.kt" \
  "$ROOT/tools/fixtures/bedrock-lifecycle/RoomTransactionFixture.kt" \
  "$ROOT/tools/fixtures/bedrock-lifecycle/ExecutionRecoveryHostRegression.kt" \
  2>&1 | tee "$OUT/compile.log"
echo 'Running deterministic JVM regressions and real test-owned Linux process fixtures.'
"$JAVA" -cp "$OUT/classes:$CP" org.junit.runner.JUnitCore \
  com.nyx.ai.service.ServiceOperationSlotBedrockTest \
  com.nyx.ai.autonomy.ExecutionLeasePolicyBedrockTest \
  com.nyx.ai.autonomy.ExecutionRecoveryHostRegression \
  com.nyx.ai.terminal.TerminalOwnershipBedrockTest \
  com.nyx.ai.terminal.TerminalDispatchBindingBedrockTest 2>&1 | tee "$OUT/results.log"
