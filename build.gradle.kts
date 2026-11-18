import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins { base; alias(libs.plugins.spotless) apply false; alias(libs.plugins.spring.boot) apply false }

allprojects { group = "io.github.gbalazsfalvi.slotkeeper"; version = "0.1.0"; repositories { mavenCentral() } }
val junitBom = libs.junit.bom
val junitJupiter = libs.junit.jupiter
val junitLauncher = libs.junit.launcher
val formatter = libs.versions.googleJavaFormat.get()
val coverage = libs.versions.jacoco.get()
subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "jacoco")
    extensions.configure<JavaPluginExtension> { toolchain.languageVersion.set(JavaLanguageVersion.of(21)); withSourcesJar(); withJavadocJar() }
    extensions.configure<SpotlessExtension> { java { googleJavaFormat(formatter); removeUnusedImports(); trimTrailingWhitespace(); endWithNewline() } }
    extensions.configure<JacocoPluginExtension> { toolVersion = coverage }
    dependencies { add("testImplementation", platform(junitBom)); add("testImplementation", junitJupiter); add("testRuntimeOnly", junitLauncher) }
    dependencyLocking { lockAllConfigurations() }
    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.release.set(21); options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters")) }
    tasks.withType<Javadoc>().configureEach { options.encoding = "UTF-8"; (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all", true); (options as StandardJavadocDocletOptions).addBooleanOption("Werror", true); isFailOnError = true }
    tasks.withType<Test>().configureEach { useJUnitPlatform(); maxParallelForks = 1; maxHeapSize = "512m"; testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
    tasks.withType<JacocoReport>().configureEach { dependsOn(tasks.named("test")); reports { xml.required.set(true); html.required.set(true) } }
    tasks.named("check") { dependsOn("spotlessCheck", "javadoc", "jacocoTestReport") }
    tasks.register("resolveDependencies") { notCompatibleWithConfigurationCache("Explicit dependency inventory resolves configurations at execution."); doLast { configurations.filter { it.isCanBeResolved }.sortedBy { it.name }.forEach { it.resolve() } } }
}
val npm = if (System.getProperty("os.name").startsWith("Windows")) listOf("cmd.exe", "/d", "/c", "npm.cmd") else listOf("npm")
val webInstall by tasks.registering(Exec::class) { workingDir("web"); commandLine(npm + listOf("ci", "--no-audit", "--no-fund")); inputs.files("web/package.json", "web/package-lock.json"); outputs.dir("web/node_modules") }
val webBuild by tasks.registering(Exec::class) { dependsOn(webInstall); workingDir("web"); commandLine(npm + listOf("run", "build")); inputs.files(fileTree("web") { include("src/**", "public/**", "e2e/**", "index.html", "*.json", "*.ts"); exclude("node_modules/**", "dist/**") }); outputs.dir("web/dist") }
val webTest by tasks.registering(Exec::class) { dependsOn(webInstall); workingDir("web"); commandLine(npm + listOf("test")) }
tasks.named("check") { dependsOn(subprojects.map { "${it.path}:check" }, webTest) }
tasks.named("assemble") { dependsOn(subprojects.map { "${it.path}:assemble" }) }
tasks.register("resolveDependencies") { dependsOn(subprojects.map { "${it.path}:resolveDependencies" }) }
tasks.register("verify") { group = "verification"; dependsOn("check", ":application:integrationTest") }
