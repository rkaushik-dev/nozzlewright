package io.github.rkaushikdev.nozzlewright.cli;

import static io.github.rkaushikdev.nozzlewright.cli.Cli.invoke;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.fleet.CostModel;
import io.github.rkaushikdev.nozzlewright.siteconfig.SiteConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliCostTest {

    static final String CARTON = "LABEL carton 32\n"
            + "FIELD brand TEXT HALVEX\n"
            + "FIELD serial COUNTER width=6\n"
            + "TEXT line1 0,0 brand\n"
            + "BARCODE code 0,10 serial height=20 module=2 human=0\n"
            + "END\n";

    static final String PRICED =
            """

            [site]
            id = "bremen-2"

            [costing]
            cartridge_price = 84.0
            part_number = "SLX-2580"
            ink = "MEK"
            cartridge_ml = 42.0
            drop_volume_pl = 42.0
            purges_per_thousand = 2.0
            """;

    @TempDir
    Path tmp;

    Path write(String name, String text) throws Exception {
        Path path = tmp.resolve(name);
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return path;
    }

    Path site() throws Exception {
        return site(PRICED);
    }

    Path site(String text) throws Exception {
        return write(SiteConfig.SITE_FILE, text);
    }

    static JsonNode json(String text) throws Exception {
        return new ObjectMapper().readTree(text);
    }

    @Test
    void theSiteFileCarriesACostModel() throws Exception {
        site();
        CostModel model = SiteConfig.load(SiteConfig.sitePath(tmp)).costing().orElseThrow();
        assertThat(model.cartridgePrice()).isEqualTo(84.0);
        assertThat(model.spec().partNumber()).isEqualTo("SLX-2580");
        assertThat(model.purgesPerThousand()).isEqualTo(2.0);
    }

    @Test
    void aSiteFileWithNoCosting() throws Exception {
        site("[site]\nid = \"bremen-2\"\n");
        assertThat(SiteConfig.load(SiteConfig.sitePath(tmp)).costing()).isEmpty();
    }

    @Test
    void aCostingBlockWithNoPrice() throws Exception {
        site("[costing]\ncartridge_ml = 42.0\n");
        assertThatThrownBy(() -> SiteConfig.load(SiteConfig.sitePath(tmp)))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("needs a cartridge_price");
    }

    @Test
    void anInkNobodySells() throws Exception {
        site("[costing]\ncartridge_price = 84.0\nink = \"GRAVY\"\n");
        assertThatThrownBy(() -> SiteConfig.load(SiteConfig.sitePath(tmp)))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must be one of");
    }

    @Test
    void aPriceOfNothing() throws Exception {
        site("[costing]\ncartridge_price = 0.0\n");
        assertThatThrownBy(() -> SiteConfig.load(SiteConfig.sitePath(tmp)))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    void theCostOfALabel() throws Exception {
        site();
        Path source = write("carton.lbl", CARTON);
        Cli.Result result = invoke(tmp, "label", "cost", source.toString());
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("label=carton").contains("drops_per_code=");
    }

    @Test
    void theCostAsJson() throws Exception {
        site();
        Path source = write("carton.lbl", CARTON);
        JsonNode body =
                json(invoke(tmp, "label", "cost", source.toString(), "--json").stdout());
        assertThat(body.get("label").asText()).isEqualTo("carton");
        assertThat(body.get("drops_per_code").asDouble()).isPositive();
        assertThat(body.get("cost_per_thousand").asDouble()).isPositive();
        assertThat(body.get("purge_share_pct").asDouble()).isPositive();
        assertThat(body.get("codes_per_cartridge").asDouble()).isPositive();
    }

    @Test
    void aPriceOnTheCommandLine() throws Exception {
        Path source = write("carton.lbl", CARTON);
        JsonNode body = json(invoke(tmp, "label", "cost", source.toString(), "--price", "84", "--json")
                .stdout());
        assertThat(body.get("cost_per_thousand").asDouble()).isPositive();
        assertThat(body.get("purge_share_pct").asDouble()).isZero();
    }

    @Test
    void aPriceOnTheCommandLineBeatsTheFile() throws Exception {
        site();
        Path source = write("carton.lbl", CARTON);
        JsonNode cheap =
                json(invoke(tmp, "label", "cost", source.toString(), "--json").stdout());
        JsonNode dear = json(invoke(tmp, "label", "cost", source.toString(), "--price", "168", "--json")
                .stdout());
        assertThat(dear.get("cost_per_thousand").asDouble())
                .isGreaterThan(cheap.get("cost_per_thousand").asDouble());
    }

    @Test
    void aPurgeRateOnTheCommandLine() throws Exception {
        site();
        Path source = write("carton.lbl", CARTON);
        JsonNode body = json(invoke(tmp, "label", "cost", source.toString(), "--purges", "0", "--json")
                .stdout());
        assertThat(body.get("purge_share_pct").asDouble()).isZero();
    }

    @Test
    void noPriceAnywhere() throws Exception {
        Path source = write("carton.lbl", CARTON);
        Cli.Result result = invoke(tmp, "label", "cost", source.toString());
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("no cartridge price");
    }

    @Test
    void aBiggerLabelCostsMore() throws Exception {
        site();
        Path small = write("small.lbl", "LABEL small 32\nFIELD a TEXT X\nTEXT t 0,0 a\nEND\n");
        Path big = write("big.lbl", CARTON);
        JsonNode smallCost =
                json(invoke(tmp, "label", "cost", small.toString(), "--json").stdout());
        JsonNode bigCost =
                json(invoke(tmp, "label", "cost", big.toString(), "--json").stdout());
        assertThat(bigCost.get("drops_per_code").asDouble())
                .isGreaterThan(smallCost.get("drops_per_code").asDouble());
        assertThat(bigCost.get("codes_per_cartridge").asDouble())
                .isLessThan(smallCost.get("codes_per_cartridge").asDouble());
    }

    @Test
    void aLabelWithAPromptInIt() throws Exception {
        site();
        Path source = write("asked.lbl", "LABEL asked 32\nFIELD lot PROMPT max=8\nTEXT l 0,0 lot\nEND\n");
        JsonNode body =
                json(invoke(tmp, "label", "cost", source.toString(), "--json").stdout());
        assertThat(body.get("drops_per_code").asDouble()).isPositive();
    }
}
