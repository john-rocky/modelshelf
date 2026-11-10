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

import java.security.KeyStore
import java.security.cert.X509Certificate
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
// The keystore path is absolute (`~/` is the home folder) and outside this checkout. Without all four
// values, without the keystore file, with a relative path or one inside the checkout, the tasks that
// make a signed release (assembleRelease, bundleRelease and the packaging and signing tasks under them)
// stop before any task runs, with one sentence. They also stop when the key is the debug key (its alias,
// its file, or its certificate "Android Debug") and when Android Studio's signing properties
// (android.injected.signing.*) are set: those sign with a key this file does not check (D-614 round 10d,
// review finding 15). The values are read on every Gradle run; a properties file that cannot be read
// stops only the release tasks. Pass the passwords through the environment or the file: a
// -Pmodelshelf.release.storePassword=... argument shows in the process list and the shell's history.
val releaseSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val releaseSigningFile: File =
  file(
    (findProperty("modelshelf.release.signing") as String?)?.takeIf { it.isNotBlank() }
      ?: System.getenv("MODELSHELF_RELEASE_SIGNING")?.takeIf { it.isNotBlank() }
      ?: "${System.getProperty("user.home")}/.modelshelf/release-signing.properties"
  )
// A properties file that cannot be read: it stops the release tasks only (D-614 round 10d).
val releaseSigningUnreadable: List<String> =
  if (!releaseSigningFile.isFile) emptyList()
  else
    try {
      releaseSigningFile.inputStream().use { Properties().load(it) }
      emptyList()
    } catch (e: Exception) {
      listOf("a properties file that can be read (${releaseSigningFile.path})")
    }
val releaseSigning: Map<String, String> = run {
  val fromFile = Properties()
  if (releaseSigningFile.isFile && releaseSigningUnreadable.isEmpty()) {
    releaseSigningFile.inputStream().use { fromFile.load(it) }
  }
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
// The keystore file: `~/` is the home folder; a relative path is not taken.
val releaseStoreFile: File? =
  releaseSigning["storeFile"]?.let { raw ->
    val expanded = if (raw.startsWith("~/")) System.getProperty("user.home") + raw.substring(1) else raw
    File(expanded).takeIf { it.isAbsolute }?.canonicalFile
  }
// The checkout this file is in (Android/src is two folders below it).
val releaseCheckout: File = rootProject.projectDir.resolve("../..").canonicalFile
val releaseSigningMissing: List<String> =
  releaseSigningUnreadable +
    releaseSigningKeys.filter { it !in releaseSigning } +
    listOfNotNull(
      releaseSigning["storeFile"]?.let { raw ->
        val store = releaseStoreFile
        when {
          store == null -> "an absolute keystore path (not $raw)"
          !store.isFile -> "a keystore file at ${store.path}"
          store.path.startsWith(releaseCheckout.path + File.separator) ->
            "a keystore outside the checkout (${store.path} is inside ${releaseCheckout.path})"
          else -> null
        }
      }
    )
// Android Studio's signed-build dialog sets these; the release would be signed with their key.
val releaseSigningInjected: List<String> =
  listOf("store.file", "store.password", "key.alias", "key.password")
    .map { "android.injected.signing.$it" }
    .filter { findProperty(it) != null }

// Whether the release key is the debug key: its alias, its file, or its certificate. Read only when a
// release task runs (the certificate needs the store's password).
fun releaseKeyIsDebugKey(): String? {
  val store = releaseStoreFile ?: return null
  if (releaseSigning["keyAlias"].equals("androiddebugkey", ignoreCase = true)) {
    return "its alias is androiddebugkey"
  }
  val debugStore = File(System.getProperty("user.home"), ".android/debug.keystore")
  if (debugStore.isFile && store == debugStore.canonicalFile) return "its file is ${debugStore.path}"
  return try {
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
    store.inputStream().use {
      keyStore.load(it, releaseSigning.getValue("storePassword").toCharArray())
    }
    val certificate =
      keyStore.getCertificate(releaseSigning.getValue("keyAlias")) as? X509Certificate
    if (certificate?.subjectX500Principal?.name?.contains("CN=Android Debug") == true) {
      "its certificate is Android Debug"
    } else null
  } catch (e: Exception) {
    // The store does not open with these values: the signing task says so itself.
    null
  }
}

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
        storeFile = releaseStoreFile
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
  // Model Shelf: the JVM tests reach code that logs through android.util.Log and takes a Context it
  // never reads; the Android stubs return defaults there instead of throwing.
  testOptions { unitTests.isReturnDefaultValues = true }
  packaging {
    jniLibs {
      pickFirsts.add("**/liblitertlm_jni.so")
      // The NPU runtime finds its dispatch library and the Hexagon skel files in the app's native
      // library folder, so the libraries are extracted at install.
      useLegacyPackaging = true
    }
  }
}

// Model Shelf: the JVM tests run on Java 21: the LiteRT-LM classes (Contents, Conversation) are Java
// 21 class files, which a Java 17 test JVM cannot load.
tasks.withType<Test>().configureEach {
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
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
  // Model Shelf: the Custom Tabs of openExternalPage. Declared here, at the version AppAuth brought
  // in before (1.3.0): a library that only comes through another one goes when that one goes (round
  // 10d; the same trap as androidx.documentfile when Firebase went).
  implementation(libs.androidx.browser)
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
      "(makeApkFromBundleFor|extractApksFor|extractApksFromBundleFor)Release)$"
  )
gradle.taskGraph.whenReady {
  val needsKey =
    allTasks.filter {
      it.path.startsWith("${project.path}:") &&
        releaseSigningTasks.matches(it.path.substringAfterLast(':'))
    }
  if (needsKey.isNotEmpty() && releaseSigningInjected.isNotEmpty()) {
    throw GradleException(
      "The release build stops: Android Studio's signing properties are set " +
        "(${releaseSigningInjected.joinToString()}), and ${needsKey.first().path} would sign with " +
        "their key, which this build does not check: build the release with the Model Shelf values " +
        "(see app/build.gradle.kts) instead."
    )
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
  if (needsKey.isNotEmpty()) {
    releaseKeyIsDebugKey()?.let { why ->
      throw GradleException(
        "The release build stops: the release key is the debug key ($why), and " +
          "${needsKey.first().path} never signs a release with it."
      )
    }
  }
}
