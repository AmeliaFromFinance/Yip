plugins {
    alias(libs.plugins.yip.android.library)
    alias(libs.plugins.yip.hilt)
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    api(libs.timber)
}
