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
        mavenCentral() // 🧹 속 썩이던 JitPack 관련 코드는 깔끔하게 삭제!
    }
}

rootProject.name = "royal2"
include(":app")
