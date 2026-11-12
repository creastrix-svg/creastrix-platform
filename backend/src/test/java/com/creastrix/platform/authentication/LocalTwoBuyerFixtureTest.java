package com.creastrix.platform.authentication;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class LocalTwoBuyerFixtureTest {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("creastrix_account_cart_local").withUsername("account_cart_local_app")
            .withCreateContainerCmdModifier(c -> c.getHostConfig().withNetworkMode("bridge").withPublishAllPorts(false)
                    .withPortBindings(new Ports(ExposedPort.tcp(5432), Ports.Binding.bindIp("127.0.0.1"))));
    private static final UUID A = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID B = UUID.fromString("10000000-0000-4000-8000-000000000003");

    private static String fixture(String name) throws Exception {
        return new ClassPathResource("account-cart-local/" + name + ".sql").getContentAsString(StandardCharsets.UTF_8);
    }

    @Test void optionalSecondBuyerHasPrivateUnprivilegedStateAndOneAuthorizationSelection() throws Exception {
        var source = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration", "classpath:db/account-cart-local")
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false")).load().migrate();
        var sql = new JdbcTemplate(source);
        sql.execute(fixture("fixture"));
        sql.execute(fixture("lane-fixture"));
        var wiring = new LocalAccountCartLauncher.SyntheticWiring();
        var normal = wiring.cartTestBuyers(false, sql);
        var switchable = wiring.cartTestBuyers(true, sql);
        assertThat(normal.admitted()).containsExactly(LocalAccountCartConfiguration.SUBJECT);
        assertThat(normal.select().get().subject()).isEqualTo(LocalAccountCartConfiguration.SUBJECT);
        assertThat(switchable.admitted()).containsExactlyInAnyOrder(LocalAccountCartConfiguration.SUBJECT, LocalAccountCartLauncher.SUBJECT_B);
        assertThatThrownBy(() -> switchable.select().get()).isInstanceOf(DataAccessException.class); // Not seeded: fail closed.
        var aLines = sql.queryForList("SELECT * FROM account_cart.cart_line WHERE owner_id=? ORDER BY id", A);
        sql.execute(fixture("two-buyers-fixture"));
        assertThat(sql.queryForObject("SELECT version FROM account_cart.cart WHERE owner_id=?", Long.class, B)).isZero();
        assertThat(sql.queryForList("SELECT id FROM account_cart.cart_line WHERE owner_id=?", B)).isEmpty();
        assertThat(sql.queryForObject("""
                SELECT u.status FROM users u JOIN user_profiles p ON p.user_id=u.id
                JOIN user_identity_current i ON i.user_id=u.id WHERE u.id=? AND i.issuer=? AND i.subject=?
                """, String.class, B, LocalAccountCartConfiguration.ISSUER, LocalAccountCartLauncher.SUBJECT_B)).isEqualTo("ACTIVE");
        assertThat(sql.queryForList("SELECT workspace_id FROM workspace_memberships WHERE user_id=?", B)).isEmpty();
        assertThat(sql.queryForList("SELECT id FROM workspaces WHERE owner_user_id=?", B)).isEmpty();
        assertThat(sql.queryForList("SELECT id FROM platform_access_grants WHERE recipient_user_id=?", B)).isEmpty();

        sql.update("UPDATE account_cart.cart SET version=7 WHERE owner_id=?", B);
        sql.update("UPDATE account_cart_fixture.two_buyers SET next_is_b=true WHERE singleton");
        sql.execute(fixture("two-buyers-fixture"));
        assertThat(sql.queryForObject("SELECT version FROM account_cart.cart WHERE owner_id=?", Long.class, B)).isEqualTo(7L);
        assertThat(sql.queryForList("SELECT * FROM account_cart.cart_line WHERE owner_id=? ORDER BY id", A)).isEqualTo(aLines);
        assertThat(normal.select().get().subject()).isEqualTo(LocalAccountCartConfiguration.SUBJECT); // Ignores the switch.
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<String> select = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return switchable.select().get().subject();
            };
            var first = pool.submit(select);
            var second = pool.submit(select);
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(LocalAccountCartConfiguration.SUBJECT, LocalAccountCartLauncher.SUBJECT_B);
        }
        assertThat(switchable.select().get().subject()).isEqualTo(LocalAccountCartConfiguration.SUBJECT);
    }
}
