package io.github.rkaushikdev.nozzlewright.api;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import io.github.rkaushikdev.nozzlewright.CommandRejected;
import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.LinkClosed;
import io.github.rkaushikdev.nozzlewright.NotAuthenticated;
import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.PermissionDenied;
import io.github.rkaushikdev.nozzlewright.PrinterBusy;
import io.github.rkaushikdev.nozzlewright.ProtocolError;
import io.github.rkaushikdev.nozzlewright.TransportError;
import io.github.rkaushikdev.nozzlewright.telemetry.EventLog;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Every error the API answers with, in one place.
 *
 * <p>Every error the domain raises has a status here and nowhere else. A handler that caught a printer
 * error to turn it into a response would get it slightly different from the next one, so the mapping
 * lives here and handlers let errors through. A domain error is answered as {@code {"error": <type>,
 * "detail": <message>, "printer_id": <the machine in the path, or empty>}}; a refusal the HTTP layer
 * makes itself as {@code {"detail": <message>}}; and a request that does not fit its route as status
 * 422 with {@code {"detail": [<one entry per problem>]}}.
 */
@RestControllerAdvice
public class ApiErrors {

    /**
     * How a domain error becomes an HTTP status. Tried in order and the first match wins, so the
     * specific types come before their bases.
     */
    public static final Map<Class<? extends Throwable>, Integer> STATUS_FOR = statuses();

    private static final EventLog LOG = EventLog.get("nozzlewright.api");

    private static Map<Class<? extends Throwable>, Integer> statuses() {
        Map<Class<? extends Throwable>, Integer> order = new LinkedHashMap<>();
        order.put(PermissionDenied.class, 403);
        order.put(NotAuthenticated.class, 401);
        order.put(PrinterBusy.class, 409);
        order.put(CommandRejected.class, 502);
        order.put(LinkClosed.class, 504);
        order.put(TransportError.class, 502);
        order.put(ProtocolError.class, 502);
        order.put(ConfigError.class, 400);
        order.put(LabelError.class, 400);
        order.put(NozzlewrightError.class, 500);
        return Collections.unmodifiableMap(order);
    }

    /** The HTTP status one error maps to; 500 for anything the table does not name. */
    public static int statusFor(Throwable error) {
        for (Map.Entry<Class<? extends Throwable>, Integer> entry : STATUS_FOR.entrySet()) {
            if (entry.getKey().isInstance(error)) {
                return entry.getValue();
            }
        }
        return 500;
    }

    /** A domain error, with the machine the request named. */
    @ExceptionHandler(NozzlewrightError.class)
    public ResponseEntity<Map<String, Object>> domain(NozzlewrightError error, HttpServletRequest request) {
        int code = statusFor(error);
        String printerId = pathVariable(request, "printer_id");
        if (code >= 500) {
            LOG.warning(
                    "request failed",
                    "path",
                    request.getRequestURI(),
                    "printer",
                    printerId,
                    "error",
                    String.valueOf(error.getMessage()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error.getClass().getSimpleName());
        body.put("detail", String.valueOf(error.getMessage()));
        body.put("printer_id", printerId);
        return ResponseEntity.status(code)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /** A refusal the HTTP layer made itself. */
    @ExceptionHandler(ApiProblem.class)
    public ResponseEntity<Map<String, Object>> problem(ApiProblem problem) {
        HttpHeaders headers = new HttpHeaders();
        problem.headers().forEach(headers::set);
        return ResponseEntity.status(problem.status())
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(detail(problem.detail()));
    }

    /** A request that did not fit its route. */
    @ExceptionHandler(RequestRejected.class)
    public ResponseEntity<Map<String, Object>> rejected(RequestRejected rejected) {
        List<Map<String, Object>> problems = new ArrayList<>();
        for (RequestRejected.Problem problem : rejected.problems()) {
            problems.add(problem.asMap());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", problems);
        return ResponseEntity.status(422)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /** A body that is not JSON, or JSON of the wrong shape. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException failure) {
        return rejected(new RequestRejected(List.of(problemIn(failure))));
    }

    /** A body whose fields broke their limits. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException failure) {
        List<RequestRejected.Problem> problems = new ArrayList<>();
        for (FieldError error : failure.getBindingResult().getFieldErrors()) {
            List<Object> loc = new ArrayList<>();
            loc.add("body");
            loc.add(snake(error.getField()));
            String message = String.valueOf(error.getDefaultMessage());
            problems.add(new RequestRejected.Problem(typeOf(error.getCode(), message), loc, message));
        }
        return rejected(new RequestRejected(problems));
    }

    /** A query parameter the route needs and did not get. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> missing(MissingServletRequestParameterException failure) {
        return rejected(RequestRejected.one("missing", "query", failure.getParameterName(), "Field required"));
    }

    /** A query or path value that is not the type the route takes. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> mismatched(MethodArgumentTypeMismatchException failure) {
        String where = failure.getParameter().hasParameterAnnotation(PathVariable.class) ? "path" : "query";
        Class<?> wanted = failure.getRequiredType();
        RequestRejected rejected;
        if (wanted == Boolean.class || wanted == boolean.class) {
            rejected = RequestRejected.one(
                    "bool_parsing",
                    where,
                    failure.getName(),
                    "Input should be a valid boolean, unable to interpret input");
        } else {
            rejected = RequestRejected.one(
                    "int_parsing",
                    where,
                    failure.getName(),
                    "Input should be a valid integer, unable to parse string as an integer");
        }
        return rejected(rejected);
    }

    /** A body sent as something other than JSON. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> notJson(HttpMediaTypeNotSupportedException failure) {
        return rejected(new RequestRejected(List.of(new RequestRejected.Problem(
                "model_attributes_type",
                List.of("body"),
                "Input should be a valid dictionary or object to extract fields from"))));
    }

    /** A path nothing answers. */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> notFound(Exception failure) {
        return ResponseEntity.status(404)
                .contentType(MediaType.APPLICATION_JSON)
                .body(detail("Not Found"));
    }

    /** A path that is there, asked with the wrong method. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> wrongMethod(HttpRequestMethodNotSupportedException failure) {
        return ResponseEntity.status(405)
                .contentType(MediaType.APPLICATION_JSON)
                .body(detail("Method Not Allowed"));
    }

    /** Anything else: logged, and answered as a plain server error. */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> unexpected(RuntimeException failure, HttpServletRequest request) {
        LOG.exception("request crashed", failure, "path", request.getRequestURI());
        return ResponseEntity.status(500).contentType(MediaType.TEXT_PLAIN).body("Internal Server Error");
    }

    private static Map<String, Object> detail(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", text);
        return body;
    }

    private static String pathVariable(HttpServletRequest request, String name) {
        Object found = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (found instanceof Map<?, ?> variables && variables.get(name) instanceof String value) {
            return value;
        }
        return "";
    }

    private static RequestRejected.Problem problemIn(HttpMessageNotReadableException failure) {
        Throwable cause = failure.getCause();
        if (cause instanceof JsonParseException) {
            return new RequestRejected.Problem("json_invalid", List.of("body"), "JSON decode error");
        }
        if (cause instanceof MismatchedInputException mismatch
                && !mismatch.getPath().isEmpty()) {
            List<Object> loc = new ArrayList<>();
            loc.add("body");
            for (JsonMappingException.Reference step : mismatch.getPath()) {
                loc.add(step.getFieldName() != null ? step.getFieldName() : step.getIndex());
            }
            return new RequestRejected.Problem(
                    "type_error", loc, "Input should be a valid " + kind(mismatch.getTargetType()));
        }
        if (cause instanceof JsonMappingException) {
            return new RequestRejected.Problem(
                    "model_attributes_type",
                    List.of("body"),
                    "Input should be a valid dictionary or object to extract fields from");
        }
        return new RequestRejected.Problem("missing", List.of("body"), "Field required");
    }

    private static String kind(Class<?> type) {
        if (type == null) {
            return "value";
        }
        if (type == String.class) {
            return "string";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        }
        if (type == Integer.class || type == int.class || type == Long.class || type == long.class) {
            return "integer";
        }
        if (List.class.isAssignableFrom(type)) {
            return "list";
        }
        return type.getSimpleName().toLowerCase(Locale.ROOT);
    }

    private static String typeOf(String code, String message) {
        if ("NotNull".equals(code)) {
            return "missing";
        }
        if ("Size".equals(code)) {
            return message.contains("at least") ? "string_too_short" : "string_too_long";
        }
        return "value_error";
    }

    /** A Java field name as the wire spells it: {@code printerId} becomes {@code printer_id}. */
    static String snake(String field) {
        return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
