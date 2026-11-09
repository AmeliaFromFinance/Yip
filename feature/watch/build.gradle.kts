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
    debugImplementation(libs.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", providers.gradleProperty("robolectric.repo").getOrElse("https://repo1.maven.org/maven2"))
    maxHeapSize = "2g"
}
