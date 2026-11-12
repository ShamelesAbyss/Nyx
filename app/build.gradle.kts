plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.nyx.ai"
    compileSdk = 37
    // AGP 9.1.1's supported minimum/default; SDK 37 does not require BT 37.
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.nyx.ai"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "0.6.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DCMAKE_CXX_STANDARD=17",
                    "-DCMAKE_CXX_STANDARD_REQUIRED=ON",
                    "-DANDROID_STL=c++_shared"
                )
                targets += "nyx_llama"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP built-in Kotlin inherits targetCompatibility; no obsolete kotlinOptions.
    buildFeatures { compose = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // AGP >= 8.5.1 zip-aligns uncompressed JNI libraries to 16 KB.
        // NDK r28+ supplies 16 KB ELF alignment by default.
        jniLibs { useLegacyPackaging = false }
    }
}

// Re-evaluate the official policy before every release; last checked 2026-11-12 UTC.
// https://support.google.com/googleplay/android-developer/answer/11926878?hl=en
// Keep this Gradle check independent of the Python checker so it cannot silently
// accept a downgrade if the script's constants/parser are changed accidentally.
val policyCompileSdk = requireNotNull(android.compileSdk)
val policyTargetSdk = requireNotNull(android.defaultConfig.targetSdk)
val policyMinSdk = requireNotNull(android.defaultConfig.minSdk)
val checkSdkPolicyGradle by tasks.registering {
    group = "verification"
    description = "Reject unsupported Android SDK configuration independently of Python."
    doLast {
        check(policyTargetSdk >= 36) { "NYX requires targetSdk >= 36 (Google Play submission policy)." }
        check(policyCompileSdk >= policyTargetSdk) { "compileSdk must be >= targetSdk." }
        check(policyMinSdk == 26) { "NYX minSdk must remain 26." }
        check(policyCompileSdk >= 37 && policyTargetSdk >= 37) {
            "SDK 37 is available and required. An API 36 fallback needs documented package/build-tools unavailability and explicit policy re-evaluation."
        }
    }
}
val checkSdkPolicy by tasks.registering(Exec::class) {
    group = "verification"
    description = "Check the checked-in SDK configuration and dated release policy."
    dependsOn(checkSdkPolicyGradle)
    workingDir(rootProject.projectDir)
    commandLine("python3", rootProject.file("tools/check_sdk_policy.py").absolutePath)
}
tasks.named("preBuild") { dependsOn(checkSdkPolicy) }

// Validate variant overrides too, not just defaultConfig. This runs during
// configuration and prevents a flavor/variant from bypassing the preBuild guard.
androidComponents.onVariants { variant ->
    check(variant.targetSdk.apiLevel >= 36) { "${variant.name}: targetSdk must be >= 36." }
    check(variant.targetSdk.apiLevel >= 37) { "${variant.name}: available SDK 37 is required." }
    check(policyCompileSdk >= variant.targetSdk.apiLevel) { "${variant.name}: compileSdk < targetSdk." }
    check(variant.minSdk.apiLevel == 26) { "${variant.name}: minSdk must remain 26." }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
