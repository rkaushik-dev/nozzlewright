package io.github.rkaushikdev.nozzlewright.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * A client for the API's parts, without a server: what the gateway would answer over HTTP, for one
 * services object, read the same way every time.
 */
final class Http {

    static final ObjectMapper JSON = ApiJson.mapper();

    /** One answer: its status, headers and body. */
    record Answer(int status, Map<String, String> headers, String text) {

        /** The body read as JSON. */
        JsonNode json() {
            try {
                return JSON.readTree(text);
            } catch (JsonProcessingException error) {
                throw new AssertionError("the body is not JSON: " + text, error);
            }
        }

        /** One header, empty when it was not sent. */
        String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse("");
        }
    }

    private final MockMvc mvc;
    private final Map<String, String> headers = new LinkedHashMap<>();

    private Http(MockMvc mvc) {
        this.mvc = mvc;
    }

    /** A client of the API built around {@code services}, as the gateway would build it. */
    static Http of(Services services) {
        Api api = Api.of(services);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(api.routes().toArray())
                .setControllerAdvice(api.errors())
                .addInterceptors(api.keyCheck())
                .setCustomArgumentResolvers(api.paging())
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter(JSON))
                .build();
        return new Http(mvc);
    }

    /** The same client sending this header on every request. */
    Http with(String name, String value) {
        headers.put(name, value);
        return this;
    }

    /** GET a path, with query parameters as name, value pairs. */
    Answer get(String path, String... query) {
        return send(MockMvcRequestBuilders.get(path), query);
    }

    /** POST a path with no body, with query parameters as name, value pairs. */
    Answer post(String path, String... query) {
        return send(MockMvcRequestBuilders.post(path), query);
    }

    /** POST a JSON body. */
    Answer postJson(String path, Object body) {
        return send(json(MockMvcRequestBuilders.post(path), body));
    }

    /** PUT a JSON body. */
    Answer putJson(String path, Object body) {
        return send(json(MockMvcRequestBuilders.put(path), body));
    }

    /** DELETE a path. */
    Answer delete(String path) {
        return send(MockMvcRequestBuilders.delete(path));
    }

    private Answer send(MockHttpServletRequestBuilder request, String... query) {
        for (int index = 0; index < query.length; index += 2) {
            request.queryParam(query[index], query[index + 1]);
        }
        headers.forEach(request::header);
        try {
            MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
            Map<String, String> sent = new LinkedHashMap<>();
            for (String name : response.getHeaderNames()) {
                sent.put(name, response.getHeader(name));
            }
            return new Answer(response.getStatus(), sent, response.getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new AssertionError("the request failed outright", error);
        }
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body) {
        try {
            return request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        } catch (JsonProcessingException error) {
            throw new AssertionError(error);
        }
    }
}
