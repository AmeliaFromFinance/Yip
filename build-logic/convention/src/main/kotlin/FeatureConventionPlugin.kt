import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class FeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("yip.android.library")
        pluginManager.apply("yip.android.compose")
        pluginManager.apply("yip.hilt")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        dependencies {
            add("implementation", project(":core:model"))
            add("implementation", project(":core:common"))
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:data"))
            add("implementation", libs.findLibrary("androidx-hilt-navigation-compose").get())
            add("implementation", libs.findLibrary("androidx-lifecycle-runtime-compose").get())
            add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
            add("implementation", libs.findLibrary("androidx-navigation-compose").get())
            add("implementation", libs.findLibrary("kotlinx-serialization-json").get())
        }
    }
}
