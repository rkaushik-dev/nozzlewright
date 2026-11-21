package io.github.rkaushikdev.nozzlewright.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

/** The whole gateway, up against simulated machines and down again, on every build. */
class SmokeTest {

    @Test
    void theGatewayComesUpAnswersAndStops() {
        StringWriter said = new StringWriter();
        int code = Smoke.run(new PrintWriter(said, true));
        assertThat(code).as(said.toString()).isZero();
        assertThat(said.toString()).contains("smoke ok");
    }
}
