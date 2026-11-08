import java.util.Properties

plugins {
    alias(libs.plugins.yip.android.application)
    alias(libs.plugins.yip.android.compose)
    alias(libs.plugins.yip.hilt)
    alias(libs.plugins.kotlin.serialization)
}

val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "lol.sinist3r.yip"

    defaultConfig {
        applicationId = "lol.sinist3r.yip"
        versionCode = (project.findProperty("yip.versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("yip.versionName") as String?) ?: "0.1.0"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            val path = signingValue("storeFile", "YIP_KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = signingValue("storePassword", "YIP_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "YIP_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "YIP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildFeatures.buildConfig = true

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/*.kotlin_module")
    }

    dependenciesInfo {
        // Not uploaded to Play; keep the APK free of the encrypted dependency blob.
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:playback"))
    implementation(project(":feature:browse"))
    implementation(project(":feature:watch"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.coil.network.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

}
