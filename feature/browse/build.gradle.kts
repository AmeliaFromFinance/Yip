plugins {
    alias(libs.plugins.yip.feature)
}

dependencies {
    implementation(project(":core:database"))
    implementation(project(":core:playback"))
    implementation(libs.media3.ui.compose)
}
