pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 🟢 개별 모듈에서 저장소를 정의하지 못하게 하고 여기서만 정의하도록 강제합니다.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 🟢 JitPack을 가장 안전한 방식으로 등록
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "royal2"
include(":app")
