description = "Framework-free reservation state, policies, and transaction ports."

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"))
    violationRules { rule {
        limit { counter = "LINE"; minimum = "0.90".toBigDecimal() }
        limit { counter = "BRANCH"; minimum = "0.85".toBigDecimal() }
    } }
}
tasks.named("check") { dependsOn("jacocoTestCoverageVerification") }
