import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val terrainMapLibreVersion = "5.6.2"

val ensureTerrainMapLibreAssets by tasks.registering {
    val assetDir = file("src/main/assets")
    val jsFile = assetDir.resolve("maplibre-gl.js")
    val cssFile = assetDir.resolve("maplibre-gl.css")
    outputs.files(jsFile, cssFile)
    doLast {
        assetDir.mkdirs()
        listOf(
            "maplibre-gl.js" to jsFile,
            "maplibre-gl.css" to cssFile
        ).forEach { (name, target) ->
            if (!target.isFile || target.length() < 1024L) {
                val url = URL("https://unpkg.com/maplibre-gl@$terrainMapLibreVersion/dist/$name")
                val connection = url.openConnection().apply {
                    connectTimeout = 15_000
                    readTimeout = 45_000
                }
                val temporary = assetDir.resolve("$name.download")
                try {
                    connection.getInputStream().use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    }
                    check(temporary.length() >= 1024L) { "Downloaded MapLibre asset is empty: $name" }
                    check(temporary.renameTo(target) || temporary.copyTo(target, overwrite = true).let { temporary.delete(); true }) {
                        "Cannot install MapLibre asset: $name"
                    }
                } finally {
                    temporary.delete()
                }
            }
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(ensureTerrainMapLibreAssets)
}
android {
    namespace = "com.tubu.trailkit"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.tubu.trailkit"
        minSdk = 26
        targetSdk = 35
        versionCode = 26
        versionName = "1.6.1"
    }
    signingConfigs {
        create("stable") {
            storeFile = file(System.getenv("TRAILKIT_KEYSTORE_PATH") ?: "trailkit-release.jks")
            storePassword = System.getenv("TRAILKIT_STORE_PASSWORD")
            keyAlias = "trailkit"
            keyPassword = System.getenv("TRAILKIT_STORE_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stable")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures { compose = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.maplibre.gl:android-sdk:12.3.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
