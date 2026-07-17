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
        
        // ✨ 다른 저장소 인증 정보(GitHub Packages 토큰 등)가 간섭하여 발생하는 401 에러를 막기 위해
        // JitPack 요청 시 빈(Empty) 인증 정보를 강제로 사용하도록 설정합니다.
        maven { 
            url = uri("https://jitpack.io") 
            credentials {
                username = ""
                password = ""
            }
        }
    }
}

rootProject.name = "royal2"
include(":app")
