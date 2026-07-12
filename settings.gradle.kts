pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 🟢 모든 모듈의 저장소를 전역에서 중앙 제어하도록 강제합니다.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 🟢 JitPack 저장소를 전역에 안전하게 등록하여 401/404 인증 우회를 해결합니다.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "royal2"
include(":app")
