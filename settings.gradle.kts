pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 🟢 모든 모듈의 저장소 설정을 여기서 강제 통합 (중복 방지)
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 🟢 JitPack 저장소 추가
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "royal2"
include(":app")
