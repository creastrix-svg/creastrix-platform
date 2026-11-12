package com.creastrix.platform.authentication;

import java.util.Arrays;
import java.util.List;
import java.io.IOException;
import java.nio.file.Path;
import com.creastrix.platform.accountcart.assets.PrivateCartAssetStore;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Only the synthetic, loopback account-cart process may opt into these reads and migrations. */
@Configuration(proxyBeanMethods = false)
@Profile(LocalAccountCartConfiguration.PROFILE)
class LocalAccountCartConfiguration {
    static final String PROFILE = "account-cart-local";
    static final String ORIGIN = "http://localhost:4317";
    static final String ISSUER = "http://127.0.0.1:8192/";
    static final String SUBJECT = "cart-synthetic-buyer";
    static final String DATABASE = "jdbc:postgresql://127.0.0.1:55489/creastrix_account_cart_local";
    static final String ROLE = "account_cart_local_app";
    static final String MIGRATIONS = "classpath:db/migration,classpath:db/account-cart-local";

    @Bean
    static BeanFactoryPostProcessor accountCartIsolation(Environment env) {
        return factory -> verify(env);
    }

    static void verify(Environment env) {
        if (!List.of(PROFILE).equals(Arrays.asList(env.getActiveProfiles()))
                || !"127.0.0.1".equals(env.getProperty("server.address"))
                || !"8191".equals(env.getProperty("server.port"))
                || !"none".equals(env.getProperty("server.forward-headers-strategy"))
                || !"true".equals(env.getProperty("creastrix.auth.enabled"))
                || !"true".equals(env.getProperty("creastrix.auth.existing-only"))
                || !ISSUER.equals(env.getProperty("creastrix.auth.issuer"))
                || !ORIGIN.equals(env.getProperty("creastrix.auth.browser-origin"))
                || !DATABASE.equals(env.getProperty("spring.datasource.url"))
                || !ROLE.equals(env.getProperty("spring.datasource.username"))
                || env.containsProperty("spring.flyway.url") || env.containsProperty("spring.flyway.user")
                || env.containsProperty("spring.flyway.password") || env.containsProperty("spring.flyway.driver-class-name")
                || !Arrays.equals(Binder.get(env).bind("spring.flyway.locations", String[].class)
                        .orElse(new String[0]), MIGRATIONS.split(","))) {
            throw new IllegalStateException("Account-cart reads require the isolated local synthetic runtime");
        }
    }

    @Bean
    PrivateCartAssetStore accountCartFiles(Environment env) throws IOException {
        return PrivateCartAssetStore.open(Path.of(env.getRequiredProperty("CART_LOCAL_ASSET_ROOT")));
    }

    @Bean
    @FlywayDataSource
    DriverManagerDataSource localAccountCartDataSource(Environment env) {
        // Keep JDBC and Flyway on precisely the same dedicated database. Do not
        // inherit connection pool URLs or the credentials of the account pilot.
        var password = env.getRequiredProperty("CART_LOCAL_DB_PASSWORD");
        if (password.isBlank()) throw new IllegalStateException("Dedicated local database password required");
        return new DriverManagerDataSource(DATABASE, ROLE, password);
    }
}
