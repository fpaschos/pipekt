plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    explicitApi()
    jvm()

    sourceSets {
        all {
            languageSettings {
                enableLanguageFeature("ContextParameters")
            }
        }
        configureEach {
            languageSettings.progressiveMode = true
        }
        jvmMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.rabbitmq.amqp.client)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotest.framework.engine)
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotest.runner.junit5)
        }
    }
}
