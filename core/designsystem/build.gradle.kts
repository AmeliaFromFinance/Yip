plugins {
    alias(libs.plugins.yip.android.library)
    alias(libs.plugins.yip.android.compose)
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    api(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.core.ktx)
}

dependencies {
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Robolectric fetches Android framework jars at test time; allow a mirror (-Probolectric.repo=...).
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", providers.gradleProperty("robolectric.repo").getOrElse("https://repo1.maven.org/maven2"))
    systemProperty("roborazzi.test.record", providers.gradleProperty("roborazzi.test.record").getOrElse("false"))
    maxHeapSize = "2g"
}
