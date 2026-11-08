plugins {
    alias(libs.plugins.yip.android.library)
    alias(libs.plugins.yip.hilt)
    alias(libs.plugins.room)
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation(project(":core:model"))
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}
