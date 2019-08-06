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
import static org.gradle.testkit.runner.TaskOutcome.SKIPPED
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

class RelocationTest extends Specification {

    static final String GRADLE_INSTALLATION_PROPERTY = "org.gradle.kotlin.test.gradle-installation"
    static final String KOTLIN_VERSION_PROPERTY = "org.gradle.kotlin.test.kotlin-version"
    static final String SCAN_URL_PROPERTY = "org.gradle.kotlin.test.scan-url"
    static final String PLUGIN_MIRROR_PROPERTY = "org.gradle.internal.plugins.portal.url.override"
    static final String SMOKE_TEST_INIT_SCRIPT_PROPERTY = "org.gradle.smoketests.mirror.init.script"

    static final String DEFAULT_GRADLE_VERSION = "5.4.1"
    static final String DEFAULT_KOTLIN_VERSION = "1.3.30"

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

        builder.put(":spek-gradle-plugin:kaptGenerateStubsKotlin", FROM_CACHE)
        builder.put(":spek-gradle-plugin:kaptKotlin", FROM_CACHE)
        builder.put(":spek-gradle-plugin:compileKotlin", FROM_CACHE)
        builder.put(":spek-gradle-plugin:compileJava", NO_SOURCE)
        builder.put(":spek-gradle-plugin:pluginDescriptors", SUCCESS)
        builder.put(":spek-gradle-plugin:processResources", SUCCESS)
        builder.put(":spek-gradle-plugin:classes", SUCCESS)
        builder.put(":spek-gradle-plugin:inspectClassesForKotlinIC", SUCCESS)
        builder.put(":spek-gradle-plugin:jar", SUCCESS)
        builder.put(":spek-dsl:compileKotlinLinux", SUCCESS)
        builder.put(":spek-kotlin-compiler-plugin-native:kaptGenerateStubsKotlin", FROM_CACHE)
        builder.put(":spek-kotlin-compiler-plugin-jvm:kaptGenerateStubsKotlin", NO_SOURCE)
        builder.put(":integration-test:jvmProcessResources", NO_SOURCE)
        builder.put(":spek-runtime:jvmProcessResources", NO_SOURCE)
        builder.put(":spek-ide-plugin-intellij-base:patchPluginXml", NO_SOURCE)
        builder.put(":spek-ide-plugin-interop-jvm:processResources", NO_SOURCE)
        builder.put(":spek-ide-plugin-intellij-base-jvm:patchPluginXml", NO_SOURCE)
        builder.put(":spek-ide-plugin-android-studio:patchPluginXml", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-idea:patchPluginXml", SUCCESS)
        builder.put(":spek-runner-junit5:processResources", SUCCESS)
        builder.put(":integration-test:linuxProcessResources", NO_SOURCE)
        builder.put(":spek-ide-plugin-intellij-base:processResources", NO_SOURCE)
        builder.put(":spek-runtime:compileKotlinWindows", SKIPPED)
        builder.put(":spek-runtime:linuxProcessResources", NO_SOURCE)
        builder.put(":spek-runtime:macosProcessResources", NO_SOURCE)
        builder.put(":integration-test:macosProcessResources", NO_SOURCE)
        builder.put(":spek-kotlin-compiler-plugin-jvm:kaptKotlin", FROM_CACHE)
        builder.put(":spek-ide-plugin-intellij-base-jvm:processResources", NO_SOURCE)
        builder.put(":spek-runtime:windowsProcessResources", NO_SOURCE)
        builder.put(":integration-test:windowsProcessResources", NO_SOURCE)
        builder.put(":spek-runtime:windowsMainKlibrary", UP_TO_DATE)
        builder.put(":spek-runtime:linkTestDebugExecutableWindows", SKIPPED)
        builder.put(":spek-runtime:metadataSourcesJar", SUCCESS)
        builder.put(":spek-kotlin-compiler-plugin-native:kaptKotlin", FROM_CACHE)
        builder.put(":spek-kotlin-compiler-plugin-native:compileKotlin", FROM_CACHE)
        builder.put(":spek-kotlin-compiler-plugin-native:compileJava", NO_SOURCE)
        builder.put(":spek-kotlin-compiler-plugin-native:processResources", NO_SOURCE)
        builder.put(":spek-kotlin-compiler-plugin-native:classes", UP_TO_DATE)
        builder.put(":spek-kotlin-compiler-plugin-native:inspectClassesForKotlinIC", SUCCESS)
        builder.put(":spek-kotlin-compiler-plugin-native:jar", SUCCESS)
        builder.put(":spek-kotlin-compiler-plugin-jvm:compileKotlin", NO_SOURCE)
        builder.put(":spek-kotlin-compiler-plugin-jvm:compileJava", NO_SOURCE)
        builder.put(":spek-kotlin-compiler-plugin-jvm:processResources", NO_SOURCE)
        builder.put(":spek-kotlin-compiler-plugin-jvm:classes", UP_TO_DATE)
        builder.put(":spek-kotlin-compiler-plugin-jvm:inspectClassesForKotlinIC", SUCCESS)
        builder.put(":spek-kotlin-compiler-plugin-jvm:jar", SUCCESS)
        builder.put(":spek-ide-plugin-android-studio:processResources", SUCCESS)
        builder.put(":integration-test:compileKotlinLinux", SUCCESS)
        builder.put(":spek-kotlin-compiler-plugin-native:assemble", SUCCESS)
        builder.put(":spek-kotlin-compiler-plugin-jvm:assemble", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-idea:processResources", SUCCESS)
        builder.put(":integration-test:compileKotlinMacos", SKIPPED)
        builder.put(":spek-runtime:compileKotlinLinux", SUCCESS)
        builder.put(":spek-dsl:compileKotlinMacos", SKIPPED)
        builder.put(":integration-test:compileKotlinWindows", SKIPPED)
        builder.put(":integration-test:compileKotlinJvm", NO_SOURCE)
        builder.put(":integration-test:jvmMainClasses", UP_TO_DATE)
        builder.put(":integration-test:jvmJar", SUCCESS)
        builder.put(":integration-test:linuxMainKlibrary", SUCCESS)
        builder.put(":integration-test:macosMainKlibrary", UP_TO_DATE)
        builder.put(":integration-test:windowsMainKlibrary", UP_TO_DATE)
        builder.put(":integration-test:linkTestDebugExecutableWindows", SKIPPED)
        builder.put(":integration-test:compileKotlinMetadata", NO_SOURCE)
        builder.put(":integration-test:metadataMainClasses", UP_TO_DATE)
        builder.put(":integration-test:metadataJar", SUCCESS)
        builder.put(":spek-dsl:compileKotlinWindows", SKIPPED)
        builder.put(":spek-dsl:compileKotlinJs", FROM_CACHE)
        builder.put(":spek-dsl:jsProcessResources", NO_SOURCE)
        builder.put(":spek-dsl:jsMainClasses", UP_TO_DATE)
        builder.put(":spek-dsl:jsJar", SUCCESS)
        builder.put(":spek-dsl:compileKotlinJvm", FROM_CACHE)
        builder.put(":spek-dsl:jvmProcessResources", NO_SOURCE)
        builder.put(":spek-dsl:jvmMainClasses", UP_TO_DATE)
        builder.put(":spek-dsl:jvmJar", SUCCESS)
        builder.put(":spek-dsl:linuxProcessResources", NO_SOURCE)
        builder.put(":spek-dsl:linuxMainKlibrary", SUCCESS)
        builder.put(":spek-dsl:linkTestDebugExecutableLinux", NO_SOURCE)
        builder.put(":spek-dsl:macosProcessResources", NO_SOURCE)
        builder.put(":spek-dsl:macosMainKlibrary", UP_TO_DATE)
        builder.put(":spek-dsl:linkTestDebugExecutableMacos", SKIPPED)
        builder.put(":spek-dsl:windowsProcessResources", NO_SOURCE)
        builder.put(":spek-dsl:windowsMainKlibrary", UP_TO_DATE)
        builder.put(":spek-dsl:linkTestDebugExecutableWindows", SKIPPED)
        builder.put(":spek-dsl:compileKotlinMetadata", FROM_CACHE)
        builder.put(":spek-dsl:metadataMainClasses", UP_TO_DATE)
        builder.put(":spek-dsl:metadataJar", SUCCESS)
        builder.put(":spek-dsl:metadataSourcesJar", SUCCESS)
        builder.put(":spek-dsl:assemble", SUCCESS)
        builder.put(":integration-test:linkTestDebugExecutableLinux", SUCCESS)
        builder.put(":spek-runtime:compileKotlinMacos", SKIPPED)
        builder.put(":spek-runtime:compileKotlinJvm", FROM_CACHE)
        builder.put(":spek-runtime:jvmMainClasses", UP_TO_DATE)
        builder.put(":spek-runtime:jvmJar", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-base:compileKotlin", FROM_CACHE)
        builder.put(":spek-ide-plugin-interop-jvm:compileKotlin", FROM_CACHE)
        builder.put(":spek-runner-junit5:compileKotlin", FROM_CACHE)
        builder.put(":spek-runtime:linuxMainKlibrary", SUCCESS)
        builder.put(":spek-runtime:linkTestDebugExecutableLinux", NO_SOURCE)
        builder.put(":spek-runtime:macosMainKlibrary", UP_TO_DATE)
        builder.put(":spek-runtime:linkTestDebugExecutableMacos", SKIPPED)
        builder.put(":spek-runtime:compileKotlinMetadata", FROM_CACHE)
        builder.put(":spek-ide-plugin-interop-jvm:compileJava", NO_SOURCE)
        builder.put(":spek-ide-plugin-interop-jvm:classes", UP_TO_DATE)
        builder.put(":spek-ide-plugin-interop-jvm:shadowJar", SUCCESS)
        builder.put(":spek-runner-junit5:compileJava", NO_SOURCE)
        builder.put(":spek-runner-junit5:classes", SUCCESS)
        builder.put(":spek-runner-junit5:inspectClassesForKotlinIC", SUCCESS)
        builder.put(":spek-runner-junit5:jar", SUCCESS)
        builder.put(":spek-runtime:metadataMainClasses", UP_TO_DATE)
        builder.put(":spek-runtime:metadataJar", SUCCESS)
        builder.put(":spek-runtime:assemble", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-base:compileJava", NO_SOURCE)
        builder.put(":spek-runner-junit5:assemble", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-base:classes", UP_TO_DATE)
        builder.put(":spek-ide-plugin-intellij-base:inspectClassesForKotlinIC", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-base:instrumentCode", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-base:postInstrumentCode", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-base:jar", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-base:prepareSandbox", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-base:buildSearchableOptions", SKIPPED)
        builder.put(":spek-ide-plugin-intellij-base:jarSearchableOptions", SKIPPED)
        builder.put(":spek-ide-plugin-intellij-base:buildPlugin", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-base:assemble", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-base-jvm:compileKotlin", FROM_CACHE)
        builder.put(":spek-ide-plugin-interop-jvm:inspectClassesForKotlinIC", SUCCESS)
        builder.put(":spek-ide-plugin-interop-jvm:jar", SUCCESS)
        builder.put(":spek-ide-plugin-interop-jvm:assemble", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-base-jvm:compileJava", NO_SOURCE)
        builder.put(":spek-ide-plugin-intellij-base-jvm:classes", UP_TO_DATE)
        builder.put(":spek-ide-plugin-intellij-base-jvm:inspectClassesForKotlinIC", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-base-jvm:instrumentCode", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-base-jvm:postInstrumentCode", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-base-jvm:jar", SUCCESS)
        builder.put(":spek-ide-plugin-android-studio:compileKotlin", FROM_CACHE)
        builder.put(":spek-ide-plugin-intellij-base-jvm:prepareSandbox", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-idea:compileKotlin", FROM_CACHE)
        builder.put(":spek-ide-plugin-intellij-idea:compileJava", NO_SOURCE)
        builder.put(":spek-ide-plugin-android-studio:compileJava", NO_SOURCE)
        builder.put(":spek-ide-plugin-intellij-idea:classes", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-idea:inspectClassesForKotlinIC", SUCCESS)
        builder.put(":spek-ide-plugin-android-studio:classes", SUCCESS)
        builder.put(":spek-ide-plugin-android-studio:inspectClassesForKotlinIC", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-idea:instrumentCode", SUCCESS)
        builder.put(":spek-ide-plugin-android-studio:instrumentCode", SUCCESS)
        builder.put(":spek-ide-plugin-android-studio:postInstrumentCode", SUCCESS)
        builder.put(":spek-ide-plugin-android-studio:jar", SUCCESS)
        builder.put(":spek-ide-plugin-android-studio:prepareSandbox", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-idea:postInstrumentCode", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-idea:jar", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-idea:prepareSandbox", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-base-jvm:buildSearchableOptions", SKIPPED)
        builder.put(":spek-ide-plugin-intellij-base-jvm:jarSearchableOptions", SKIPPED)
        builder.put(":spek-ide-plugin-intellij-base-jvm:buildPlugin", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-base-jvm:assemble", SUCCESS)
        builder.put(":spek-ide-plugin-android-studio:buildSearchableOptions", SKIPPED)
        builder.put(":spek-ide-plugin-android-studio:jarSearchableOptions", SKIPPED)
        builder.put(":spek-ide-plugin-android-studio:buildPlugin", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-idea:buildSearchableOptions", SUCCESS)
        builder.put(":spek-ide-plugin-android-studio:assemble", SUCCESS)
        builder.put(":integration-test:linkTestDebugExecutableMacos", SKIPPED)
        builder.put(":spek-ide-plugin-intellij-idea:jarSearchableOptions", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-idea:buildPlugin", SUCCESS)
        builder.put(":spek-ide-plugin-intellij-idea:assemble", SUCCESS)
        builder.put(":integration-test:assemble", SUCCESS)

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
