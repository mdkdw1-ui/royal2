import java.io.File
import java.net.URL

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
    // 🟢 JitPack 저장소를 완전히 제거하여 GitHub Actions의 무차별 토큰 주입 타겟에서 완전히 제외시킵니다.
}

// 🟢 [핵심] 빌드가 시작될 때 가상 머신 몰래 일반 HTTP 통신으로 OpenCV AAR 파일을 직접 다운로드하는 작업입니다.
tasks.register("downloadOpenCV") {
    val outputFile = file("libs/opencv-android-4.6.0.aar")
    outputs.file(outputFile)

    doLast {
        if (!outputFile.exists()) {
            outputFile.parentFile.mkdirs()
            println("🚀 [우회 작전] JitPack에서 OpenCV 라이브러리를 직접 다운로드하는 중...")
            try {
                // Gradle 저장소 메커니즘을 쓰지 않으므로 오염된 인증 헤더가 절대 붙지 않습니다.
                URL("https://jitpack.io/com/github/jeziellago/opencv-android/4.6.0/opencv-android-4.6.0.aar").openStream().use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                println("✅ 다운로드 성공: ${outputFile.absolutePath}")
            } catch (e: Exception) {
                throw GradleException("OpenCV 다운로드 실패: ${e.message}", e)
            }
        }
    }
}

// 안드로이드 빌드 초기 단계(preBuild)에서 위의 다운로드 작업이 무조건 선행되도록 강제 결합합니다.
tasks.named("preBuild") {
    dependsOn("downloadOpenCV")
}

dependencies {
    // 안드로이드 기본 핵심 라이브러리
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // 🟢 원격 저장소 에러를 완전히 파괴하고, 위에서 로컬로 다운로드 완료한 순수 AAR 파일을 다이렉트로 결합합니다.
    implementation(files("libs/opencv-android-4.6.0.aar"))

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:core-runtime:3.5.1")
}
