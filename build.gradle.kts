import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

group = "io.github.fpaschos"
version = libs.versions.pipekt.version
    .get()

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.ksp) apply false
}

repositories {
    mavenCentral()
}

val ktlintRules = libs.versions.ktlint.rules
val ktlintPlugin = libs.plugins.ktlint
    .get()
    .pluginId

subprojects {
    version = rootProject.version

    repositories {
        mavenCentral()
    }

    apply(plugin = ktlintPlugin)

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            showStandardStreams = true
            events("passed", "failed", "skipped")
        }
    }

    configure<KtlintExtension> {
        version.set(ktlintRules)
        verbose.set(true)
        outputToConsole.set(true)
        coloredOutput.set(true)
        reporters { reporter(ReporterType.HTML) }
        filter {
            exclude { it.file.path.contains("/build/generated/") }
        }
    }
}
