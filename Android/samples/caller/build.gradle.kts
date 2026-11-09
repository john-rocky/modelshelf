// The same Android Gradle plugin and Kotlin as Model Shelf (Android/src/gradle/libs.versions.toml).
// AGP 9 compiles Kotlin itself; the Kotlin plugin line only picks the Kotlin version.
plugins {
  id("com.android.application") version "9.0.1" apply false
  id("org.jetbrains.kotlin.android") version "2.2.21" apply false
}
