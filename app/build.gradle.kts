import java.util.Properties
import java.net.URI
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}
val defaultBackendProperties = Properties().apply { rootProject.file("backend.properties.example").inputStream().use { load(it) } }
val backendProperties = Properties().apply { rootProject.file("backend.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
fun backendValue(name: String): String {
    val value=(System.getenv(name) ?: backendProperties.getProperty(name, defaultBackendProperties.getProperty(name, ""))).trim()
    if(value.isNotEmpty()) {
        val uri=runCatching { URI(value) }.getOrNull()
        require(uri!=null && uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.rawQuery==null && uri.rawFragment==null && uri.host!="open.neis.go.kr") { "Backend configuration must contain public HTTPS endpoints only; credentials and query parameters are prohibited." }
    }
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
}
val versionProperties = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val releaseProperties = Properties().apply { rootProject.file(".signing/signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
fun signingValue(name: String, local: String) = System.getenv(name) ?: releaseProperties.getProperty(local, "")
val releaseStore = signingValue("ANDROID_KEYSTORE_PATH", "storeFile")
val releaseStorePassword = signingValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("ANDROID_KEY_PASSWORD", "keyPassword")
val signingReady = listOf(releaseStore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it.isNotBlank() }
android {
    namespace = "kr.han.meal"
    compileSdk = 36
    defaultConfig {
        applicationId = "kr.han.meal"
        minSdk = 26
        targetSdk = 36
        versionCode = versionProperties.getProperty("VERSION_CODE").toInt()
        versionName = versionProperties.getProperty("VERSION_NAME")
        buildConfigField("String", "UPDATE_REPOSITORY", "\"${versionProperties.getProperty("UPDATE_REPOSITORY", "")}\"")
        buildConfigField("String", "MEAL_API_BASE_URL", "\"${backendValue("MEAL_API_BASE_URL")}\"")
        buildConfigField("String", "SCHOOL_DIRECTORY_BASE_URL", "\"${backendValue("SCHOOL_DIRECTORY_BASE_URL")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    if (signingReady) signingConfigs.create("release") {
        storeFile = rootProject.file(releaseStore)
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
    }
    buildTypes.getByName("release") {
        signingConfig = signingConfigs.findByName("release")
        isMinifyEnabled = false
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "1g"
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort").forEach { name ->
                System.getProperty(name)?.let { value -> it.systemProperty(name, value) }
            }
            it.systemProperty("http.nonProxyHosts", "localhost|127.*|[::1]")
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    ksp("androidx.room:room-compiler:2.8.3")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.21.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.room:room-testing:2.8.3")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

val validateReleaseCredentials = tasks.register("validateReleaseCredentials") {
    doLast {
        check(signingReady && rootProject.file(releaseStore).isFile) {
            "Release signing is not configured. Run python3 scripts/setup_signing.py or supply ANDROID_KEYSTORE_* / ANDROID_KEY_ALIAS / ANDROID_KEY_PASSWORD. Unsigned releases are prohibited."
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(validateReleaseCredentials) }
