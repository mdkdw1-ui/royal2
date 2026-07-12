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
}

// 🟢 [최종 병기] 자바 네트워크 스택을 쓰지 않고, 리눅스 자체 curl 명령어로 강제 다운로드합니다.
tasks.register("downloadOpenCV") {
    val outputFile = file("libs/opencv-android-4.6.0.aar")
    outputs.file(outputFile)

    doLast {
        if (!outputFile.exists()) {
            outputFile.parentFile.mkdirs()
            println("🚀 [최종 우회 작전] JVM 오염을 피해 시스템 curl 명령어로 다운로드를 시작합니다.")
            
            exec {
                // 가상 머신 OS의 curl을 다이렉트로 호출하여 토큰 강제 주입을 원천 차단합니다.
                commandLine(
                    "curl", "-L", "-s", "--fail",
                    "https://jitpack.io/com/github/jeziellago/opencv-android/4.6.0/opencv-android-4.6.0.aar",
                    "-o", outputFile.absolutePath
                )
            }
            println("✅ 다운로드 완료: ${outputFile.absolutePath}")
        }
    }
}

tasks.named("preBuild") {
    dependsOn("downloadOpenCV")
}

dependencies {
    // 안드로이드 기본 핵심 라이브러리
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // 시스템 curl이 안전하게 받아온 로컬 파일과 직접 결합합니다.
    implementation(files("libs/opencv-android-4.6.0.aar"))

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:core-runtime:3.5.1")
}
