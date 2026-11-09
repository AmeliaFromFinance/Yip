import java.util.Properties

plugins {
    alias(libs.plugins.yip.android.library)
    alias(libs.plugins.yip.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    buildFeatures.buildConfig = true
    defaultConfig {
        val props = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        val clientId = props.getProperty("twitch.clientId") ?: System.getenv("YIP_TWITCH_CLIENT_ID") ?: "YOUR_TWITCH_CLIENT_ID"
        val configKey = props.getProperty("yip.configPublicKey") ?: System.getenv("YIP_CONFIG_PUBLIC_KEY") ?: ""
        buildConfigField("String", "TWITCH_CLIENT_ID", "\"$clientId\"")
        buildConfigField("String", "CONFIG_PUBLIC_KEY", "\"$configKey\"")
        buildConfigField("String", "AUTH_REDIRECT_URI", "\"https://yip.sinist3r.lol/auth\"")
        // owner/repo that publishes release APKs. CI fills it from the repository it runs in.
        val updateRepo = props.getProperty("yip.updateRepo") ?: System.getenv("YIP_UPDATE_REPO") ?: ""
        require(updateRepo.isEmpty() || Regex("^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$").matches(updateRepo)) { "yip.updateRepo must look like owner/repo" }
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    api(libs.okhttp)
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle)
    testImplementation(libs.ktor.client.mock)
}
