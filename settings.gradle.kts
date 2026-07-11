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
        // OpenCV 안드로이드 빌드 자동 다운로드를 위한 JitPack 저장소 추가
        maven { url = java.net.URI("https://jitpack.io") }
    }
}

rootProject.name = "Match3Solver"
include(":app")
