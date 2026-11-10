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
        // 7단계: 샘플 앱은 프로젝트 의존성이 아니라 "배포된 AAR"을 Maven 좌표로 받는다.
        // ./gradlew :mini-analytics:publishReleasePublicationToLocalBuildRepository 로 채운다.
        maven {
            name = "localBuild"
            url = uri(rootDir.resolve("build/local-maven"))
        }
    }
}

rootProject.name = "mini-analytics-sdk"

include(":mini-analytics")
include(":sample")
