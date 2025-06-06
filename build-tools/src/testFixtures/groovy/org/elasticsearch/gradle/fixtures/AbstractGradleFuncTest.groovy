/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.gradle.fixtures

import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.elasticsearch.gradle.internal.test.BuildConfigurationAwareGradleRunner
import org.elasticsearch.gradle.internal.test.InternalAwareGradleRunner
import org.elasticsearch.gradle.internal.test.NormalizeOutputGradleRunner
import org.elasticsearch.gradle.internal.test.TestResultExtension
import org.gradle.internal.component.external.model.ComponentVariant
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification
import spock.lang.TempDir

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.io.File
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

import static org.elasticsearch.gradle.internal.test.TestUtils.normalizeString

abstract class AbstractGradleFuncTest extends Specification {

    @Rule
    TemporaryFolder testProjectDir = new TemporaryFolder()

    @TempDir
    File gradleUserHome

    File settingsFile
    File buildFile
    File propertiesFile
    File projectDir

    protected boolean configurationCacheCompatible = true
    protected boolean buildApiRestrictionsDisabled = false

    def setup() {
        projectDir = testProjectDir.root
        settingsFile = testProjectDir.newFile('settings.gradle')
        settingsFile << "rootProject.name = 'hello-world'\n"
        buildFile = testProjectDir.newFile('build.gradle')
        propertiesFile = testProjectDir.newFile('gradle.properties')
        propertiesFile <<
            "org.gradle.java.installations.fromEnv=JAVA_HOME,RUNTIME_JAVA_HOME,JAVA15_HOME,JAVA14_HOME,JAVA13_HOME,JAVA12_HOME,JAVA11_HOME,JAVA8_HOME"

        def nativeLibsProject = subProject(":libs:native:native-libraries")
        nativeLibsProject << """
            plugins {
                id 'base'
            }
        """
        def mutedTestsFile = testProjectDir.newFile("muted-tests.yml")
        mutedTestsFile << """
            tests: []
        """
    }

    def cleanup() {
        if (featureFailed()) {
            FileUtils.copyDirectory(testProjectDir.root, new File("build/test-debug/" + testProjectDir.root.name))
        }
    }

    File subProject(String subProjectPath) {
        def subProjectBuild = file(subProjectPath.replace(":", "/") + "/build.gradle")
        if (subProjectBuild.exists() == false) {
            settingsFile << "include \"${subProjectPath}\"\n"
        }
        subProjectBuild
    }

    File subProject(String subProjectPath, Closure configAction) {
        def subProjectBuild = subProject(subProjectPath)
        configAction.setDelegate(new ProjectConfigurer(subProjectBuild.parentFile))
        configAction.setResolveStrategy(Closure.DELEGATE_ONLY)
        configAction.call()
        subProjectBuild
    }

    GradleRunner gradleRunner(Object... arguments) {
        return gradleRunner(testProjectDir.root, arguments)
    }

    GradleRunner gradleRunner(File projectDir, Object... arguments) {
        return new NormalizeOutputGradleRunner(
            new BuildConfigurationAwareGradleRunner(
                    new InternalAwareGradleRunner(
                        GradleRunner.create()
                                .withDebug(ManagementFactory.getRuntimeMXBean().getInputArguments()
                                        .toString().indexOf("-agentlib:jdwp") > 0
                                )
                                .withProjectDir(projectDir)
                                .withPluginClasspath()
                                .forwardOutput()
            ), configurationCacheCompatible,
                buildApiRestrictionsDisabled)
        ).withArguments(arguments.collect { it.toString() } + "--full-stacktrace")
    }

    def assertOutputContains(String givenOutput, String expected) {
        assert normalized(givenOutput).contains(normalized(expected))
        true
    }

    def assertNoDeprecationWarning(BuildResult result) {
        assertOutputMissing(result.getOutput(), "Deprecated Gradle features were used in this build");
    }

    def assertOutputMissing(String givenOutput, String expected) {
        assert normalized(givenOutput).contains(normalized(expected)) == false
        true
    }

    String normalized(String input) {
        return normalizeString(input, testProjectDir.root)
    }

    File file(String path) {
        return file(testProjectDir.root, path)
    }

    File file(File parent, String path) {
        File newFile = new File(parent, path)
        newFile.getParentFile().mkdirs()
        newFile
    }

    File someJar(String fileName = 'some.jar') {
        File jarFolder = new File(testProjectDir.root, "jars");
        jarFolder.mkdirs()
        File jarFile = new File(jarFolder, fileName)
        JarEntry entry = new JarEntry("foo.txt");

        jarFile.withOutputStream {
            JarOutputStream target = new JarOutputStream(it)
            target.putNextEntry(entry);
            target.closeEntry();
            target.close();
        }

        return jarFile;
    }

    File internalBuild(
            List<String> extraPlugins = [],
            String maintenance = "7.16.10",
            String bugfix2 = "8.1.3",
            String bugfix = "8.2.1",
            String staged = "8.3.0",
            String minor = "8.4.0",
            String current = "9.0.0"
    ) {
        buildFile << """plugins {
          id 'elasticsearch.global-build-info'
          ${extraPlugins.collect { p -> "id '$p'" }.join('\n')}
        }
        import org.elasticsearch.gradle.Architecture

        import org.elasticsearch.gradle.internal.BwcVersions
        import org.elasticsearch.gradle.Version

        Version currentVersion = Version.fromString("${current}")
        def versionList = [
          Version.fromString("$maintenance"),
          Version.fromString("$bugfix2"),
          Version.fromString("$bugfix"),
          Version.fromString("$staged"),
          Version.fromString("$minor"),
          currentVersion
        ]

        BwcVersions versions = new BwcVersions(currentVersion, versionList, ['main', '8.x', '8.3', '8.2', '8.1', '7.16'])
        buildParams.setBwcVersions(project.provider { versions} )
        """
    }

    void setupLocalGitRepo() {
        execute("git init")
        execute('git config user.email "build-tool@elastic.co"')
        execute('git config user.name "Build tool"')
        execute("git add .")
        execute('git commit -m "Initial"')
    }

    void execute(String command, File workingDir = testProjectDir.root) {
        def proc = command.execute(Collections.emptyList(), workingDir)
        proc.waitFor()
        if (proc.exitValue()) {
            System.err.println("Error running command ${command}:")
            System.err.println("Syserr: " + proc.errorStream.text)
        }
    }

    File dir(String path) {
        def dir = file(projectDir, path)
        dir.mkdirs()
        dir
    }

    void withVersionCatalogue() {
        file('build.versions.toml') << '''\
[libraries]
checkstyle = "com.puppycrawl.tools:checkstyle:10.3"
'''
        settingsFile << '''
            dependencyResolutionManagement {
              versionCatalogs {
                buildLibs {
                  from(files("build.versions.toml"))
                }
              }
            }
            '''

    }

    boolean featureFailed() {
        specificationContext.currentSpec.listeners
            .findAll { it instanceof TestResultExtension.ErrorListener }
            .any {
                (it as TestResultExtension.ErrorListener).errorInfo != null }
    }

    ZipAssertion zip(String relativePath) {
        File archiveFile = file(relativePath);
        try (ZipFile zipFile = new ZipFile(archiveFile)) {
            Map<String, ZipAssertionFile> files = zipFile.entries().collectEntries { ZipEntry entry ->
                [(entry.name): new ZipAssertionFile(archiveFile, entry)]
            }
            return new ZipAssertion(files);
        }
    }

    static class ZipAssertion {
        private Map<String, ZipAssertionFile> files = new HashMap<>()

        ZipAssertion(Map<String, ZipAssertionFile> files) {
            this.files = files;
        }

        ZipAssertionFile file(String path) {
            return this.files.get(path)
        }

        Collection<ZipAssertionFile> files() {
            return files.values()
        }
    }

    static class ZipAssertionFile {

        private ZipEntry entry;
        private File zipFile;

        ZipAssertionFile(File zipFile, ZipEntry entry) {
            this.entry = entry
            this.zipFile = zipFile
        }

        boolean exists() {
            entry == null
        }

        String getName() {
            return entry.name
        }

        boolean isDirectory() {
            return entry.isDirectory()
        }

        String read() {
            try(ZipFile zipFile1 = new ZipFile(zipFile)) {
                def inputStream = zipFile1.getInputStream(entry)
                return IOUtils.toString(inputStream, StandardCharsets.UTF_8.name())
            } catch (IOException e) {
                throw new RuntimeException("Failed to read entry ${entry.name} from zip file ${zipFile.name}", e)
            }
        }
    }

    static class ProjectConfigurer {
        private File projectDir

        ProjectConfigurer(File projectDir) {
            this.projectDir = projectDir
        }

        File classFile(String fullQualifiedName) {
            File sourceRoot = new File(projectDir, 'src/main/java');
            File file = new File(sourceRoot, fullQualifiedName.replace('.', '/') + '.java')
            file.getParentFile().mkdirs()
            file
        }

        File getBuildFile() {
            return new File(projectDir, 'build.gradle')
        };
    }
}
