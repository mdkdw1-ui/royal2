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
        
        // ✨ OpenCV 라이브러리를 정상적으로 다운로드받기 위해 이 라인을 추가합니다.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "royal2"
include(":app")
