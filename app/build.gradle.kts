plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI builds one ABI per APK. Without this property, local debug builds remain
// universal for development and the existing testDebugUnitTest task is unchanged.
val ciAbi = providers.gradleProperty("ciAbi").orNull
require(ciAbi == null || ciAbi in setOf("armeabi-v7a", "arm64-v8a")) {
    "ciAbi must be armeabi-v7a or arm64-v8a"
}

android {
    namespace = "ir.aai.hushyar"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.aai.hushyar"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "0.16.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (ciAbi != null) {
            ndk { abiFilters.add(ciAbi) }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // On-JVM SQLite migration/persistence tests: no emulator or NDK required in CI.
    testImplementation("org.robolectric:robolectric:4.14.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Keep the SQLite/Robolectric tests runnable on small local builders as well as in CI.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    maxHeapSize = "512m"
    maxParallelForks = 1
    // Robolectric downloads its Android 14 runtime during JVM tests, outside
    // Gradle's repository resolver. The default repo1.maven.org returned 404
    // for .pom.sha512 in Actions, although the artifact exists on canonical
    // Maven Central. Point Robolectric itself at the canonical endpoint.
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
}
