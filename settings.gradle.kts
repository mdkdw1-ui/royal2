pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // 🟢 개별 프로젝트 레벨에서 의존성 저장소를 자유롭고 확실하게 제어하도록 허용합니다.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
}

rootProject.name = "royal2"
include(":app")
