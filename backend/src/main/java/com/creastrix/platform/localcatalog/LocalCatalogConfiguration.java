package com.creastrix.platform.localcatalog;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

/** The storefront read service is opt-in and cannot share the account pilot's listener or migrations. */
@Configuration(proxyBeanMethods = false)
@Profile("local-catalog")
class LocalCatalogConfiguration {
    static final String PRODUCTS_PATH = "/api/local-catalog/products";
    private static final Pattern LOCAL_DATABASE = Pattern.compile(
            "jdbc:postgresql://127\\.0\\.0\\.1(?::[0-9]{1,5})?/creastrix_local_catalog(?:_test)?");

    @Bean
    static BeanFactoryPostProcessor localCatalogStartupGuard(Environment environment) {
        // Fail before creating the datasource, running Flyway or opening the HTTP listener.
        return factory -> verifyIsolation(environment);
    }

    static void verifyIsolation(Environment env) {
        checkedCatalogUrl(env);
        if (!Arrays.asList(env.getActiveProfiles()).equals(List.of("local-catalog"))
                || !"false".equalsIgnoreCase(env.getProperty("creastrix.auth.enabled", "false"))
                || !"127.0.0.1".equals(env.getProperty("server.address"))
                || env.containsProperty("spring.flyway.url")
                || env.containsProperty("spring.flyway.user")
                || env.containsProperty("spring.flyway.password")
                || env.containsProperty("spring.flyway.driver-class-name")
                || !"local_catalog".equals(env.getProperty("spring.flyway.default-schema"))
                || !singleSetting(env, "spring.flyway.schemas", "local_catalog")
                || !singleSetting(env, "spring.flyway.locations", "classpath:db/local-catalog")) {
            // Connection strings can contain secrets. Never include an offending value here.
            throw new IllegalStateException("Local catalog requires its isolated profile, loopback listener and dedicated database.");
        }
    }

    @Bean
    @FlywayDataSource
    DriverManagerDataSource localCatalogDataSource(Environment env) {
        // Both JDBC and Flyway use this one source; no pool or driver properties are rebound onto it.
        String url = checkedCatalogUrl(env);
        String username = env.getProperty("CREASTRIX_CATALOG_DB_USERNAME", "");
        String password = env.getProperty("CREASTRIX_CATALOG_DB_PASSWORD", "");
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("Local catalog requires dedicated database credentials.");
        }
        return new DriverManagerDataSource(url, username, password);
    }

    private static String checkedCatalogUrl(Environment env) {
        String url = env.getProperty("CREASTRIX_CATALOG_DB_URL", "");
        if (!LOCAL_DATABASE.matcher(url).matches() || !url.equals(env.getProperty("spring.datasource.url"))) {
            throw new IllegalStateException("Local catalog requires its dedicated loopback database URL.");
        }
        return url;
    }

    private static boolean singleSetting(Environment env, String property, String expected) {
        String[] values = Binder.get(env).bind(property, String[].class).orElse(new String[0]);
        return Arrays.equals(values, new String[] {expected});
    }

    @Bean
    @Order(1)
    SecurityFilterChain localCatalogReadAccess(HttpSecurity http) throws Exception {
        // This profile exposes the buyer feed and published derivative reads, never an upload route.
        var paths = PathPatternRequestMatcher.withDefaults();
        return http.securityMatcher(new OrRequestMatcher(paths.matcher(HttpMethod.GET, PRODUCTS_PATH),
                        paths.matcher(HttpMethod.GET, "/api/local-catalog/media/{publication}/{rendition}"),
                        paths.matcher(HttpMethod.HEAD, "/api/local-catalog/media/{publication}/thumb.jpg"),
                        paths.matcher(HttpMethod.HEAD, "/api/local-catalog/media/{publication}/detail.jpg")))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }
}
