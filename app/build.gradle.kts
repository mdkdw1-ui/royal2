import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.helper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.helper"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

repositories {
    google()
    mavenCentral()
    // 🟢 JitPack 원격 저장소를 안전하게 추가합니다 (settings의 PREFER_PROJECT 규칙 덕분에 작동 가능)
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // 안드로이드 기본 핵심 라이브러리
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // 🟢 복잡한 로컬 AAR 수동 다운로드 대신 표준 Gradle 원격 의존성 주입을 사용합니다.
    implementation("com.github.jeziellago.opencv-android:opencv:4.6.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:core-runtime:3.5.1")
}
