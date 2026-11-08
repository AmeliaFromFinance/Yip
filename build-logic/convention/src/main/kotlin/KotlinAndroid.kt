import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String =
    findVersion(alias).get().requiredVersion

internal fun Project.configureKotlinAndroid(common: CommonExtension) {
    common.apply {
        compileSdk = libs.version("compileSdk").toInt()
        defaultConfig.minSdk = libs.version("minSdk").toInt()
        compileOptions.sourceCompatibility = JavaVersion.VERSION_21
        compileOptions.targetCompatibility = JavaVersion.VERSION_21
        testOptions.unitTests.isIncludeAndroidResources = true
    }
    extensions.configure<KotlinAndroidProjectExtension> {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            allWarningsAsErrors.set(false)
            freeCompilerArgs.addAll(
                "-Xjsr305=strict",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-opt-in=kotlinx.coroutines.FlowPreview",
                "-Xconsistent-data-class-copy-visibility",
            )
        }
    }
}
