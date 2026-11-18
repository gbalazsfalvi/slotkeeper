plugins { alias(libs.plugins.spring.boot) }

description = "Hardened reservation API, PostgreSQL adapters, sessions and packaged product UI."

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.spring.boot.bom))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-session-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation(libs.bouncycastle)
    runtimeOnly("org.postgresql:postgresql")
    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation(libs.archunit)
}

springBoot { mainClass.set("io.github.gbalazsfalvi.slotkeeper.SlotkeeperApplication") }

tasks.named<Test>("test") { useJUnitPlatform { excludeTags("docker") } }
val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Exercises actual PostgreSQL, real HTTP, and independent process contention."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("docker") }
    shouldRunAfter(tasks.named("test"))
    maxHeapSize = "1024m"
}

tasks.named<ProcessResources>("processResources") {
    if (!providers.gradleProperty("externalWebBuild").isPresent) dependsOn(rootProject.tasks.named("webBuild"))
    from(rootProject.layout.projectDirectory.dir("web/dist")) { into("static") }
}
