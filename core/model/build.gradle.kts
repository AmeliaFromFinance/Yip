plugins {
    alias(libs.plugins.yip.android.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.collections.immutable)
}
