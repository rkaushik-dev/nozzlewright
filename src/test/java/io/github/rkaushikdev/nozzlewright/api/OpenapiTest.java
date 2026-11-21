package io.github.rkaushikdev.nozzlewright.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.nozzlewright.Nozzlewright;
import io.github.rkaushikdev.nozzlewright.fleet.Registry;
import io.github.rkaushikdev.nozzlewright.settings.Settings;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** The published API document and the page that shows it, read off the service on a port of its own. */
class OpenapiTest {

    private static final ObjectMapper JSON = ApiJson.mapper();

    private static final HttpClient WIRE =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    private static Database database;
    private static ConfigurableApplicationContext app;
    private static URI base;

    @BeforeAll
    static void startTheService() {
        database = Database.inMemory();
        database.createAll();
        Services services = new Services(Settings.builder().siteId("bench").build(), database, new Registry("bench"));
        app = GatewayApplication.run(services, "--server.port=0");
        int port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
        base = URI.create("http://127.0.0.1:" + port);
    }

    @AfterAll
    static void stopTheService() {
        app.close();
        database.close();
    }

    static HttpResponse<String> fetch(URI where) throws IOException, InterruptedException {
        return WIRE.send(HttpRequest.newBuilder(where).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> fetch(String path) throws IOException, InterruptedException {
        return fetch(base.resolve(path));
    }

    static JsonNode document() throws IOException, InterruptedException {
        return JSON.readTree(fetch("/openapi.json").body());
    }

    @Test
    void theDocumentIsServed() throws Exception {
        HttpResponse<String> response = fetch("/openapi.json");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).get("openapi").asText()).startsWith("3.");
    }

    @Test
    void theDocumentCarriesTheVersion() throws Exception {
        assertThat(document().get("info").get("version").asText()).isEqualTo(Nozzlewright.VERSION);
    }

    @Test
    void theKeyHeaderIsDeclared() throws Exception {
        JsonNode scheme = document().get("components").get("securitySchemes").get(ApiDocument.SECURITY_SCHEME);
        assertThat(scheme.get("type").asText()).isEqualTo("apiKey");
        assertThat(scheme.get("name").asText()).isEqualTo(Services.API_KEY_HEADER);
        assertThat(scheme.get("in").asText()).isEqualTo("header");
    }

    @Test
    void theWholeApiAsksForTheKey() throws Exception {
        assertThat(document().get("security"))
                .isEqualTo(JSON.readTree("[{\"" + ApiDocument.SECURITY_SCHEME + "\": []}]"));
    }

    @Test
    void theDescriptionSaysWhatTheHeaderIs() throws Exception {
        assertThat(document().get("info").get("description").asText()).contains(Services.API_KEY_HEADER);
    }

    @Test
    void everyTagIsDescribed() throws Exception {
        JsonNode tags = document().get("tags");
        Set<String> described = new HashSet<>();
        tags.forEach(tag -> described.add(tag.get("name").asText()));
        assertThat(described).isEqualTo(ApiDocument.TAGS.keySet());
        tags.forEach(tag -> assertThat(tag.path("description").asText()).isNotEmpty());
    }

    @Test
    void theTagsCoverTheRoutes() throws Exception {
        JsonNode body = document();
        Set<String> described = new HashSet<>();
        body.get("tags").forEach(tag -> described.add(tag.get("name").asText()));
        Set<String> used = new HashSet<>();
        body.get("paths")
                .forEach(path ->
                        path.forEach(operation -> operation.path("tags").forEach(tag -> used.add(tag.asText()))));
        assertThat(described).containsAll(used);
    }

    @Test
    void theWallBoardIsNotInTheDocument() throws Exception {
        assertThat(document().get("paths").has("/board")).isFalse();
    }

    @Test
    void theDocumentIsBuiltOnce() throws Exception {
        JsonNode first = document();
        JsonNode second = document();
        assertThat(first).isEqualTo(second);
    }

    /**
     * The docs page answers, and it is pointed at the document.
     *
     * <p>{@code /docs} forwards to the stock viewer page, which takes where to find the document from
     * the start-up script it loads; the service writes that address in, so that is where it is read.
     */
    @Test
    void theDocsPageComesUp() throws Exception {
        HttpResponse<String> page = fetch("/docs");
        assertThat(page.statusCode()).isEqualTo(200);
        HttpResponse<String> setup = fetch(page.uri().resolve("swagger-initializer.js"));
        assertThat(setup.statusCode()).isEqualTo(200);
        assertThat(page.body() + setup.body()).contains("openapi.json");
    }
}
