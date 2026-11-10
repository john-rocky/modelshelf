/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.hilt.application)
  alias(libs.plugins.oss.licenses)
  alias(libs.plugins.ksp)
}

// Model Shelf: the release key comes from outside the repository, and a release build never falls back
// to the debug key or to an unsigned file. Four values, each read from the first place that has it:
//   1. a Gradle property:      -Pmodelshelf.release.storeFile=...  (storePassword, keyAlias, keyPassword)
//   2. an environment variable: MODELSHELF_RELEASE_STORE_FILE  (_STORE_PASSWORD, _KEY_ALIAS, _KEY_PASSWORD)
//   3. a properties file outside the checkout with the keys storeFile, storePassword, keyAlias and
//      keyPassword: -Pmodelshelf.release.signing=<file> or MODELSHELF_RELEASE_SIGNING=<file>, else
//      ~/.modelshelf/release-signing.properties
// The keystore path is absolute (a relative one is read from this app module's folder). Without all four
// values, or without the keystore file, the tasks that make a signed release (assembleRelease,
// bundleRelease and the packaging and signing tasks under them) stop before any task runs, with one
// sentence; debug builds and unit tests never read these values.
val releaseSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val releaseSigningFile: File =
  file(
    (findProperty("modelshelf.release.signing") as String?)?.takeIf { it.isNotBlank() }
      ?: System.getenv("MODELSHELF_RELEASE_SIGNING")?.takeIf { it.isNotBlank() }
      ?: "${System.getProperty("user.home")}/.modelshelf/release-signing.properties"
  )
val releaseSigning: Map<String, String> = run {
  val fromFile = Properties()
  if (releaseSigningFile.isFile) releaseSigningFile.inputStream().use { fromFile.load(it) }
  releaseSigningKeys
    .mapNotNull { key ->
      val env = "MODELSHELF_RELEASE_" + key.replace(Regex("([A-Z])"), "_$1").uppercase()
      val value =
        (findProperty("modelshelf.release.$key") as String?)?.takeIf { it.isNotEmpty() }
          ?: System.getenv(env)?.takeIf { it.isNotEmpty() }
          ?: fromFile.getProperty(key)?.takeIf { it.isNotEmpty() }
      value?.let { key to it }
    }
    .toMap()
}
val releaseSigningMissing: List<String> =
  releaseSigningKeys.filter { it !in releaseSigning } +
    listOfNotNull(
      releaseSigning["storeFile"]
        ?.takeIf { !file(it).isFile }
        ?.let { "a keystore file at $it" }
    )

android {
  namespace = "com.google.ai.edge.gallery"
  compileSdk { this.version = release(37) { minorApiLevel = 0 } }

  defaultConfig {
    applicationId = "io.github.johnrocky.modelshelf"
    minSdk = 31
    targetSdk = 37
    versionCode = 52
    versionName = "1.0.23"

    // Needed for HuggingFace auth workflows.
    // Use the scheme of the "Redirect URLs" in HuggingFace app.
    manifestPlaceholders["appAuthRedirectScheme"] =
        "REPLACE_WITH_YOUR_REDIRECT_SCHEME_IN_HUGGINGFACE_APP"
    manifestPlaceholders["applicationName"] = "com.google.ai.edge.gallery.GalleryApplication"
    manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"

    buildConfigField("String", "FEEDBACK_API_KEY", "\"\"")
    // The LiteRT-LM version the app links, compared with a model file's min_runtime_version.
    buildConfigField("String", "LITERTLM_VERSION", "\"${libs.versions.litertlm.get()}\"")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    // Filled only when every value is there; otherwise the release tasks stop (see the end of this file).
    create("release") {
      if (releaseSigningMissing.isEmpty()) {
        storeFile = file(releaseSigning.getValue("storeFile"))
        storePassword = releaseSigning.getValue("storePassword")
        keyAlias = releaseSigning.getValue("keyAlias")
        keyPassword = releaseSigning.getValue("keyPassword")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  packaging {
    jniLibs {
      pickFirsts.add("**/liblitertlm_jni.so")
      // The NPU runtime finds its dispatch library and the Hexagon skel files in the app's native
      // library folder, so the libraries are extracted at install.
      useLegacyPackaging = true
    }
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_11)
    freeCompilerArgs.addAll("-Xskip-metadata-version-check")
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.compose.navigation)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlin.reflect)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.datastore)
  implementation(libs.com.google.code.gson)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.webkit)
  implementation(libs.litertlm)
  // Phonon-2.5-English (speech recognition): LiteRT's CompiledModel on the CPU, GPU and, on the
  // Galaxy S26 (SM8850), the NPU through LiteRT's Qualcomm dispatch and the QNN runtime.
  implementation(libs.litert.core)
  implementation(libs.litert.gpu)
  implementation(libs.litert.npu.qualcomm)
  implementation(libs.qnn.runtime)
  implementation(libs.commonmark)
  implementation(libs.richtext)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.openid.appauth)
  implementation(libs.androidx.splashscreen)
  implementation(libs.protobuf.javalite)
  implementation(libs.protobuf.kotlin.lite)
  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.play.services.oss.licenses)
  implementation(libs.androidx.exifinterface)
  // SkillManager uses DocumentFile, which reached the app only through the Firebase libraries.
  implementation("androidx.documentfile:documentfile:1.1.0")
  implementation(libs.moshi.kotlin)
  ksp(libs.hilt.android.compiler)
  ksp("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0")
  annotationProcessor("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0")
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.hilt.android.testing)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
  ksp(libs.moshi.kotlin.codegen)
  implementation(libs.mlkit.genai.prompt)
  implementation(libs.mcp.kotlin.sdk)
  implementation(libs.ktor.client.android)
  implementation(libs.ktor.client.core)
  // Model Shelf: the local API (DESIGN.md §g). No class of ktor-server-core or ktor-server-cio
  // 3.4.3 refers to jansi (desktop console colors, with native libraries for other systems).
  implementation(libs.ktor.server.cio) { exclude(group = "org.fusesource.jansi", module = "jansi") }
  implementation(libs.tasks.vision)
  implementation(libs.tasks.retrieval)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.transformer)
  implementation(libs.androidx.media3.ui)
  implementation(libs.coil.compose)
  implementation(libs.androidx.xr.projected)
  implementation(libs.mlkit.genai.common)
  implementation(libs.mlkit.genai.speech.recognition)
  implementation(libs.mlkit.genai.text.to.speech)
  implementation(libs.mlkit.genai.schema)
  ksp(libs.mlkit.genai.schema.compiler)
  implementation(libs.tasks.audio)
  implementation(libs.androidx.pdf.core)
  implementation(libs.androidx.pdf.document.service)
}

configurations.all { resolutionStrategy.force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0") }

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.26.1" }
  generateProtoTasks {
    all().forEach { task ->
      task.builtins {
        create("java") { option("lite") }
        create("kotlin") { option("lite") }
      }
    }
  }
}

// Model Shelf: a release build without its key stops before any task runs (the values: after the plugins
// block). The tasks below make or sign a release APK or app bundle; nothing else reads the key.
val releaseSigningTasks =
  Regex(
    "^(assembleRelease|bundleRelease|packageRelease|packageReleaseBundle|signReleaseBundle|" +
      "validateSigningRelease|installRelease|packageReleaseUniversalApk|" +
      "(makeApkFromBundleFor|extractApksFor)Release)$"
  )
gradle.taskGraph.whenReady {
  val needsKey =
    allTasks.filter {
      it.path.startsWith("${project.path}:") &&
        releaseSigningTasks.matches(it.path.substringAfterLast(':'))
    }
  if (needsKey.isNotEmpty() && releaseSigningMissing.isNotEmpty()) {
    throw GradleException(
      "The release build has no release key (missing: ${releaseSigningMissing.joinToString()}), so " +
        "${needsKey.first().path} stops here instead of signing with the debug key or making an " +
        "unsigned file: pass storeFile, storePassword, keyAlias and keyPassword as " +
        "-Pmodelshelf.release.<name> Gradle properties, as the MODELSHELF_RELEASE_STORE_FILE, " +
        "_STORE_PASSWORD, _KEY_ALIAS and _KEY_PASSWORD environment variables, or in a properties " +
        "file outside the checkout (${releaseSigningFile.path}, or the file that " +
        "-Pmodelshelf.release.signing or MODELSHELF_RELEASE_SIGNING names)."
    )
  }
}
