import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

group = "io.github.fpaschos"
version = libs.versions.pipekt.version
    .get()

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
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

    configure<KtlintExtension> {
        version.set(ktlintRules)
        verbose.set(true)
        outputToConsole.set(true)
        coloredOutput.set(true)
        reporters { reporter(ReporterType.HTML) }
    }

    tasks.withType<Test> {
        useJUnitPlatform()

        testLogging { events = setOf(TestLogEvent.PASSED, TestLogEvent.FAILED, TestLogEvent.SKIPPED) }

        addTestListener(object : TestListener {
            override fun beforeSuite(suite: TestDescriptor) {}

            override fun beforeTest(testDescriptor: TestDescriptor) {}

            override fun afterTest(
                testDescriptor: TestDescriptor,
                result: TestResult,
            ) {
            }

            override fun afterSuite(
                suite: TestDescriptor,
                result: TestResult,
            ) {
                if (suite.parent == null) {
                    println("\nTest result: ${result.resultType}")
                    val summary = "Test summary: ${result.testCount} tests, " +
                        "${result.successfulTestCount} succeeded, " +
                        "${result.failedTestCount} failed, " +
                        "${result.skippedTestCount} skipped"
                    println(summary)
                }
            }
        })
    }
}
