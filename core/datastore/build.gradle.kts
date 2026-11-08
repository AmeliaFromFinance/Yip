plugins {
    alias(libs.plugins.yip.android.library)
    alias(libs.plugins.yip.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.serialization.json)
}
