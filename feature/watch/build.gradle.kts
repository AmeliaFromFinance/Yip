plugins {
    alias(libs.plugins.yip.feature)
}

dependencies {
    implementation(project(":core:playback"))
    implementation(project(":core:database"))
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.ui)
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", providers.gradleProperty("robolectric.repo").getOrElse("https://repo1.maven.org/maven2"))
    systemProperty("roborazzi.test.record", providers.gradleProperty("roborazzi.test.record").getOrElse("false"))
    maxHeapSize = "2g"
}
