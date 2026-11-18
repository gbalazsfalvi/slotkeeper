# v0.1.0 observed evidence

This snapshot comes from an actual clean-checkout, initially empty-database rehearsal of source `44a4caff44eb14424957ce51ae21bbc153fcf25f` on 18 November 2026. All fifteen checks passed. The start/finish instants and every sanitized observation are retained in the original exports.

- [Portable report](report.md), [JSON observations](report.json), [standalone HTML](report.html).
- [Independent two-JVM database contention](database-contention.json): 100 attempts, one created reservation, 99 slot conflicts, one active assignment.
- [Verification summary](verification-summary.json): actual JUnit counters/coverage plus observed React, browser and mocked-launcher results.
- `SHA256SUMS` is the original exporter's unchanged inventory of its three reports. `SNAPSHOT_SHA256SUMS` additionally covers this snapshot's database evidence, summary and explanation.

For example, `sha256sum --check SNAPSHOT_SHA256SUMS` verifies the copied bytes on a system providing that command. Hashes do not certify their origin or application security.

A11 retains 130 attempts, at most eight in flight, with 73 observed rate rejections and no transport failures. A12 retains a separate 100-attempt HTTP start barrier and classifies HTTP quota/rate decisions separately from database contention. S02 is a read-only consistent database snapshot, not a product-generated success flag. The teaching positive control is a separate synthetic fixture with a reduced actor model.

The source revision identifies tested executable code. A later release commit adds only documentation, images, evidence and completion metadata. Native Linux launch and remote GitHub Actions execution were not observed. This is local reference evidence, not a certification or a production throughput result.
