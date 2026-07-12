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

// 🟢 여기서 가상 머신(CI)이 강제로 심어놓은 인증 토큰을 원천 차단합니다.
repositories {
    google()
    mavenCentral()
    
    maven {
        url = java.net.URI("https://jitpack.io")
        
        authentication {
            // 1. GitHub Actions가 전역으로 주입한 모든 인증 방식(Basic Auth 등)을 완전히 초기화합니다.
            clear() 
        }
        credentials {
            // 2. 공백("")이 아닌 완전한 null을 선언하여 인증 헤더가 조립되는 것 자체를 방지합니다.
            username = null
            password = null
        }
    }
}

dependencies {
    // 안드로이드 기본 핵심 라이브러리
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // 이제 깨끗한 무인증 상태로 JitPack 서버에서 정상 다운로드됩니다.
    implementation("com.github.jeziellago:opencv-android:4.6.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:core-runtime:3.5.1")
}
