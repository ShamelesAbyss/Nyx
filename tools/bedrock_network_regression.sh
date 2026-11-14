#!/usr/bin/env bash
# Finite host-only regression runner. No Android/Keystore/Ollama/provider/device acceptance claim.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
KOTLINC="${KOTLINC:-/tmp/nyx-toolchain-tests/kotlin-home/bin/kotlinc}"
CACHE="${GRADLE_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/nyx-bedrock-network.XXXXXX")"
printf 'Network host evidence: %s\n' "$OUT"
jar() {
    local found
    found="$(find "$CACHE/$1/$2/$3" -type f -name "$2-$3.jar" -print -quit)"
    test -n "$found" || { printf 'Missing cached dependency: %s:%s:%s\n' "$1" "$2" "$3" >&2; exit 2; }
    printf '%s' "$found"
}
CP="$(jar org.jetbrains.kotlin kotlin-stdlib 2.2.10):$(jar org.jetbrains.kotlinx kotlinx-coroutines-core-jvm 1.9.0):$(jar com.squareup.okhttp3 okhttp 4.12.0):$(jar com.squareup.okhttp3 mockwebserver 4.12.0):$(jar com.squareup.okio okio-jvm 3.6.0):$(jar org.json json 20240303):$(jar junit junit 4.13.2):$(jar org.hamcrest hamcrest-core 1.3)"
S="$ROOT/app/src/main/java/com/nyx/ai"
T="$ROOT/app/src/test/java/com/nyx/ai"
SOURCES=(
    "$S/network/NetworkAddressPolicy.kt" "$S/network/InferenceEndpointPolicy.kt" "$S/network/NetworkTransportGuard.kt"
    "$S/network/NetworkPolicy.kt" "$S/network/NetworkTools.kt" "$S/network/OllamaClient.kt" "$S/network/ProviderFactory.kt"
    "$S/network/InferenceProvider.kt" "$S/network/InferenceJson.kt" "$S/network/InferenceTimeouts.kt"
    "$S/network/ModelCapability.kt" "$S/network/ModelDiagnostic.kt" "$S/network/InferenceFabric.kt"
    "$S/settings/CredentialTransportPolicy.kt" "$S/settings/CredentialReferencePolicy.kt"
    "$S/gateway/GatewayModels.kt" "$S/gateway/McpClient.kt" "$S/gateway/GitHubGateway.kt" "$S/gateway/GatewayRepository.kt"
    "$S/workbench/ResearchEngine.kt" "$S/workbench/HtmlTextExtractor.kt" "$S/workbench/WorkbenchStore.kt"
    "$S/workbench/WorkbenchModel.kt" "$S/workbench/WorkbenchManifestCodec.kt" "$S/workbench/ArtifactFormats.kt" "$S/workbench/ZipBundleWriter.kt"
    "$S/autonomy/ExecutionTimeoutPolicy.kt" "$S/autonomy/PlanningLifecyclePolicy.kt" "$S/autonomy/PlanningRecovery.kt"
    "$S/autonomy/ObjectivePlanParser.kt" "$S/autonomy/CompactPlanningPolicy.kt" "$S/autonomy/Verification.kt"
    "$S/autonomy/IncrementalPlanning.kt" "$S/autonomy/SovereignPolicy.kt" "$S/agent/AgentToolProvider.kt" "$S/performance/PerformanceModel.kt"
    "$S/autonomy/policy/AutonomyPolicy.kt"
)
TESTS=(
    network.NetworkPolicyTest network.NetworkToolsTest network.OllamaClientAuthTest network.BedrockNetworkSecurityTest
    settings.CredentialTransportPolicyTest settings.CredentialReferencePolicyTest
    gateway.McpClientTest gateway.GitHubGatewayTest gateway.GatewayRepositoryTest gateway.BedrockGatewaySecurityTest
    workbench.ResearchEngineTest workbench.HtmlTextExtractorTest
)
for test in "${TESTS[@]}"; do SOURCES+=("$T/${test//.//}.kt"); done
printf '%s\n' "${SOURCES[@]}" > "$OUT/sources.txt"
sha256sum "${SOURCES[@]}" > "$OUT/source-sha256.txt"
printf '%s\n' "$CP" > "$OUT/classpath.txt"
"$KOTLINC" -jvm-target 17 -classpath "$CP" -d "$OUT/classes" "${SOURCES[@]}" > "$OUT/compile.log" 2>&1 || { cat "$OUT/compile.log"; exit 1; }
printf 'Compilation passed; running local fake-server/byte-fixture regressions.\n'
CLASSES=()
for test in "${TESTS[@]}"; do CLASSES+=("com.nyx.ai.$test"); done
java -cp "$OUT/classes:$CP" org.junit.runner.JUnitCore "${CLASSES[@]}" 2>&1 | tee "$OUT/tests.log"
printf 'Host regression evidence complete: %s\n' "$OUT"
