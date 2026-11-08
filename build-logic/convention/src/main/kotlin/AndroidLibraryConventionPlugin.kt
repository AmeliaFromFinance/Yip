import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            configureKotlinAndroid(this)
            defaultConfig.consumerProguardFiles("consumer-rules.pro")
            val path = target.path.removePrefix(":").replace(':', '.').replace('-', '_')
            namespace = "lol.sinist3r.yip.$path"
        }
        dependencies {
            add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())
            add("implementation", libs.findLibrary("timber").get())
            add("testImplementation", libs.findLibrary("junit4").get())
            add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
            add("testImplementation", libs.findLibrary("turbine").get())
            add("testImplementation", libs.findLibrary("mockk").get())
        }
    }
}
