package io.github.rkaushikdev.nozzlewright.api;

import java.util.List;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;

/**
 * The gateway's HTTP layer, assembled around one services object: the routes, the key check in front
 * of them, the readers for paging and time windows, and the error mapping.
 *
 * <p>Small on purpose. The handheld does most of its work over the radio, straight to the machine, and
 * only comes here for the things that need the whole site: what is running, what is in alarm, and
 * what a label looked like last Tuesday. {@link GatewayApplication} serves exactly these parts; the
 * tests drive the same parts without a server.
 *
 * @param services what the routes reach for
 * @param routes the route objects, one per area of the API
 * @param keyCheck the door in front of every keyed route
 * @param resolvers hand routes their page of a listing and their window of history
 * @param errors turns every failure into its answer
 */
public record Api(
        Services services,
        List<Object> routes,
        KeyCheck keyCheck,
        List<HandlerMethodArgumentResolver> resolvers,
        ApiErrors errors) {

    /** Copies the lists. */
    public Api {
        routes = List.copyOf(routes);
        resolvers = List.copyOf(resolvers);
    }

    /** Every part of the API, built around {@code services}. */
    public static Api of(Services services) {
        return new Api(
                services,
                List.of(
                        new PrinterRoutes(services),
                        new LabelRoutes(services),
                        new FleetRoutes(services),
                        new HistoryRoutes(services),
                        new OpsRoutes(services)),
                new KeyCheck(services),
                List.of(new PagingResolver(), new WindowResolver()),
                new ApiErrors());
    }
}
