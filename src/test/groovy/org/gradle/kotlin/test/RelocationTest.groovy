package org.gradle.kotlin.test

import com.google.common.collect.ImmutableMap
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

import static org.gradle.testkit.runner.TaskOutcome.FROM_CACHE

class RelocationTest extends Specification {

    static final String GRADLE_INSTALLATION_PROPERTY = "org.gradle.kotlin.test.gradle-installation"
    static final String KOTLIN_VERSION_PROPERTY = "org.gradle.kotlin.test.kotlin-version"
    static final String SCAN_URL_PROPERTY = "org.gradle.kotlin.test.scan-url"

    static final String DEFAULT_GRADLE_VERSION = "4.4"
    static final String DEFAULT_KOTLIN_VERSION = "1.2.20-dev-526"

    @Rule TemporaryFolder temporaryFolder
    File cacheDir
    String kotlinPluginVersion
    String scanUrl

    def setup() {
        cacheDir = temporaryFolder.newFolder()

        kotlinPluginVersion = System.getProperty(KOTLIN_VERSION_PROPERTY)
        if (!kotlinPluginVersion) {
            kotlinPluginVersion = DEFAULT_KOTLIN_VERSION
        }

        scanUrl = System.getProperty(SCAN_URL_PROPERTY)
    }

    def "spek can be built relocatably"() {
        def tasksToRun = ["assemble"]

        println "> Using Kotlin plugin ${kotlinPluginVersion}"
        println "> Cache directory: $cacheDir (files: ${cacheDir.listFiles().length})"

        def originalDir = new File(System.getProperty("original.dir"))
        def relocatedDir = new File(System.getProperty("relocated.dir"))

        def expectedResults = expectedResults()

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
                        maven {
                            url "https://dl.bintray.com/kotlin/kotlin-dev"
                        }
                    }
                    dependencies {
                        classpath ('org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinPluginVersion') { force = true }
                    }
                }

                $scanPluginConfiguration
            }

            settingsEvaluated { settings ->
                settings.buildCache {
                    local(DirectoryBuildCache) {
                        directory = "${cacheDir.toURI()}"
                    }
                }
            }
        """

        def defaultArgs = [
            "--build-cache",
            "--scan",
            "--init-script", initScript.absolutePath,
        ]

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
        // builder.put(':common:assembleDebug', SUCCESS)
        return new ExpectedResults(builder.build())
    }

    GradleRunner createGradleRunner() {
        def gradleRunner = GradleRunner.create()

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
