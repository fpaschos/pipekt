import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

group   = "io.github.fpaschos"
version = "0.1.0"

repositories {
    mavenCentral()
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

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging { events = setOf(TestLogEvent.PASSED, TestLogEvent.FAILED, TestLogEvent.SKIPPED) }
}
