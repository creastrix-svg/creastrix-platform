package com.creastrix.platform.localcatalog;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JndiDataSourceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalCatalogIsolationTest {
    private static final String CATALOG_URL = "jdbc:postgresql://127.0.0.1:5433/creastrix_local_catalog";

    private MockEnvironment local() {
        var env = new MockEnvironment()
                .withProperty("server.address", "127.0.0.1")
                .withProperty("spring.datasource.url", CATALOG_URL)
                .withProperty("CREASTRIX_CATALOG_DB_URL", CATALOG_URL)
                .withProperty("CREASTRIX_CATALOG_DB_USERNAME", "catalog-test-user")
                .withProperty("CREASTRIX_CATALOG_DB_PASSWORD", "catalog-test-password")
                .withProperty("spring.flyway.schemas", "local_catalog")
                .withProperty("spring.flyway.default-schema", "local_catalog")
                .withProperty("spring.flyway.locations", "classpath:db/local-catalog");
        env.setActiveProfiles("local-catalog");
        return env;
    }

    @Test
    void acceptsOnlyTheLocalProfile() {
        var env = local();
        assertThatCode(() -> LocalCatalogConfiguration.verifyIsolation(env)).doesNotThrowAnyException();
        env.setActiveProfiles("local-catalog", "auth-local");
        assertThatThrownBy(() -> LocalCatalogConfiguration.verifyIsolation(env))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "server.address, 0.0.0.0",
            "creastrix.auth.enabled, true",
            "spring.datasource.url, jdbc:postgresql://127.0.0.1/creastrix_platform",
            "spring.datasource.url, jdbc:postgresql://db.invalid/creastrix_local_catalog",
            "CREASTRIX_CATALOG_DB_URL, jdbc:postgresql://db.invalid/creastrix_local_catalog",
            "spring.flyway.url, jdbc:postgresql://127.0.0.1/another_database",
            "spring.flyway.user, unused",
            "spring.flyway.password, unused",
            "spring.flyway.driver-class-name, unused",
            "spring.flyway.schemas, public",
            "spring.flyway.default-schema, public",
            "spring.flyway.locations, classpath:db/migration"
    })
    void refusesUnsafeLaunchBeforeDatabaseInitialization(String key, String value) {
        var env = local().withProperty(key, value);
        assertThatThrownBy(() -> LocalCatalogConfiguration.verifyIsolation(env))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREASTRIX_CATALOG_DB_USERNAME", "CREASTRIX_CATALOG_DB_PASSWORD"})
    void requiresCatalogCredentialsWhenCreatingTheDataSource(String property) {
        var env = local().withProperty(property, "");
        assertThatThrownBy(() -> new LocalCatalogConfiguration().localCatalogDataSource(env))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void applicationAndFlywayUseOneValidatedCatalogDataSourceWithoutPoolOrConnectionOverrides() {
        var otherUrl = "jdbc:postgresql://127.0.0.1/unused_test";
        var otherFlywayDetails = mock(FlywayConnectionDetails.class);
        when(otherFlywayDetails.getJdbcUrl()).thenReturn(otherUrl);

        new WebApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class, JndiDataSourceAutoConfiguration.class,
                        FlywayAutoConfiguration.class, SecurityAutoConfiguration.class,
                        ServletWebSecurityAutoConfiguration.class))
                .withUserConfiguration(LocalCatalogConfiguration.class)
                .withPropertyValues("spring.profiles.active=local-catalog", "CREASTRIX_CATALOG_DB_URL=" + CATALOG_URL,
                        "CREASTRIX_CATALOG_DB_USERNAME=catalog-test-user", "CREASTRIX_CATALOG_DB_PASSWORD=catalog-test-password",
                        "spring.datasource.username=unused", "spring.datasource.password=unused",
                        "spring.datasource.jndi-name=java:comp/env/unused", "spring.datasource.hikari.jdbc-url=" + otherUrl,
                        "spring.flyway.jdbc-properties.ApplicationName=unused")
                .withBean(FlywayConnectionDetails.class, () -> otherFlywayDetails)
                // Inspect the configured Flyway instance without opening any database connections.
                .withBean(FlywayMigrationStrategy.class, () -> flyway -> {})
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(DataSource.class);
                    var source = context.getBean(DriverManagerDataSource.class);
                    assertThat(source.getUrl()).isEqualTo(CATALOG_URL);
                    assertThat(source.getUsername()).isEqualTo("catalog-test-user");
                    assertThat(source.getPassword()).isEqualTo("catalog-test-password");
                    assertThat(source.getConnectionProperties()).isNull();
                    assertThat(context.getBean(JdbcTemplate.class).getDataSource()).isSameAs(source);
                    assertThat(context.getBean(Flyway.class).getConfiguration().getDataSource()).isSameAs(source);
                });
    }
}
