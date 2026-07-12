pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // 프로젝트 내 개별 설정 대신 이 공통 설정을 강제 적용합니다.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral() // 🟢 핵심: 이 줄이 있어야 OpenCV 라이브러리 다운로드 서버가 열립니다.
        maven { url = java.net.URI("https://jitpack.io") } // 백업용 오픈소스 저장소
    }
}

rootProject.name = "royal2"
include(":app")
