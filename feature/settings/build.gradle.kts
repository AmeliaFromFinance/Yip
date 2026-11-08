plugins {
    alias(libs.plugins.yip.feature)
}

dependencies {
    implementation(project(":core:playback"))
    implementation(project(":core:network"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)
}
