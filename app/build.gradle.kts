plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.hakkiiler.voiceassistant"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.hakkiiler.voiceassistant"
        minSdk = 24
        targetSdk = 35
        versionCode = 9
        versionName = "1.4.0"

        // Нативное рисование (stable-diffusion.cpp) собираем только под arm64:
        // на остальных ABI экран «Рисование» честно пишет, что устройство не поддерживается.
        externalNativeBuild {
            cmake {
                abiFilters += listOf("arm64-v8a")
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    ndkVersion = "27.0.12077973"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // full — модели Vosk внутри APK (~123 МБ), работает сразу офлайн.
    // lite — без моделей (<24 МБ), скачивает их один раз при первом запуске.
    flavorDimensions += "dist"
    productFlavors {
        create("full") {
            dimension = "dist"
            buildConfigField("boolean", "BUNDLED_MODELS", "true")
            ndk {
                // телефоны + x86_64-эмулятор; Vosk весит ~10 МБ нативного кода на ABI
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
        create("lite") {
            dimension = "dist"
            versionNameSuffix = "-lite"
            buildConfigField("boolean", "BUNDLED_MODELS", "false")
            ndk {
                abiFilters += listOf("arm64-v8a") // почти все современные телефоны
            }
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
        // java.time on API 24/25
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// lite: нативные библиотеки (Vosk + stable-diffusion.cpp) кладём в APK сжатыми —
// файл вдвое меньше, система распакует их при установке.
androidComponents {
    onVariants(selector().withFlavor("dist" to "lite")) { variant ->
        variant.packaging.jniLibs.useLegacyPackaging.set(true)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Offline speech recognition
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    testImplementation("junit:junit:4.13.2")
}
