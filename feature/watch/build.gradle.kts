plugins {
    alias(libs.plugins.yip.feature)
}

dependencies {
    implementation(project(":core:playback"))
    implementation(project(":core:database"))
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.ui)
    implementation(libs.androidx.activity.compose)
}
