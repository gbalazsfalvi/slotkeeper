package io.github.gbalazsfalvi.slotkeeper.training;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import tools.jackson.databind.json.JsonMapper;

/** Explicitly launched vulnerable teaching process; it cannot use product sessions or databases. */
public final class TrainingTargetMain {
  private TrainingTargetMain() {}

  /**
   * Starts the separate teaching listener using the owning script's instance identity.
   *
   * @param args {@code --instance-id UUID}, optional {@code --bind-address} and {@code --port};
   *     wildcard binding is intended only inside the isolated Docker teaching namespace
   * @throws IOException if the fixed local port cannot be bound
   */
  public static void main(String[] args) throws IOException {
    var options = TrainingTargetOptions.parse(args);
    var fixture = new TrainingFixture(options.instanceId());
    var mapper = JsonMapper.builder().build();
    var server =
        HttpServer.create(
            new InetSocketAddress(InetAddress.getByName(options.bindAddress()), options.port()),
            16);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(
        "/",
        exchange -> {
          try (exchange) {
            var reply =
                fixture.reply(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("X-Training-Actor"));
            byte[] body = mapper.writeValueAsString(reply.body()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(reply.status(), body.length);
            exchange.getResponseBody().write(body);
          }
        });
    Runtime.getRuntime()
        .addShutdownHook(new Thread(() -> server.stop(0), "training-target-shutdown"));
    server.start();
    System.out.println(
        "Slotkeeper Teaching Target listening on "
            + options.bindAddress()
            + ":4193; reduced synthetic actor model.");
  }
}
