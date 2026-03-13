plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()

    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }

    sourceSets {
        all {
            languageSettings {
                enableLanguageFeature("ContextParameters")
            }
        }
        configureEach {
            languageSettings.progressiveMode = true
        }
        commonMain.dependencies {
            implementation(libs.bundles.arrow)
            implementation(libs.bundles.kotlinx.serialization)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotest.framework.engine)
            implementation(libs.kotest.assertions.core)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
            implementation(libs.kotest.assertions.arrow)
        }
    }
}
