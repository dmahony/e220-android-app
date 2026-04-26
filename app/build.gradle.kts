plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

fun gitOutput(vararg args: String): String? {
    return try {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.waitFor() == 0) output.takeIf { it.isNotEmpty() } else null
    } catch (_: Exception) {
        null
    }
}

fun computeVersionCode(): Int {
    val override = providers.gradleProperty("appVersionCode").orNull?.toIntOrNull()
        ?: System.getenv("APP_VERSION_CODE")?.toIntOrNull()
    if (override != null && override > 0) return override

    val commitCount = gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
    return 1000 + commitCount
}

fun computeVersionName(): String {
    val override = providers.gradleProperty("appVersionName").orNull
        ?: System.getenv("APP_VERSION_NAME")
    if (!override.isNullOrBlank()) return override

    val exactTag = gitOutput("describe", "--tags", "--exact-match")
    if (!exactTag.isNullOrBlank()) return exactTag

    val commitCount = gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
    val shortHash = gitOutput("rev-parse", "--short=8", "HEAD") ?: "local"
    return "0.1.0-dev.$commitCount+$shortHash"
}

android {
    namespace = "com.dmahony.e220chat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dmahony.e220chat"
        minSdk = 26
        targetSdk = 34
        versionCode = computeVersionCode()
        versionName = computeVersionName()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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
        debug {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
