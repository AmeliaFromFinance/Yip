plugins {
    alias(libs.plugins.yip.android.application)
    alias(libs.plugins.yip.android.compose)
}

android {
    namespace = "lol.sinist3r.yip"
    defaultConfig {
        applicationId = "lol.sinist3r.yip"
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
