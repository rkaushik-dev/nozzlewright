package io.github.rkaushikdev.nozzlewright.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The door every {@link RequiresKey} route goes through.
 *
 * <p>It runs before Spring reads a single argument, so a caller without a key is told so before
 * anybody looks at what they sent: a 401 beats a 422. A gateway with no keys configured lets
 * everybody through. A refusal names the header in {@code WWW-Authenticate}, so a client knows what
 * it was meant to send.
 */
public final class KeyCheck implements HandlerInterceptor {

    private final Services services;

    /** A door checking keys against what {@code services} holds at the time of each request. */
    public KeyCheck(Services services) {
        this.services = services;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method && needsKey(method)) {
            check(request.getHeader(Services.API_KEY_HEADER));
        }
        return true;
    }

    /**
     * Refuse a presented key unless it is accepted, when a key is needed at all.
     *
     * @throws ApiProblem with status 401 when it is not
     */
    void check(String key) {
        if (!services.authRequired()) {
            return;
        }
        if (!services.keyring().accepts(key)) {
            throw new ApiProblem(
                    401, "a valid " + Services.API_KEY_HEADER + " header is required", Services.keyChallenge());
        }
    }

    private static boolean needsKey(HandlerMethod method) {
        return method.hasMethodAnnotation(RequiresKey.class)
                || method.getBeanType().isAnnotationPresent(RequiresKey.class);
    }
}
