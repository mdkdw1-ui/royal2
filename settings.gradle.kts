pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 🟢 핵심: JitPack 저장소를 등록하여 외부 OpenCV 라이브러리를 무조건 다운로드받을 수 있게 합니다.
        maven { url = java.net.URI("https://jitpack.io") }
    }
}

rootProject.name = "royal2"
include(":app")
