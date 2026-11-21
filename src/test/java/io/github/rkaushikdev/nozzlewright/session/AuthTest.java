package io.github.rkaushikdev.nozzlewright.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.CommandRejected;
import io.github.rkaushikdev.nozzlewright.NotAuthenticated;
import io.github.rkaushikdev.nozzlewright.PermissionDenied;
import io.github.rkaushikdev.nozzlewright.protocol.Codec;
import io.github.rkaushikdev.nozzlewright.protocol.Dialect;
import io.github.rkaushikdev.nozzlewright.settings.SessionSettings;
import io.github.rkaushikdev.nozzlewright.testing.ScriptedPrinter;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthTest {

    private ScriptedPrinter printer;
    private Channel channel;
    private Authenticator auth;

    @BeforeEach
    void wire() throws Exception {
        ScriptedPrinter.Link link = ScriptedPrinter.scriptedLink(Map.of("LOGIN", "0:USER=mueller,LEVEL=2"));
        printer = link.printer();
        channel = new Channel(
                link.transport(),
                Dialect.REVISION_TWO,
                SessionSettings.builder()
                        .keepaliveIntervalSeconds(1.0)
                        .commandTimeoutSeconds(0.5)
                        .retries(0)
                        .build());
        channel.start();
        auth = new Authenticator(channel);
    }

    @AfterEach
    void unwire() throws InterruptedException {
        channel.stop();
    }

    @Test
    void levelNames() {
        assertThat(new Operator("a", 0).levelName()).isEqualTo("operator");
        assertThat(new Operator("a", 1).levelName()).isEqualTo("supervisor");
        assertThat(new Operator("a", 2).levelName()).isEqualTo("engineer");
        assertThat(new Operator("a", 3).levelName()).isEqualTo("factory");
        assertThat(new Operator("a", 9).levelName()).contains("9");
    }

    @Test
    void operatorStrIsReadable() {
        assertThat(new Operator("mueller", 2)).hasToString("mueller (engineer)");
    }

    @Test
    void operatorPermissionCheck() {
        assertThat(new Operator("a", 0).may("STATUS")).isTrue();
        assertThat(new Operator("a", 0).may("ADDUSER")).isFalse();
        assertThat(new Operator("a", 1).may("ADDUSER")).isTrue();
        assertThat(new Operator("a", 2).may("FIRINGVOL")).isFalse();
        assertThat(new Operator("a", 3).may("FIRINGVOL")).isTrue();
    }

    @Test
    void credentialsNeverPrintThePin() {
        Credentials held = new Credentials("mueller", "4417");
        assertThat(held.toString()).doesNotContain("4417").isEqualTo("mueller/****");
    }

    @Test
    void loginRecordsTheOperator() throws Exception {
        Operator operator = auth.login("mueller", "4417");
        assertThat(operator.name()).isEqualTo("mueller");
        assertThat(operator.level()).isEqualTo(2);
        assertThat(auth.loggedIn()).isTrue();
    }

    @Test
    void loginUsesTheNameThePrinterReports() throws Exception {
        printer.expect("LOGIN", "0:USER=MUELLER.J,LEVEL=1");
        assertThat(auth.login("mueller", "4417").name()).isEqualTo("MUELLER.J");
    }

    @Test
    void missingLevelDefaultsToOperator() throws Exception {
        printer.expect("LOGIN", "0:USER=kraft");
        assertThat(auth.login("kraft", "0000").level()).isZero();
    }

    @Test
    void junkLevelDefaultsToOperator() throws Exception {
        printer.expect("LOGIN", "0:USER=kraft,LEVEL=high");
        assertThat(auth.login("kraft", "0000").level()).isZero();
    }

    @Test
    void levelIsClamped() throws Exception {
        printer.expect("LOGIN", "0:USER=kraft,LEVEL=99");
        assertThat(auth.login("kraft", "0000").level()).isEqualTo(3);
    }

    @Test
    void badPinIsReported() {
        printer.expect("LOGIN", "10:");
        assertThatThrownBy(() -> auth.login("mueller", "0000")).isInstanceOf(CommandRejected.class);
        assertThat(auth.loggedIn()).isFalse();
    }

    @Test
    void requireBeforeLogin() {
        assertThatThrownBy(() -> auth.require("STATUS"))
                .isInstanceOf(NotAuthenticated.class)
                .hasMessageContaining("STATUS");
    }

    @Test
    void requireBlocksAVerbAboveTheLevel() throws Exception {
        auth.login("mueller", "4417");
        auth.require("HORZRES");
        assertThatThrownBy(() -> auth.require("FIRINGVOL"))
                .isInstanceOf(PermissionDenied.class)
                .hasMessageContaining("needs level 3");
    }

    @Test
    void logoutClearsTheSession() throws Exception {
        auth.login("mueller", "4417");
        auth.logout();
        assertThat(auth.loggedIn()).isFalse();
        assertThat(printer.count("LOGOUT")).isEqualTo(1);
    }

    @Test
    void logoutWithoutASessionIsQuiet() throws Exception {
        auth.logout();
        assertThat(printer.count("LOGOUT")).isZero();
    }

    @Test
    void logoutForgetsEvenWhenTheCommandFails() throws Exception {
        auth.login("mueller", "4417");
        printer.expect("LOGOUT", "6:");
        assertThatThrownBy(auth::logout).isInstanceOf(CommandRejected.class);
        assertThat(auth.loggedIn()).isFalse();
    }

    @Test
    void resumeLogsTheSameOperatorBackIn() throws Exception {
        auth.login("mueller", "4417");
        auth.sessionTimedOut();
        Optional<Operator> operator = auth.resume();
        assertThat(operator).isPresent();
        assertThat(operator.get().name()).isEqualTo("mueller");
        assertThat(auth.relogins()).isEqualTo(1);
        assertThat(printer.count("LOGIN")).isEqualTo(2);
    }

    @Test
    void resumeWithoutCredentialsReturnsNone() throws Exception {
        assertThat(auth.resume()).isEmpty();
    }

    @Test
    void resumeGivesUpOnADeletedUser() throws Exception {
        auth.login("mueller", "4417");
        printer.expect("LOGIN", "10:");
        assertThat(auth.resume()).isEmpty();
        assertThat(auth.loggedIn()).isFalse();
    }

    @Test
    void resumeReraisesAnUnexpectedRefusal() throws Exception {
        auth.login("mueller", "4417");
        printer.expect("LOGIN", "6:");
        assertThatThrownBy(auth::resume).isInstanceOf(CommandRejected.class);
    }

    @Test
    void forgetDropsEverything() throws Exception {
        auth.login("mueller", "4417");
        auth.forget();
        assertThat(auth.loggedIn()).isFalse();
        assertThat(auth.resume()).isEmpty();
        assertThat(printer.count("LOGOUT")).isZero();
    }

    @Test
    void thePinNeverReachesTheLog() throws Exception {
        auth.login("mueller", "4417");
        assertThat(Codec.buildRequest("LOGIN", "mueller", "4417").redacted()).doesNotContain("4417");
    }
}
