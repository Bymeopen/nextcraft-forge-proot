pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Terminal emulator libraries (com.termux.terminal / com.termux.view). Limited to that one group so no
        // other dependency can be resolved from JitPack.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.termux.termux-app") }
        }
    }
}

rootProject.name = "NextCraftForge"
include(":app")
