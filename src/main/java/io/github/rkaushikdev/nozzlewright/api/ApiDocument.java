package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.Nozzlewright;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The document the interactive docs page is built from, served at {@code /openapi.json}.
 *
 * <p>A generated document is missing the two things that matter to somebody meeting this API for the
 * first time. It does not know the key header exists, so the docs page would have nowhere to put a key
 * and every request from it would come back 401 with nothing to say what to do about it: declaring the
 * scheme puts an authorise button on the page. And the tags come out as bare words, when a support
 * engineer opening this at eleven at night wants to know that history is cheap and reads the
 * database, and that the nozzle test stops the print for a second: one sentence per tag saves a phone
 * call.
 */
public final class ApiDocument {

    /** The application's title. */
    public static final String TITLE = "Halvex Mobile gateway";

    /** One line under the title. */
    public static final String SUMMARY = "Fleet control for Halvex coding printers";

    /** The name the security scheme is referred to by inside the document. */
    public static final String SECURITY_SCHEME = "siteKey";

    /** One sentence per group of endpoints, by tag name, in the order they should be read. */
    public static final Map<String, String> TAGS = tags();

    private ApiDocument() {}

    private static Map<String, String> tags() {
        Map<String, String> made = new LinkedHashMap<>();
        made.put(
                "fleet",
                "The site as a whole. Reads the last poll rather than the machines, so it is cheap and safe to refresh.");
        made.put(
                "printers",
                "One machine at a time. These talk to the machine, so they are as slow as the link is and a nozzle"
                        + " test stops the print while it runs.");
        made.put(
                "labels",
                "The site label store. Everything is versioned and nothing is ever overwritten, so a revision can"
                        + " always be compared or gone back to.");
        made.put("history", "What was written down. Reads the database, never a machine, and every listing is paged.");
        made.put(
                "ops",
                "What a support engineer needs on a phone call. All reads, and the doctor parses every stored label"
                        + " so it is not a dashboard refresh.");
        return Collections.unmodifiableMap(made);
    }

    /** The document's frame: title, version, description, tags and the key scheme everything uses. */
    public static OpenAPI document() {
        OpenAPI document = new OpenAPI()
                .info(new Info()
                        .title(TITLE)
                        .version(Nozzlewright.VERSION)
                        .summary(SUMMARY)
                        .description("Fleet control for Halvex coding printers.\n\n"
                                + "Every endpoint but the wall board takes an API key in the `"
                                + Services.API_KEY_HEADER
                                + "` header, on a gateway that has any keys configured. A gateway with none answers"
                                + " anybody, which is deliberate and is reported by `/health`."))
                .components(new Components()
                        .addSecuritySchemes(
                                SECURITY_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name(Services.API_KEY_HEADER)
                                        .description("A site key. Ask whoever commissioned the gateway.")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
        TAGS.forEach(
                (name, description) -> document.addTagsItem(new Tag().name(name).description(description)));
        return document;
    }
}
