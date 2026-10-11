pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // 用 PREFER_SETTINGS 而不是 FAIL_ON_PROJECT_REPOS：Gradle 更宽容，
    // 也不会和 init 脚本注入的镜像打架
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "curtain"
include(":app")
