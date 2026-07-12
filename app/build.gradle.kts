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

// 🟢 [최종 수정] Gradle 9.x의 까다로운 DSL 스코프를 완전히 회피하기 위해 자바 순수 ProcessBuilder를 사용합니다.
tasks.register("downloadOpenCV") {
    val outputFile = file("libs/opencv-android-4.6.0.aar")
    outputs.file(outputFile)

    doLast {
        if (!outputFile.exists()) {
            outputFile.parentFile.mkdirs()
            println("🚀 [우회 작전] Gradle DSL 격리를 피해 Java ProcessBuilder로 curl을 호출합니다.")
            
            // Gradle API를 쓰지 않으므로 컴파일 에러가 절대 나지 않습니다.
            val process = ProcessBuilder(
                "curl", "-L", "-s", "--fail",
                "https://jitpack.io/com/github/jeziellago/opencv-android/4.6.0/opencv-android-4.6.0.aar",
                "-o", outputFile.absolutePath
            ).inheritIO().start() // 로그가 가상 머신 콘솔에 바로 찍히도록 연동
            
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw GradleException("curl 다운로드 명령이 실패했습니다. (Exit Code: $exitCode)")
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

    // 다운로드된 로컬 AAR 파일 다이렉트 결합
    implementation(files("libs/opencv-android-4.6.0.aar"))

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:core-runtime:3.5.1")
}
