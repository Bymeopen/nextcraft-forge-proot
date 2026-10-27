plugins {
    id("com.android.application") version "9.4.1" apply false
    // Pins the Kotlin that AGP 9 builds with to the one in the device's offline Maven cache (AGP would otherwise ask for 2.2.10).
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}
