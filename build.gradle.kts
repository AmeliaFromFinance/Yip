plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint().editorConfigOverride(
            mapOf(
                "ktlint_standard_function-naming" to "disabled",
                "ktlint_standard_filename" to "disabled",
                "ktlint_code_style" to "android_studio",
            ),
        )
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint()
    }
}
