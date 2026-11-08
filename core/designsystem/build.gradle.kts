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
