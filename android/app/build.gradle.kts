plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "net.jivenet.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.jivenet.client"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "0.9.4"
        vectorDrawables { useSupportLibrary = true }

        // dnstt-client собран только под arm64-v8a (95%+ современных
        // Android-устройств). Ограничиваем APK этой ABI: иначе на
        // armeabi-v7a или x86_64 приложение установится, но libdnstt_client.so
        // не будет найден и Proxy не запустится. Для поддержки других ABI
        // нужен NDK и сборка соответствующего бинарника.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

// dnstt-client собирается в jniLibs как libdnstt_client.so (на каждый ABI).
// Gradle копирует содержимое src/main/jniLibs/<abi>/ в APK автоматически.
// ApplicationInfo.nativeLibraryDir в рантайме указывает на распакованный
// каталог с этими файлами — оттуда их можно запускать через ProcessBuilder.

android.packaging {
    // .so-файлы должны быть несжатыми, иначе на Android < 10 их не получится
    // exec-нуть напрямую (Android извлекает их на диск только если они не сжаты
    // ИЛИ если extractNativeLibs=true). Для надёжности — extractNativeLibs.
    jniLibs.useLegacyPackaging = true
}

dependencies {
    // tun2socks (gomobile bind → app/libs/tun2socks.aar)
    // Если AAR ещё не собран, fileTree вернёт пустой набор — Gradle не упадёт,
    // а Tun2socksBridge через рефлексию определит что класса нет.
    implementation(fileTree("libs") { include("*.aar") })

    // AndroidX / Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")

    // Хранение настроек
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // QR-сканирование (ML Kit работает offline)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // JSON-парсинг конфигов (KotlinX)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
