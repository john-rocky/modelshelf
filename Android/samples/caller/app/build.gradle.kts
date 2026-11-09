import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("com.android.application") }

android {
  namespace = "io.github.johnrocky.modelshelf.caller"
  compileSdk { this.version = release(37) { minorApiLevel = 0 } }

  defaultConfig {
    applicationId = "io.github.johnrocky.modelshelf.caller"
    minSdk = 31
    targetSdk = 37
    versionCode = 1
    versionName = "0.1"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }

// No libraries: HttpURLConnection and org.json come with Android.
