package io.github.rkaushikdev.nozzlewright.api;

import io.swagger.v3.oas.models.OpenAPI;
import java.util.List;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The gateway HTTP service.
 *
 * <p>It is started with {@link #run}, handed the services object the gateway built or the API parts
 * made around one: Spring never builds a database of its own. Its data source, JPA and Flyway set up are switched off, because
 * with H2 on the class path and no data source of its own it would otherwise build an embedded database
 * and migrate that. Spring's logging set up is switched off too, so the gateway's own log configuration
 * stands. The routes, the key check and the error mapping are the {@link Api} parts, registered as
 * they are rather than found by scanning, so the service and the tests run the same objects.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(
        exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class
        })
public class GatewayApplication {

    /**
     * Start the service over {@code services}, with Spring arguments such as {@code --server.port=0}.
     *
     * <p>The database inside {@code services} belongs to the caller, who closes it after closing the
     * returned context.
     */
    public static ConfigurableApplicationContext run(Services services, String... args) {
        return run(Api.of(services), List.of(), args);
    }

    /** Serve exactly these API parts, with Spring arguments such as {@code --server.port=0}. */
    public static ConfigurableApplicationContext run(Api api, String... args) {
        return run(api, List.of(), args);
    }

    /**
     * Serve these API parts with more objects beside them, with Spring arguments such as {@code
     * --server.port=0}.
     *
     * <p>Each extra object is registered as it is: a route class answers its paths, and a lifecycle
     * object starts with the service and stops with it. That is how the gateway's own start and stop,
     * and its metrics page, ride along with the service without the API knowing about them.
     */
    public static ConfigurableApplicationContext run(Api api, List<?> extras, String... args) {
        System.setProperty(LoggingSystem.SYSTEM_PROPERTY, LoggingSystem.NONE);
        List<Object> beside = List.copyOf(extras);
        return new SpringApplicationBuilder(GatewayApplication.class)
                .initializers(context -> register(context.getBeanFactory(), api, beside))
                .run(args);
    }

    private static void register(ConfigurableListableBeanFactory beans, Api api, List<Object> extras) {
        beans.registerSingleton("services", api.services());
        beans.registerSingleton("api", api);
        beans.registerSingleton("apiErrors", api.errors());
        for (Object part : api.routes()) {
            beans.registerSingleton(beanName(part), part);
        }
        for (Object part : extras) {
            beans.registerSingleton(beanName(part), part);
        }
    }

    private static String beanName(Object part) {
        String name = part.getClass().getSimpleName();
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer apiJson() {
        return ApiJson::configure;
    }

    @Bean
    OpenAPI apiDocument() {
        return ApiDocument.document();
    }

    @Bean
    WebMvcConfigurer apiWeb(Api api) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(api.keyCheck());
            }

            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.addAll(api.resolvers());
            }
        };
    }
}
