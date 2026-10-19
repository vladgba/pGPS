// Build tooling only. The app itself has no library dependencies:
// it is plain Java on top of the Android framework (no AndroidX, no Kotlin stdlib).
plugins {
    id("com.android.application") version "8.13.2" apply false
}
