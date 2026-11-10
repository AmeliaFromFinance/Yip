plugins {
    `kotlin-dsl`
}

group = "dev.yipchat.buildlogic"

kotlin { jvmToolchain(21) }

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.gradle.plugin)
    compileOnly(libs.ksp.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "yip.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "yip.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "yip.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("hilt") {
            id = "yip.hilt"
            implementationClass = "HiltConventionPlugin"
        }
        register("feature") {
            id = "yip.feature"
            implementationClass = "FeatureConventionPlugin"
        }
    }
}
