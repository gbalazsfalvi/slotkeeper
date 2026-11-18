# Cold-cache dependency metadata verification

On 18 November 2026 the first GitHub Actions cold build rejected six previously unrecorded plugin-resolution metadata files. Existing Windows checks used a warm Gradle cache. No application behavior or dependency version changed. Strict verification rejected the missing metadata as intended.

Each of those initial six exact files was fetched through Maven Central and the Gradle plugin portal route, compared byte for byte, and checked against Maven Central's published SHA-256 sidecar. The six exact hashes were added; no checksum was replaced, verification mode relaxed, or wildcard trust introduced.

| Exact artifact | Verified SHA-256 | Canonical source |
| --- | --- | --- |
| `jackson-base-3.1.7.pom` | `7285af7629720e24db11084bc5a89cb7af4d4d35339e7163902958a5cdfd10fe` | [Maven Central](https://repo.maven.apache.org/maven2/tools/jackson/jackson-base/3.1.7/jackson-base-3.1.7.pom) |
| `junit-bom-5.10.2.module` | `de23b114b3e4119a8fe6eb17bed5a3852816698bace67071579d6d927ebb080a` | [Maven Central](https://repo.maven.apache.org/maven2/org/junit/junit-bom/5.10.2/junit-bom-5.10.2.module) |
| `junit-bom-5.11.0-M2.module` | `86477abcf490d6ca059aa9973cb108d22a506f49d1a5569bb32cc6cbf43c2cce` | [Maven Central](https://repo.maven.apache.org/maven2/org/junit/junit-bom/5.11.0-M2/junit-bom-5.11.0-M2.module) |
| `junit-bom-5.11.4.module` | `a9a4f27be94e99b9d570162d246a80f686d277d5d31aeb5481047cf51daf46e4` | [Maven Central](https://repo.maven.apache.org/maven2/org/junit/junit-bom/5.11.4/junit-bom-5.11.4.module) |
| `junit-bom-5.13.3.module` | `5dc84d74ef981d023c639eb0cbc4b1f9ef891034287ce1b101effbbc0f1534b4` | [Maven Central](https://repo.maven.apache.org/maven2/org/junit/junit-bom/5.13.3/junit-bom-5.13.3.module) |
| `opentelemetry-bom-1.49.0.module` | `ed8b705c0f275f3eb6dd4b25a87eadf966bb70459e0db0ba6d54d4d8e76aaf91` | [Maven Central](https://repo.maven.apache.org/maven2/io/opentelemetry/opentelemetry-bom/1.49.0/opentelemetry-bom-1.49.0.module) |

The immutable `v0.1.0` source tag predates this metadata correction. Use the current `main` checkout for a cold build. The released application source and observed local v0.1.0 results are unchanged. The publication record distinguishes original local evidence from subsequent CI observations.

## Complete cold configuration resolution

The next hosted build reached application resolution and identified 22 additional missing POM/module metadata files. A separate scratch checkout generated a candidate from all resolvable configurations with an initially empty Gradle home. Every added exact hash was reviewed against canonical HTTPS Maven Central bytes and the plugin portal route before merging it into the strict source file. All original hashes were preserved; no executable artifact or selected dependency version changed. Sixteen of these additional files also had matching publisher SHA-256 sidecars; six older metadata publications did not publish that sidecar.

| Exact artifact | Pinned SHA-256 | Publisher SHA-256 sidecar | Canonical source |
| --- | --- | --- | --- |
| `oss-parent-68.pom` | `25eafd96dae242b6b5a7108f55b2c14015b828daa5abe234289fd6e774a1ce57` | matched | [Maven Central](https://repo.maven.apache.org/maven2/com/fasterxml/oss-parent/68/oss-parent-68.pom) |
| `jackson-bom-2.19.1.pom` | `ba6d68eeab3a1cc13a77a8ade2197ff9a32aa1cffeac72847d49badd82e1b9ce` | matched | [Maven Central](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/jackson-bom/2.19.1/jackson-bom-2.19.1.pom) |
| `jackson-parent-2.19.2.pom` | `639a2b63dd05da4e381081303985caadfbb7ad9f85b1d2320638f6b11f208365` | matched | [Maven Central](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/jackson-parent/2.19.2/jackson-parent-2.19.2.pom) |
| `opentelemetry-bom-1.62.0.module` | `599f9f1632e58033a4dca25355b7b3f854bcc5c601a4b8edae88ad259f85ad69` | matched | [Maven Central](https://repo.maven.apache.org/maven2/io/opentelemetry/opentelemetry-bom/1.62.0/opentelemetry-bom-1.62.0.module) |
| `reactor-bom-2025.0.8.module` | `7f3860b5780f56ee335c97b92e753ef80c6701833fd738317d902e18b247e57e` | matched | [Maven Central](https://repo.maven.apache.org/maven2/io/projectreactor/reactor-bom/2025.0.8/reactor-bom-2025.0.8.module) |
| `jakarta.jakartaee-bom-9.1.0.pom` | `df98e0266219fdbb82562826d79a3a20776a8ba02aa787f0f0765a538654c8a4` | not published; canonical bytes compared | [Maven Central](https://repo.maven.apache.org/maven2/jakarta/platform/jakarta.jakartaee-bom/9.1.0/jakarta.jakartaee-bom-9.1.0.pom) |
| `jakartaee-api-parent-9.1.0.pom` | `a7702c487026802784b5797b62330a8b8d6592bf0f4737b25c67a5eac82659c0` | not published; canonical bytes compared | [Maven Central](https://repo.maven.apache.org/maven2/jakarta/platform/jakartaee-api-parent/9.1.0/jakartaee-api-parent-9.1.0.pom) |
| `groovy-bom-4.0.27.module` | `d6c2254c8347b84cda84caf74914a187c2f377e4284e8d8bb3f901521810aa8b` | matched | [Maven Central](https://repo.maven.apache.org/maven2/org/apache/groovy/groovy-bom/4.0.27/groovy-bom-4.0.27.module) |
| `groovy-bom-4.0.27.pom` | `aa44eb52bfdfe61d27b3e450d2b348d88deaa3437ab4d9d49a84095348f9f6fb` | matched | [Maven Central](https://repo.maven.apache.org/maven2/org/apache/groovy/groovy-bom/4.0.27/groovy-bom-4.0.27.pom) |
| `groovy-bom-5.0.8.module` | `3657f5207155d1630f1c4f50346d3f3e06118750a694b42df104952efbfa7722` | matched | [Maven Central](https://repo.maven.apache.org/maven2/org/apache/groovy/groovy-bom/5.0.8/groovy-bom-5.0.8.module) |
| `log4j-2.25.5.pom` | `00faeca1c624b9773a71b0fcff8fbd195e5101a4782aab085d74a6fb469881bd` | not published; canonical bytes compared | [Maven Central](https://repo.maven.apache.org/maven2/org/apache/logging/log4j/log4j/2.25.5/log4j-2.25.5.pom) |
| `project-1.0.7.pom` | `205c039a42cbae3556efbeb04a483eb3a3cf9550bd75bf84260dc8f28218f105` | not published; canonical bytes compared | [Maven Central](https://repo.maven.apache.org/maven2/org/eclipse/ee4j/project/1.0.7/project-1.0.7.pom) |
| `junit-bom-5.13.2.module` | `ed67e15221404ac42b5e598102edf762dd6a952dc95001e9c0c4ee74a04e8283` | matched | [Maven Central](https://repo.maven.apache.org/maven2/org/junit/junit-bom/5.13.2/junit-bom-5.13.2.module) |
| `junit-bom-5.13.2.pom` | `43b1104fb3fd4ef4b72a9751d41e09c29f001c8be00ff3972238dc169a734b49` | matched | [Maven Central](https://repo.maven.apache.org/maven2/org/junit/junit-bom/5.13.2/junit-bom-5.13.2.pom) |
| `spring-framework-bom-5.3.39.module` | `f88b40e2a50333b40b42f181eee272b75ec75dd8666cb145bd90b15b97e183e3` | not published; canonical bytes compared | [Maven Central](https://repo.maven.apache.org/maven2/org/springframework/spring-framework-bom/5.3.39/spring-framework-bom-5.3.39.module) |
| `spring-framework-bom-5.3.39.pom` | `f6d481093e75767cba1ac7e1db38f8f692cbe3e38744693371a75aeb21c6148b` | not published; canonical bytes compared | [Maven Central](https://repo.maven.apache.org/maven2/org/springframework/spring-framework-bom/5.3.39/spring-framework-bom-5.3.39.pom) |
| `spring-framework-bom-7.0.10.module` | `75e9b9707af2f6685d527f798cd45626b669c27b57b6b3d7c3255b742e9adc8f` | matched | [Maven Central](https://repo.maven.apache.org/maven2/org/springframework/spring-framework-bom/7.0.10/spring-framework-bom-7.0.10.module) |
| `spring-amqp-bom-4.1.2.module` | `372416aad14db0f5f75dd3005d1b6b4b09c24d8340499e4ebb5b5c0a8c6d587e` | matched | [Maven Central](https://repo.maven.apache.org/maven2/org/springframework/amqp/spring-amqp-bom/4.1.2/spring-amqp-bom-4.1.2.module) |
| `spring-integration-bom-7.1.2.module` | `06c0abe93064df22ef4d137fa39d27544a0e460944490cdaf7852adc42af6d07` | matched | [Maven Central](https://repo.maven.apache.org/maven2/org/springframework/integration/spring-integration-bom/7.1.2/spring-integration-bom-7.1.2.module) |
| `spring-pulsar-bom-2.0.8.module` | `60ee68d3198417ac22fc1cb0fc6c309a7f1026b30a05f3f779727a8ed622e561` | matched | [Maven Central](https://repo.maven.apache.org/maven2/org/springframework/pulsar/spring-pulsar-bom/2.0.8/spring-pulsar-bom-2.0.8.module) |
| `spring-security-bom-7.1.2.module` | `d2069bb6d8b320578969e50491349bc5a25a6b5dd98c89cd3821177454b950c6` | matched | [Maven Central](https://repo.maven.apache.org/maven2/org/springframework/security/spring-security-bom/7.1.2/spring-security-bom-7.1.2.module) |
| `spring-session-bom-4.1.1.module` | `604335e7a5a3b57a8e9d77e3bda6486df9e8b80e782c72bd32315b87eaabb8b9` | matched | [Maven Central](https://repo.maven.apache.org/maven2/org/springframework/session/spring-session-bom/4.1.1/spring-session-bom-4.1.1.module) |
