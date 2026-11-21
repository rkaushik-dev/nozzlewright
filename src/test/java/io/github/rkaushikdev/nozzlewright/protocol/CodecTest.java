package io.github.rkaushikdev.nozzlewright.protocol;

import static io.github.rkaushikdev.nozzlewright.testing.Wire.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.ProtocolError;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CodecTest {

    @Test
    void requestWithoutArguments() {
        assertThat(Codec.buildRequest("STATUS").render()).isEqualTo("STATUS");
    }

    @Test
    void requestWithArguments() {
        assertThat(Codec.buildRequest("LOGIN", "mueller", "4417").render()).isEqualTo("LOGIN:mueller,4417");
    }

    @Test
    void requestRedactsThePin() {
        Request request = Codec.buildRequest("LOGIN", "mueller", "4417");
        assertThat(request.redacted()).isEqualTo("LOGIN:mueller,****");
        assertThat(request.redacted()).doesNotContain("4417");
    }

    @Test
    void requestRedactsPinOnUserManagement() {
        assertThat(Codec.buildRequest("ADDUSER", "novak", "9091", 1).redacted()).isEqualTo("ADDUSER:novak,****,1");
    }

    @Test
    void wrongArgumentCountIsRejected() {
        assertThatThrownBy(() -> Codec.buildRequest("LOGIN", "mueller"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("expected 2 arguments")
                .hasMessageStartingWith("LOGIN=('mueller',)");
    }

    @Test
    void intArgumentIsNormalised() {
        assertThat(Codec.buildRequest("HORZRES", "300").render()).isEqualTo("HORZRES:300");
    }

    @Test
    void intArgumentBelowMinimum() {
        assertThatThrownBy(() -> Codec.buildRequest("HORZRES", 50))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("below the minimum");
    }

    @Test
    void intArgumentAboveMaximum() {
        assertThatThrownBy(() -> Codec.buildRequest("FIRINGVOL", 1, 20000))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("above the maximum");
    }

    @Test
    void intArgumentRejectsText() {
        assertThatThrownBy(() -> Codec.buildRequest("HORZRES", "three hundred"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("whole number");
    }

    @ParameterizedTest
    @ValueSource(strings = {"on", "YES", "1", "true", "enabled"})
    void flagAcceptsTruthyWords(String word) {
        assertThat(Codec.buildRequest("HFIRE", word).render()).isEqualTo("HFIRE:1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"off", "NO", "0", "false", "disabled"})
    void flagAcceptsFalsyWords(String word) {
        assertThat(Codec.buildRequest("HFIRE", word).render()).isEqualTo("HFIRE:0");
    }

    @Test
    void flagRejectsNonsense() {
        assertThatThrownBy(() -> Codec.buildRequest("HFIRE", "maybe"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("on or off");
    }

    @Test
    void flagAcceptsABool() {
        assertThat(Codec.buildRequest("HFIRE", true).render()).isEqualTo("HFIRE:1");
    }

    @Test
    void choiceIsUpperCased() {
        assertThat(Codec.buildRequest("PRINTMODE", "stand").render()).isEqualTo("PRINTMODE:STAND");
    }

    @Test
    void choiceRejectsUnknownOption() {
        assertThatThrownBy(() -> Codec.buildRequest("PRINTMODE", "TURBO"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("not one of");
    }

    @Test
    void textArgumentLengthIsCapped() {
        assertThatThrownBy(() -> Codec.buildRequest("LABEL", "x".repeat(25)))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("longer than");
    }

    @ParameterizedTest
    @ValueSource(strings = {"lot,a", "lot;a", "lot:a"})
    void textArgumentRejectsSeparators(String bad) {
        assertThatThrownBy(() -> Codec.buildRequest("LABEL", bad))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must not contain");
    }

    @Test
    void parseOkResponse() {
        Response response = Codec.parseResponse(bytes("0:READY"));
        assertThat(response.ok()).isTrue();
        assertThat(response.scalar()).isEqualTo("READY");
    }

    @Test
    void parseErrorResponse() {
        Response response = Codec.parseResponse("3:");
        assertThat(response.ok()).isFalse();
        assertThat(response.status()).isEqualTo(3);
    }

    @Test
    void parseResponseWithoutPayload() {
        assertThat(Codec.parseResponse("0").payload()).isEmpty();
    }

    @Test
    void parseResponseRejectsEmptyFrame() {
        assertThatThrownBy(() -> Codec.parseResponse("   "))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("empty response");
    }

    @Test
    void parseResponseRejectsMissingStatus() {
        assertThatThrownBy(() -> Codec.parseResponse("READY"))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("no status prefix");
    }

    @Test
    void parseResponseRejectsNonNumericStatus() {
        assertThatThrownBy(() -> Codec.parseResponse("ERR:READY"))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("not a number");
    }

    @Test
    void recordIsUpperCasedAndTrimmed() {
        assertThat(Codec.parseRecord(" fam = HVX8PEN , rev=2 ")).isEqualTo(Map.of("FAM", "HVX8PEN", "REV", "2"));
    }

    @Test
    void recordToleratesTrailingCommas() {
        assertThat(Codec.parseRecord("FAM=HVXDOD,,")).isEqualTo(Map.of("FAM", "HVXDOD"));
    }

    @Test
    void recordRejectsAFieldWithoutEquals() {
        assertThatThrownBy(() -> Codec.parseRecord("FAM"))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("no equals sign");
    }

    @Test
    void tableSplitsRows() {
        assertThat(Codec.parseTable("PEN=1,LVL=88;PEN=2,LVL=12;"))
                .isEqualTo(List.of(Map.of("PEN", "1", "LVL", "88"), Map.of("PEN", "2", "LVL", "12")));
    }

    @Test
    void tableOfOneRow() {
        assertThat(Codec.parseTable("PEN=1,LVL=88")).isEqualTo(List.of(Map.of("PEN", "1", "LVL", "88")));
    }

    @Test
    void tableOfNothing() {
        assertThat(Codec.parseTable("")).isEmpty();
    }

    @Test
    void responseHelpersReachTheParsers() {
        assertThat(Codec.parseResponse("0:PEN=1,LVL=88;PEN=2,LVL=12").table()).hasSize(2);
        assertThat(Codec.parseResponse("0:FAM=HVXDOD").record()).containsEntry("FAM", "HVXDOD");
    }
}
