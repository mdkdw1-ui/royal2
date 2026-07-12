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
        
        // 🟢 GitHub Actions의 전역 토큰 강제 주입으로 인한 401 에러를 방지하는 우회 설정
        maven {
            url = java.net.URI("https://jitpack.io")
            credentials {
                username = ""
                password = ""
            }
        }
    }
}

rootProject.name = "royal2"
include(":app")
