package org.gradle.kotlin.test

import com.google.common.collect.ImmutableMap
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

import static org.gradle.testkit.runner.TaskOutcome.FROM_CACHE
import static org.gradle.testkit.runner.TaskOutcome.NO_SOURCE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

class RelocationTest extends Specification {

    static final String GRADLE_INSTALLATION_PROPERTY = "org.gradle.kotlin.test.gradle-installation"
    static final String KOTLIN_VERSION_PROPERTY = "org.gradle.kotlin.test.kotlin-version"
    static final String SCAN_URL_PROPERTY = "org.gradle.kotlin.test.scan-url"
    static final String PLUGIN_MIRROR_PROPERTY = "org.gradle.internal.plugins.portal.url.override"
    static final String SMOKE_TEST_INIT_SCRIPT_PROPERTY = "org.gradle.smoketests.mirror.init.script"

    static final String DEFAULT_GRADLE_VERSION = "4.8.1"
    static final String DEFAULT_KOTLIN_VERSION = "1.2.70-eap-40"

    @Rule TemporaryFolder temporaryFolder
    File cacheDir
    String kotlinPluginVersion
    String scanUrl
    String pluginMirrorUrl
    String smokeTestInitScript

    def setup() {
        cacheDir = temporaryFolder.newFolder()

        kotlinPluginVersion = System.getProperty(KOTLIN_VERSION_PROPERTY)
        if (!kotlinPluginVersion) {
            kotlinPluginVersion = DEFAULT_KOTLIN_VERSION
        }

        scanUrl = System.getProperty(SCAN_URL_PROPERTY)
        pluginMirrorUrl = System.getProperty(PLUGIN_MIRROR_PROPERTY)
        smokeTestInitScript = System.getProperty(SMOKE_TEST_INIT_SCRIPT_PROPERTY)
    }

    def "spek can be built relocatably"() {
        def tasksToRun = ["assemble"]

        println "> Using Kotlin plugin ${kotlinPluginVersion}"
        println "> Cache directory: $cacheDir (files: ${cacheDir.listFiles().length})"

        def originalDir = new File(System.getProperty("original.dir"))
        def relocatedDir = new File(System.getProperty("relocated.dir"))

        def expectedResults = expectedResults()

        def kotlinEapRepositoryConfiguration = """maven { url "https://dl.bintray.com/kotlin/kotlin-eap" }"""

        def scanPluginConfiguration = scanUrl ? """
            plugins.matching({ it.class.name == "com.gradle.scan.plugin.BuildScanPlugin" }).all {
                buildScan {
                    server = "$scanUrl"
                }
            }
        """ : ""

        def initScript = temporaryFolder.newFile("init.gradle") << """
            rootProject { root ->
                buildscript {
                    repositories {
                        $kotlinEapRepositoryConfiguration
                    }
                    dependencies {
                        classpath ('org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinPluginVersion') { force = true }
                    }
                }

                $scanPluginConfiguration

                allprojects {
                    repositories {
                        jcenter()
                        $kotlinEapRepositoryConfiguration
                    }
                }
            }

            settingsEvaluated { settings ->
                settings.buildCache {
                    local(DirectoryBuildCache) {
                        directory = "${cacheDir.toURI()}"
                    }
                }
                settings.pluginManagement {
                    repositories {
                        gradlePluginPortal()
                        $kotlinEapRepositoryConfiguration
                    }
                }
            }
        """

        def defaultArgs = [
            "--build-cache",
            "--scan",
            "--init-script", initScript.absolutePath,
            "--stacktrace",
        ]

        if (smokeTestInitScript) {
            defaultArgs += ['--init-script', smokeTestInitScript]
        }

        if (pluginMirrorUrl) {
            defaultArgs += ["-D${SMOKE_TEST_INIT_SCRIPT_PROPERTY}=${pluginMirrorUrl}".toString()]
        }

        cleanCheckout(originalDir, defaultArgs)
        cleanCheckout(relocatedDir, defaultArgs)

        when:
        createGradleRunner()
            .withProjectDir(originalDir)
            .withArguments(*tasksToRun, *defaultArgs)
            .build()
        then:
        noExceptionThrown()

        when:
        def relocatedResult = createGradleRunner()
            .withProjectDir(relocatedDir)
            .withArguments(*tasksToRun, *defaultArgs)
            .build()
        then:
        expectedResults.verify(relocatedResult)
    }

    private void cleanCheckout(File dir, List<String> defaultArgs) {
        def args = ["clean", *defaultArgs, "--no-build-cache", "--no-scan"]
        createGradleRunner()
            .withProjectDir(dir)
            .withArguments(args)
            .build()
        new File(dir, ".gradle").deleteDir()
    }

    static class ExpectedResults {
        private final Map<String, TaskOutcome> outcomes

        ExpectedResults(Map<String, TaskOutcome> outcomes) {
            this.outcomes = outcomes
        }

        boolean verify(BuildResult result) {
            println "> Expecting ${outcomes.values().count(FROM_CACHE)} tasks out of ${outcomes.size()} to be cached"

            def outcomesWithMatchingTasks = outcomes.findAll { result.task(it.key) }
            def hasMatchingTasks = outcomesWithMatchingTasks.size() == outcomes.size() && outcomesWithMatchingTasks.size() == result.tasks.size()
            if (!hasMatchingTasks) {
                println "> Tasks missing:    " + (outcomes.findAll { !outcomesWithMatchingTasks.keySet().contains(it.key) })
                println "> Tasks in surplus: " + (result.tasks.findAll { !outcomesWithMatchingTasks.keySet().contains(it.path) })
            }

            boolean allOutcomesMatched = true
            outcomesWithMatchingTasks.each { taskName, expectedOutcome ->
                def taskOutcome = result.task(taskName)?.outcome
                if (taskOutcome != expectedOutcome) {
                    println "> Task '$taskName' was $taskOutcome but should have been $expectedOutcome"
                    allOutcomesMatched = false
                }
            }
            return hasMatchingTasks && allOutcomesMatched
        }
    }

    def expectedResults() {
        def builder = ImmutableMap.<String, TaskOutcome>builder()
        builder.put(':distribution:assemble', UP_TO_DATE)
        builder.put(':documentation:assemble', UP_TO_DATE)
        builder.put(':samples:assemble', SUCCESS)
        builder.put(':samples:classes', UP_TO_DATE)
        builder.put(':samples:compileJava', NO_SOURCE)
        builder.put(':samples:compileKotlin', FROM_CACHE)
        builder.put(':samples:jar', SUCCESS)
        builder.put(':samples:processResources', NO_SOURCE)
        builder.put(':spek-dsl:common:assemble', SUCCESS)
        builder.put(':spek-dsl:common:classes', UP_TO_DATE)
        builder.put(':spek-dsl:common:compileJava', NO_SOURCE)
        builder.put(':spek-dsl:common:compileKotlinCommon', FROM_CACHE)
        builder.put(':spek-dsl:common:jar', SUCCESS)
        builder.put(':spek-dsl:common:processResources', NO_SOURCE)
        builder.put(':spek-dsl:jvm:assemble', SUCCESS)
        builder.put(':spek-dsl:jvm:classes', UP_TO_DATE)
        builder.put(':spek-dsl:jvm:compileJava', NO_SOURCE)
        builder.put(':spek-dsl:jvm:compileKotlin', FROM_CACHE)
        builder.put(':spek-dsl:jvm:jar', SUCCESS)
        builder.put(':spek-dsl:jvm:processResources', NO_SOURCE)
        builder.put(':spek-extension:data-driven:common:assemble', SUCCESS)
        builder.put(':spek-extension:data-driven:common:classes', UP_TO_DATE)
        builder.put(':spek-extension:data-driven:common:compileJava', NO_SOURCE)
        builder.put(':spek-extension:data-driven:common:compileKotlinCommon', FROM_CACHE)
        builder.put(':spek-extension:data-driven:common:jar', SUCCESS)
        builder.put(':spek-extension:data-driven:common:processResources', NO_SOURCE)
        builder.put(':spek-extension:data-driven:jvm:assemble', SUCCESS)
        builder.put(':spek-extension:data-driven:jvm:classes', UP_TO_DATE)
        builder.put(':spek-extension:data-driven:jvm:compileJava', NO_SOURCE)
        builder.put(':spek-extension:data-driven:jvm:compileKotlin', FROM_CACHE)
        builder.put(':spek-extension:data-driven:jvm:jar', SUCCESS)
        builder.put(':spek-extension:data-driven:jvm:processResources', NO_SOURCE)
        builder.put(':spek-extension:subject:common:assemble', SUCCESS)
        builder.put(':spek-extension:subject:common:classes', UP_TO_DATE)
        builder.put(':spek-extension:subject:common:compileJava', NO_SOURCE)
        builder.put(':spek-extension:subject:common:compileKotlinCommon', FROM_CACHE)
        builder.put(':spek-extension:subject:common:jar', SUCCESS)
        builder.put(':spek-extension:subject:common:processResources', NO_SOURCE)
        builder.put(':spek-extension:subject:jvm:assemble', SUCCESS)
        builder.put(':spek-extension:subject:jvm:classes', UP_TO_DATE)
        builder.put(':spek-extension:subject:jvm:compileJava', NO_SOURCE)
        builder.put(':spek-extension:subject:jvm:compileKotlin', FROM_CACHE)
        builder.put(':spek-extension:subject:jvm:jar', SUCCESS)
        builder.put(':spek-extension:subject:jvm:processResources', NO_SOURCE)
        builder.put(':spek-runner:junit5:assemble', SUCCESS)
        builder.put(':spek-runner:junit5:classes', SUCCESS)
        builder.put(':spek-runner:junit5:compileJava', NO_SOURCE)
        builder.put(':spek-runner:junit5:compileKotlin', FROM_CACHE)
        builder.put(':spek-runner:junit5:jar', SUCCESS)
        builder.put(':spek-runner:junit5:processResources', SUCCESS)
        builder.put(':spek-runtime:common:assemble', SUCCESS)
        builder.put(':spek-runtime:common:classes', UP_TO_DATE)
        builder.put(':spek-runtime:common:compileJava', NO_SOURCE)
        builder.put(':spek-runtime:common:compileKotlinCommon', FROM_CACHE)
        builder.put(':spek-runtime:common:jar', SUCCESS)
        builder.put(':spek-runtime:common:processResources', NO_SOURCE)
        builder.put(':spek-runtime:jvm:assemble', SUCCESS)
        builder.put(':spek-runtime:jvm:classes', UP_TO_DATE)
        builder.put(':spek-runtime:jvm:compileJava', NO_SOURCE)
        builder.put(':spek-runtime:jvm:compileKotlin', FROM_CACHE)
        builder.put(':spek-runtime:jvm:jar', SUCCESS)
        builder.put(':spek-runtime:jvm:processResources', NO_SOURCE)

        builder.put(':samples:inspectClassesForKotlinIC', SUCCESS)
        builder.put(':spek-dsl:common:inspectClassesForKotlinIC', SUCCESS)
        builder.put(':spek-dsl:jvm:inspectClassesForKotlinIC', SUCCESS)
        builder.put(':spek-runtime:common:inspectClassesForKotlinIC', SUCCESS)
        builder.put(':spek-runtime:jvm:inspectClassesForKotlinIC', SUCCESS)
        builder.put(':spek-runner:junit5:inspectClassesForKotlinIC', SUCCESS)
        builder.put(':spek-extension:data-driven:common:inspectClassesForKotlinIC', SUCCESS)
        builder.put(':spek-extension:data-driven:jvm:inspectClassesForKotlinIC', SUCCESS)
        builder.put(':spek-extension:subject:common:inspectClassesForKotlinIC', SUCCESS)
        builder.put(':spek-extension:subject:jvm:inspectClassesForKotlinIC', SUCCESS)

        return new ExpectedResults(builder.build())
    }

    GradleRunner createGradleRunner() {
        def gradleRunner = GradleRunner.create()

        println "> Running with Kotlin version in $kotlinPluginVersion"

        def gradleInstallation = System.getProperty(GRADLE_INSTALLATION_PROPERTY)
        if (gradleInstallation) {
            gradleRunner.withGradleInstallation(new File(gradleInstallation))
            println "> Running with Gradle installation in $gradleInstallation"
        } else {
            def gradleVersion = DEFAULT_GRADLE_VERSION
            gradleRunner.withGradleVersion(gradleVersion)
            println "> Running with Gradle version $gradleVersion"
        }

        gradleRunner.forwardOutput()

        return gradleRunner
    }
}
