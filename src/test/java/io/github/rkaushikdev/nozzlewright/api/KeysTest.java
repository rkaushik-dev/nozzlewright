package io.github.rkaushikdev.nozzlewright.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.nozzlewright.cli.Cli;
import io.github.rkaushikdev.nozzlewright.fleet.Registry;
import io.github.rkaushikdev.nozzlewright.settings.Settings;
import io.github.rkaushikdev.nozzlewright.siteconfig.SiteConfig;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeysTest {

    @TempDir
    Path tmp;

    /** The API with keys on it. */
    static final class Harness {
        final Database database = Database.inMemory();
        final Services services;

        Harness(String... keys) {
            database.createAll();
            services = new Services(Settings.builder().siteId("bench").build(), database, new Registry("bench"));
            services.apiKeys(Set.of(keys));
        }

        Http client() {
            return Http.of(services);
        }
    }

    private final List<Harness> benches = new ArrayList<>();

    @AfterEach
    void close() {
        benches.forEach(bench -> bench.database.close());
    }

    Harness harness(String... keys) {
        Harness bench = new Harness(keys);
        benches.add(bench);
        return bench;
    }

    void writeSite(String text) throws Exception {
        Files.writeString(tmp.resolve(SiteConfig.SITE_FILE), text, StandardCharsets.UTF_8);
    }

    @Test
    void aNewKeyIsLongAndUrlSafe() {
        String key = KeyRing.newKey();
        assertThat(key.length()).isGreaterThanOrEqualTo(40);
        assertThat(key).doesNotContain("/");
        assertThat(key).doesNotContain("+");
    }

    @Test
    void twoKeysAreNeverTheSame() {
        assertThat(KeyRing.newKey()).isNotEqualTo(KeyRing.newKey());
    }

    @Test
    void aKeyHashesTheSameWayTwice() {
        assertThat(KeyRing.hashKey("alpha")).isEqualTo(KeyRing.hashKey("alpha"));
    }

    @Test
    void theStoredFormSaysWhatItIs() {
        assertThat(KeyRing.hashKey("alpha")).startsWith(KeyRing.DIGEST_PREFIX);
        assertThat(KeyRing.hashKey("alpha")).hasSize(KeyRing.DIGEST_PREFIX.length() + 64);
    }

    @Test
    void whitespaceAroundAKeyIsIgnored() {
        assertThat(KeyRing.hashKey("  alpha ")).isEqualTo(KeyRing.hashKey("alpha"));
    }

    @Test
    void anEmptyRingAcceptsNothing() {
        KeyRing ring = KeyRing.of(List.of());
        assertThat(ring.any()).isFalse();
        assertThat(ring.size()).isZero();
        assertThat(ring.accepts("alpha")).isFalse();
        assertThat(ring.advice()).isEqualTo("no keys, the API is open");
    }

    @Test
    void aRingOfPlainKeys() {
        KeyRing ring = KeyRing.of(List.of("alpha", "beta"));
        assertThat(ring.any()).isTrue();
        assertThat(ring.size()).isEqualTo(2);
        assertThat(ring.accepts("alpha")).isTrue();
        assertThat(ring.accepts("gamma")).isFalse();
        assertThat(ring.hasPlain()).isTrue();
        assertThat(ring.advice()).contains("some stored in the clear");
    }

    @Test
    void aRingOfHashedKeys() {
        KeyRing ring = KeyRing.of(List.of(KeyRing.hashKey("alpha")));
        assertThat(ring.accepts("alpha")).isTrue();
        assertThat(ring.hasPlain()).isFalse();
        assertThat(ring.advice()).isEqualTo("1 keys, all hashed");
    }

    @Test
    void aRingOfBoth() {
        KeyRing ring = KeyRing.of(List.of(KeyRing.hashKey("alpha"), "beta"));
        assertThat(ring.accepts("alpha")).isTrue();
        assertThat(ring.accepts("beta")).isTrue();
        assertThat(ring.hasPlain()).isTrue();
    }

    @Test
    void theSameKeyTwiceIsOneKey() {
        assertThat(KeyRing.of(List.of("alpha", KeyRing.hashKey("alpha"))).size())
                .isEqualTo(1);
    }

    @Test
    void emptyEntriesAreDropped() {
        assertThat(KeyRing.of(List.of("alpha", "", "   ")).size()).isEqualTo(1);
    }

    @Test
    void aHashedKeyIsNotCaseSensitive() {
        KeyRing ring = KeyRing.of(List.of(KeyRing.hashKey("alpha").toUpperCase(Locale.ROOT)));
        assertThat(ring.accepts("alpha")).isTrue();
    }

    @Test
    void nothingPresented() {
        KeyRing ring = KeyRing.of(List.of("alpha"));
        assertThat(ring.accepts(null)).isFalse();
        assertThat(ring.accepts("")).isFalse();
    }

    @Test
    void aGatewayWithNoKeysIsOpen() {
        assertThat(harness().client().get("/fleet/status").status()).isEqualTo(200);
    }

    @Test
    void aPlainKeyStillWorks() {
        Http client = harness("alpha").client();
        assertThat(client.get("/fleet/status").status()).isEqualTo(401);
        Http.Answer good = client.with(Services.API_KEY_HEADER, "alpha").get("/fleet/status");
        assertThat(good.status()).isEqualTo(200);
    }

    @Test
    void aHashedKeyWorksTheSame() {
        Http client = harness(KeyRing.hashKey("alpha")).client();
        assertThat(client.get("/fleet/status").status()).isEqualTo(401);
        Http.Answer good = client.with(Services.API_KEY_HEADER, "alpha").get("/fleet/status");
        assertThat(good.status()).isEqualTo(200);
        assertThat(client.with(Services.API_KEY_HEADER, KeyRing.hashKey("alpha"))
                        .get("/fleet/status")
                        .status())
                .isEqualTo(401);
    }

    @Test
    void theWrongKeyIsRefused() {
        Http.Answer response = harness(KeyRing.hashKey("alpha"))
                .client()
                .with(Services.API_KEY_HEADER, "beta")
                .get("/fleet/status");
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.header("www-authenticate")).isEqualTo(Services.API_KEY_HEADER);
    }

    @Test
    void theRingIsRebuiltWhenTheKeysChange() {
        Harness bench = harness("alpha");
        assertThat(bench.services.keyring().accepts("alpha")).isTrue();
        bench.services.apiKeys(Set.of("beta"));
        assertThat(bench.services.keyring().accepts("alpha")).isFalse();
        assertThat(bench.services.keyring().accepts("beta")).isTrue();
    }

    @Test
    void makingAKeyOnTheConsole() {
        Cli.Result result = Cli.invoke(tmp, "key", "new");
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        List<String> lines = result.stdout().lines().toList();
        assertThat(lines.get(0)).startsWith("key ");
        assertThat(lines.get(1)).contains(KeyRing.DIGEST_PREFIX);
    }

    @Test
    void makingSeveralKeys() {
        assertThat(Cli.invoke(tmp, "key", "new", "--count", "3").stdout().lines())
                .hasSize(6);
    }

    @Test
    void hashingAKeyOnTheConsole() {
        assertThat(Cli.invoke(tmp, "key", "hash", "alpha").stdout().strip()).isEqualTo(KeyRing.hashKey("alpha"));
    }

    @Test
    void checkingAKeyAgainstASiteWithNone() {
        Cli.Result result = Cli.invoke(tmp, "key", "check", "alpha");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("the API is open");
    }

    @Test
    void checkingAKeyThatIsOnTheRing() throws Exception {
        writeSite("[api]\nkeys = [\"" + KeyRing.hashKey("alpha") + "\"]\n");
        Cli.Result result = Cli.invoke(tmp, "key", "check", "alpha");
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("accepted");
        assertThat(result.stdout()).contains("all hashed");
    }

    @Test
    void checkingAKeyThatIsNot() throws Exception {
        writeSite("[api]\nkeys = [\"" + KeyRing.hashKey("alpha") + "\"]\n");
        Cli.Result result = Cli.invoke(tmp, "key", "check", "beta");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("not on the ring");
    }

    @Test
    void aSiteWithAPlainKeyIsToldToHashIt() throws Exception {
        writeSite("[api]\nkeys = [\"alpha\"]\n");
        Cli.Result result = Cli.invoke(tmp, "key", "check", "alpha");
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("run nozzlewright key hash");
    }
}
