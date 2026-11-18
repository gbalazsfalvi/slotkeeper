plugins { application }
description = "Independent owned-target rehearsal and sanitized evidence export."
dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation("tools.jackson.core:jackson-databind")
    runtimeOnly("org.postgresql:postgresql")
}
application { mainClass.set("io.github.gbalazsfalvi.slotkeeper.verification.RehearsalMain") }
