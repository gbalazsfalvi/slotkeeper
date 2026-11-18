# Cold-cache dependency metadata verification

On 18 November 2026 the first GitHub Actions cold build rejected six previously unrecorded plugin-resolution metadata files. Existing Windows checks used a warm Gradle cache. No application behavior or dependency version changed. Strict verification rejected the missing metadata as intended.

Each exact file was fetched through Maven Central and the Gradle plugin portal route, compared byte for byte, and checked against Maven Central's published SHA-256 sidecar. The six exact hashes were added; no checksum was replaced, verification mode relaxed, or wildcard trust introduced.

| Exact artifact | Verified SHA-256 | Canonical source |
| --- | --- | --- |
| `jackson-base-3.1.7.pom` | `7285af7629720e24db11084bc5a89cb7af4d4d35339e7163902958a5cdfd10fe` | [Maven Central](https://repo.maven.apache.org/maven2/tools/jackson/jackson-base/3.1.7/jackson-base-3.1.7.pom) |
| `junit-bom-5.10.2.module` | `de23b114b3e4119a8fe6eb17bed5a3852816698bace67071579d6d927ebb080a` | [Maven Central](https://repo.maven.apache.org/maven2/org/junit/junit-bom/5.10.2/junit-bom-5.10.2.module) |
| `junit-bom-5.11.0-M2.module` | `86477abcf490d6ca059aa9973cb108d22a506f49d1a5569bb32cc6cbf43c2cce` | [Maven Central](https://repo.maven.apache.org/maven2/org/junit/junit-bom/5.11.0-M2/junit-bom-5.11.0-M2.module) |
| `junit-bom-5.11.4.module` | `a9a4f27be94e99b9d570162d246a80f686d277d5d31aeb5481047cf51daf46e4` | [Maven Central](https://repo.maven.apache.org/maven2/org/junit/junit-bom/5.11.4/junit-bom-5.11.4.module) |
| `junit-bom-5.13.3.module` | `5dc84d74ef981d023c639eb0cbc4b1f9ef891034287ce1b101effbbc0f1534b4` | [Maven Central](https://repo.maven.apache.org/maven2/org/junit/junit-bom/5.13.3/junit-bom-5.13.3.module) |
| `opentelemetry-bom-1.49.0.module` | `ed8b705c0f275f3eb6dd4b25a87eadf966bb70459e0db0ba6d54d4d8e76aaf91` | [Maven Central](https://repo.maven.apache.org/maven2/io/opentelemetry/opentelemetry-bom/1.49.0/opentelemetry-bom-1.49.0.module) |

The immutable `v0.1.0` source tag predates this metadata correction. Use the current `main` checkout for a cold build. The released application source and observed local v0.1.0 results are unchanged. The publication record distinguishes original local evidence from subsequent CI observations.
