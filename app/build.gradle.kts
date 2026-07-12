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

// 🟢 복잡한 인증(credentials, authentication) 블록을 완전히 걷어낸 순정 설정입니다.
repositories {
    google()
    mavenCentral()
    
    // 공개 저장소이므로 이 한 줄만 있으면 빈 헤더 충돌 없이 안전하게 접근합니다.
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // 안드로이드 기본 핵심 라이브러리
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // 깨끗해진 JitPack 경로를 통해 OpenCV 라이브러리가 정상적으로 빌드에 포함됩니다.
    implementation("com.github.jeziellago:opencv-android:4.6.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:core-runtime:3.5.1")
}
