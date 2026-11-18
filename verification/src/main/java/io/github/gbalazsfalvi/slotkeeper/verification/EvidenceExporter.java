package io.github.gbalazsfalvi.slotkeeper.verification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exports typed sanitized evidence only, retaining failed histories rather than overwriting them.
 */
final class EvidenceExporter {
  private final JsonMapper mapper = JsonMapper.builder().build();

  /**
   * Writes standalone public JSON, Markdown and escaped HTML with SHA-256 inventories.
   *
   * @param report the immutable safe report
   * @param directory a new or empty report directory
   * @throws IOException if the destination already contains report artifacts or writing fails
   */
  public void write(EvidenceReport report, Path directory) throws IOException {
    Files.createDirectories(directory);
    List<String> names = List.of("report.json", "report.md", "report.html", "SHA256SUMS");
    for (String name : names)
      if (Files.exists(directory.resolve(name)))
        throw new java.nio.file.FileAlreadyExistsException(name);
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("project", "Slotkeeper");
    json.put("schemaVersion", 1);
    json.put("startedAt", report.startedAt().toString());
    json.put("finishedAt", report.finishedAt().toString());
    json.put("revision", report.revision());
    json.put("complete", report.complete());
    json.put("counts", report.counts());
    json.put("targets", report.targets());
    json.put(
        "policy",
        Map.of(
            "holdTtlSeconds",
            120,
            "activeHoldAllowance",
            2,
            "mutationLimitPerFixedMinute",
            60,
            "slotMinutes",
            30));
    json.put("limits", limits());
    json.put("results", report.results());
    put(
        directory,
        "report.json",
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json) + "\n");
    put(directory, "report.md", markdown(report));
    put(directory, "report.html", html(report));
    StringBuilder sums = new StringBuilder();
    for (String name : names.subList(0, 3))
      sums.append(sha256(Files.readAllBytes(directory.resolve(name))))
          .append("  ")
          .append(name)
          .append('\n');
    put(directory, "SHA256SUMS", sums.toString());
  }

  private static void put(Path directory, String name, String value) throws IOException {
    Files.writeString(
        directory.resolve(name), value, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
  }

  private static String limits() {
    return "Owned local synthetic exercise only. Instance markers prevent accidents and are not security attestations. HTTP cases use scoped API state reads. S02 independently reads the owned Compose PostgreSQL database in a read-only transaction. HTTP quota and rate rejections are guard decisions, not database-lock admissions. A12 requires a separate two-process PostgreSQL contention artifact. The teaching fixture uses a reduced actor model and is not the product login. Request latencies are observations, not a comparative benchmark. No security certification or production-scale performance claim is made.";
  }

  private static String markdown(EvidenceReport report) {
    StringBuilder output = new StringBuilder("# Slotkeeper local rehearsal\n\n");
    output
        .append("Complete: **")
        .append(report.complete())
        .append("**  \nRevision: `")
        .append(report.revision())
        .append("`  \nStarted: ")
        .append(report.startedAt())
        .append("  \nFinished: ")
        .append(report.finishedAt())
        .append("\n\n")
        .append(limits())
        .append("\n\n| Check | Observation | Outcome | Reason |\n| --- | --- | --- | --- |\n");
    for (var result : report.results())
      output
          .append("| ")
          .append(result.id())
          .append(" | ")
          .append(result.id().title())
          .append(" | ")
          .append(result.outcome())
          .append(" | ")
          .append(result.reason())
          .append(" |\n");
    for (var result : report.results()) {
      output
          .append("\n## ")
          .append(result.id())
          .append(" — ")
          .append(result.id().title())
          .append("\n\n");
      for (var assertion : result.assertions())
        output
            .append("- ")
            .append(assertion.passed() ? "PASS" : "FAIL")
            .append(": ")
            .append(assertion.kind())
            .append('\n');
      if (!result.metrics().isEmpty())
        output.append("\nCounts: `").append(result.metrics()).append("`\n");
      if (result.databaseState() != null) {
        output.append(
            "\nIndependent owned Compose database readback:\n\n| Observation | Value |\n| --- | --- |\n");
        result
            .databaseState()
            .fields()
            .forEach(
                (name, value) ->
                    output.append("| ").append(name).append(" | ").append(value).append(" |\n"));
      }
      output.append("\nHTTP observations: ").append(result.observations().size()).append(".\n");
      if (!result.observations().isEmpty()) {
        output
            .append("\n| ")
            .append(String.join(" | ", observationHeaders()))
            .append(" |\n| ")
            .append(
                String.join(
                    " | ", java.util.Collections.nCopies(observationHeaders().size(), "---")))
            .append(" |\n");
        for (var observation : result.observations())
          output
              .append("| ")
              .append(String.join(" | ", observationCells(observation)))
              .append(" |\n");
      }
    }
    return output.toString();
  }

  private static String html(EvidenceReport report) {
    StringBuilder output =
        new StringBuilder(
            "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\"><title>Slotkeeper local rehearsal</title><style>body{font:16px/1.6 system-ui,sans-serif;color:#17233a;background:#f6f7f9;max-width:1100px;margin:32px auto;padding:0 20px}h1,h2{line-height:1.2}table{border-collapse:collapse;width:100%;background:white}td,th{text-align:left;border-bottom:1px solid #dce1e8;padding:10px;vertical-align:top}code{overflow-wrap:anywhere}details{background:white;margin:14px 0;padding:16px;border:1px solid #dce1e8}.PASS{color:#16603c}.FAIL,.ERROR{color:#a51e2d}.INCOMPLETE{color:#805400}</style><h1>Slotkeeper local rehearsal</h1>");
    output
        .append("<p>Complete: <strong>")
        .append(report.complete())
        .append("</strong><br>Revision: <code>")
        .append(escapeHtml(report.revision()))
        .append("</code><br>Started: ")
        .append(escapeHtml(report.startedAt().toString()))
        .append("<br>Finished: ")
        .append(escapeHtml(report.finishedAt().toString()))
        .append("</p><p>")
        .append(escapeHtml(limits()))
        .append(
            "</p><table><thead><tr><th>Check</th><th>Observation</th><th>Outcome</th><th>Reason</th></tr></thead><tbody>");
    for (var result : report.results())
      output
          .append("<tr><td>")
          .append(result.id())
          .append("</td><td>")
          .append(escapeHtml(result.id().title()))
          .append("</td><td class=\"")
          .append(result.outcome())
          .append("\">")
          .append(result.outcome())
          .append("</td><td>")
          .append(result.reason())
          .append("</td></tr>");
    output.append("</tbody></table>");
    for (var result : report.results()) {
      output
          .append("<details><summary>")
          .append(result.id())
          .append(" — ")
          .append(escapeHtml(result.id().title()))
          .append("</summary><ul>");
      for (var assertion : result.assertions())
        output
            .append("<li>")
            .append(assertion.passed() ? "PASS" : "FAIL")
            .append(": ")
            .append(assertion.kind())
            .append("</li>");
      output
          .append("</ul><p>Counts: <code>")
          .append(escapeHtml(result.metrics().toString()))
          .append("</code></p><p>HTTP observations: ")
          .append(result.observations().size())
          .append(".</p>");
      if (result.databaseState() != null) {
        output.append(
            "<p>Independent owned Compose database readback</p><table><thead><tr><th>Observation</th><th>Value</th></tr></thead><tbody>");
        result
            .databaseState()
            .fields()
            .forEach(
                (name, value) ->
                    output
                        .append("<tr><td>")
                        .append(escapeHtml(name))
                        .append("</td><td><code>")
                        .append(escapeHtml(value.toString()))
                        .append("</code></td></tr>"));
        output.append("</tbody></table>");
      }
      if (!result.observations().isEmpty()) {
        output.append("<div style=\"overflow:auto\"><table><thead><tr>");
        for (String header : observationHeaders())
          output.append("<th>").append(escapeHtml(header)).append("</th>");
        output.append("</tr></thead><tbody>");
        for (var observation : result.observations()) {
          output.append("<tr>");
          for (String value : observationCells(observation))
            output.append("<td><code>").append(escapeHtml(value)).append("</code></td>");
          output.append("</tr>");
        }
        output.append("</tbody></table></div>");
      }
      output.append("</details>");
    }
    return output.append("</html>\n").toString();
  }

  private static List<String> observationHeaders() {
    return List.of(
        "Observed at",
        "Port",
        "Method",
        "Endpoint",
        "HTTP status",
        "Code",
        "Request ID",
        "Reservation ID",
        "Workspace ID",
        "Owner ID",
        "Room ID",
        "State",
        "Start",
        "End",
        "Created",
        "Expires",
        "Confirmed",
        "Server time",
        "Replayed",
        "Elapsed ms");
  }

  private static List<String> observationCells(SanitizedObservation observation) {
    return List.of(
        text(observation.observedAt()),
        text(observation.targetPort()),
        text(observation.method()),
        text(observation.endpoint()),
        text(observation.status()),
        text(observation.code()),
        text(observation.requestId()),
        text(observation.resourceId()),
        text(observation.workspaceId()),
        text(observation.ownerId()),
        text(observation.roomId()),
        text(observation.state()),
        text(observation.startAt()),
        text(observation.endAt()),
        text(observation.createdAt()),
        text(observation.expiresAt()),
        text(observation.confirmedAt()),
        text(observation.serverNow()),
        text(observation.replayed()),
        text(observation.latencyMillis()));
  }

  private static String text(Object value) {
    return value == null ? "" : value.toString();
  }

  /**
   * Escapes every HTML-sensitive text character.
   *
   * @param value arbitrary text
   * @return safe HTML text content
   */
  public static String escapeHtml(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Required SHA-256 implementation unavailable", exception);
    }
  }
}
