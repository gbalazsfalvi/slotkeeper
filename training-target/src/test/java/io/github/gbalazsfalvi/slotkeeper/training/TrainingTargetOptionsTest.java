package io.github.gbalazsfalvi.slotkeeper.training;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TrainingTargetOptionsTest {
  @Test
  void nativeDefaultIsLoopbackAndDockerWildcardRequiresExplicitOption() {
    String identity = "750b9921-28af-4a08-a172-28cdf13f3ad9";
    var nativeOptions = TrainingTargetOptions.parse(new String[] {"--instance-id", identity});
    assertEquals("127.0.0.1", nativeOptions.bindAddress());
    assertEquals(4193, nativeOptions.port());
    var dockerOptions =
        TrainingTargetOptions.parse(
            new String[] {
              "--instance-id", identity, "--bind-address", "0.0.0.0", "--port", "4193"
            });
    assertEquals("0.0.0.0", dockerOptions.bindAddress());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TrainingTargetOptions.parse(
                new String[] {"--instance-id", identity, "--bind-address", "192.0.2.1"}));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TrainingTargetOptions.parse(new String[] {"--instance-id", identity, "--port", "80"}));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TrainingTargetOptions.parse(
                new String[] {"--instance-id", identity, "--database", "product"}));
  }
}
