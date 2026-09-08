package com.creastrix.platform;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Disposable-copy author experiment; observes real Flyway SQL and transaction boundaries. */
@Testcontainers
@Timeout(120)
class FlywayAtomicityCompatibilityIntegrationTest {

    private static final List<String> TABLES = List.of("users", "user_profiles", "organizations",
            "organization_memberships", "workspaces", "workspace_memberships", "workspace_membership_scopes",
            "ready_made_products", "ready_made_product_manual_quantity_delta_commands");
    private static final List<String> FOUNDATION = List.of("organizations", "organization_memberships",
            "workspaces", "workspace_memberships");

    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("compat_atomicity_" + UUID.randomUUID().toString().replace("-", ""));

    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;

    @BeforeEach
    void ownDisposableDatabaseOnly() {
        assertThat(postgres.isRunning()).isTrue();
        source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Properties limits = new Properties();
        limits.setProperty("connectTimeout", "5");
        limits.setProperty("socketTimeout", "50");
        source.setConnectionProperties(limits);
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(40);
        assertThat(source.getUrl()).isEqualTo(postgres.getJdbcUrl());
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo(postgres.getDatabaseName()).startsWith("compat_atomicity_");
        assertThat(jdbc.queryForObject("SHOW server_version_num", Integer.class)).isEqualTo(180004);
    }

    @ParameterizedTest(name = "{0}, populated={1}, failureAfterRealHistoryInsert={2}")
    @CsvSource({"BOOT,false,false", "BOOT,false,true", "BOOT,true,false", "BOOT,true,true",
            "STANDALONE,false,false", "STANDALONE,false,true", "STANDALONE,true,false", "STANDALONE,true,true"})
    void realApplicationAndStandaloneKeepValidationDdlAndHistoryAtomic(String path, boolean populated,
            boolean failAfterHistory) {
        Map<String, List<String>> before;
        List<Map<String, Object>> oldHistory = null;
        if (populated) {
            standalone(source, "8").migrate();
            populateEveryDomainTable();
            before = rows();
            assertThat(before).allSatisfy((table, values) -> assertThat(values).as(table).isNotEmpty());
            oldHistory = jdbc.queryForList("SELECT * FROM public.flyway_schema_history ORDER BY installed_rank");
        } else {
            before = new LinkedHashMap<>();
            TABLES.forEach(table -> before.put(table, List.of()));
        }

        Trace trace = new Trace(failAfterHistory);
        Throwable failure = catchThrowable(() -> migrate(path, trace));
        trace.assertConfigured(path);
        trace.assertAtomic(failAfterHistory);
        assertThat(rows()).isEqualTo(before);
        if (oldHistory != null) {
            assertThat(jdbc.queryForList("SELECT * FROM public.flyway_schema_history WHERE version <> '9' ORDER BY installed_rank"))
                    .isEqualTo(oldHistory);
        }
        if (failAfterHistory) {
            assertThat(failure).isNotNull();
            assertThat(causeContainsIdentity(failure, trace.injectedFailure))
                    .as("real post-INSERT observer failure is retained, not an unrelated failure").isTrue();
            assertThat(trace.historyVisibleBeforeInjection).isTrue();
            assertInstalled(false);
            Trace retry = new Trace(false);
            migrate(path, retry);
            retry.assertConfigured(path);
            retry.assertAtomic(false);
            assertInstalled(true);
            assertThat(rows()).isEqualTo(before);
            System.out.printf("ATOMICITY_RETRY path=%s populated=%s success=true%n", path, populated);
        } else {
            assertThat(failure).isNull();
            assertInstalled(true);
        }
        System.out.printf("ATOMICITY_CASE path=%s populated=%s failureAfterRealHistoryInsert=%s rowsPreserved=true events=%s%n",
                path, populated, failAfterHistory, trace.events);
    }

    private Flyway standalone(DataSource dataSource, String target) {
        var configuration = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(target).executeInTransaction(true).group(false).lockRetryCount(10);
        configuration.getConfigurationExtension(PostgreSQLConfigurationExtension.class).setTransactionalLock(false);
        return configuration.load();
    }

    private void migrate(String path, Trace trace) {
        if (path.equals("STANDALONE")) {
            Flyway flyway = standalone(new ObservedDataSource(source, trace), "9");
            trace.inspectConfiguration(flyway);
            flyway.migrate();
            return;
        }
        List<HikariDataSource> pools = new ArrayList<>();
        ConfigurableApplicationContext context = null;
        try {
            context = new SpringApplicationBuilder(CreastrixApplication.class)
                    .web(WebApplicationType.NONE)
                    .initializers(application -> application.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                        @Override
                        public Object postProcessAfterInitialization(Object bean, String name) {
                            if (bean instanceof HikariDataSource pool) {
                                pools.add(pool);
                                return new ObservedDataSource(pool, trace);
                            }
                            if (bean instanceof Flyway flyway) {
                                trace.inspectConfiguration(flyway);
                                trace.applicationProperty = application.getEnvironment()
                                        .getProperty("spring.flyway.postgresql.transactional-lock");
                            }
                            return bean;
                        }
                    }))
                    // Only disposable database and bounded operational settings are supplied here.
                    // The candidate MUST come from the copy's real application.yml, not this helper.
                    .run("--spring.datasource.url=" + postgres.getJdbcUrl(),
                            "--spring.datasource.username=" + postgres.getUsername(),
                            "--spring.datasource.password=" + postgres.getPassword(),
                            "--spring.datasource.hikari.connection-timeout=5000",
                            "--spring.datasource.hikari.maximum-pool-size=3",
                            "--spring.datasource.hikari.data-source-properties.socketTimeout=50",
                            "--spring.datasource.hikari.data-source-properties.connectTimeout=5",
                            "--spring.flyway.target=9", "--spring.flyway.lock-retry-count=10",
                            "--spring.main.banner-mode=off");
            assertThat(context.getBean(Flyway.class).getConfiguration().getTarget().getVersion()).isEqualTo("9");
            assertThat(pools).hasSize(1);
        } finally {
            if (context != null) {
                context.close();
            }
            // Spring normally destroys the original bean; also bound cleanup after failed refresh.
            pools.forEach(pool -> {
                if (!pool.isClosed()) {
                    pool.close();
                }
            });
        }
    }

    private void populateEveryDomainTable() {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID organization = UUID.randomUUID();
        UUID personal = UUID.randomUUID();
        UUID shared = UUID.randomUUID();
        UUID firstProduct = UUID.randomUUID();
        UUID secondProduct = UUID.randomUUID();
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO public.users(id) VALUES (?), (?)", user, other);
            jdbc.update("INSERT INTO public.user_profiles(user_id) VALUES (?), (?)", user, other);
            jdbc.update("INSERT INTO public.organizations(id) VALUES (?)", organization);
            jdbc.update("INSERT INTO public.organization_memberships VALUES (?, ?, 'OWNER', 'ACTIVE'), (?, ?, 'OWNER', 'ACTIVE')",
                    organization, user, organization, other);
            jdbc.update("INSERT INTO public.workspaces VALUES (?, 'USER', ?, NULL), (?, 'ORGANIZATION', NULL, ?)",
                    personal, user, shared, organization);
            jdbc.update("INSERT INTO public.workspace_memberships VALUES (?, ?, 'ADMIN', 'ACTIVE'), (?, ?, 'ADMIN', 'ACTIVE'), (?, ?, 'ADMIN', 'ACTIVE')",
                    personal, user, shared, user, shared, other);
            jdbc.update("INSERT INTO public.workspace_membership_scopes VALUES (?, ?, 'READY_MADE_PRODUCTS')", shared, other);
            jdbc.update("INSERT INTO public.ready_made_products VALUES (?, ?, ?, 'ACTIVE', 0), (?, ?, ?, 'ACTIVE', ?)",
                    firstProduct, personal, user, secondProduct, shared, other, Long.MAX_VALUE);
            jdbc.update("INSERT INTO public.ready_made_product_manual_quantity_delta_commands(product_id, command_id, delta, state) "
                    + "VALUES (?, ?, 1, 'REGISTERED'), (?, ?, -1, 'REGISTERED')",
                    firstProduct, UUID.randomUUID(), secondProduct, UUID.randomUUID());
        });
        jdbc.update("UPDATE public.ready_made_products SET status='ARCHIVED' WHERE id=?", secondProduct);
        jdbc.update("UPDATE public.users SET status='SUSPENDED' WHERE id=?", other);
    }

    private Map<String, List<String>> rows() {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        TABLES.forEach(table -> rows.put(table, jdbc.queryForList("SELECT to_jsonb(t)::text FROM public."
                + table + " t ORDER BY to_jsonb(t)::text", String.class)));
        return rows;
    }

    private void assertInstalled(boolean installed) {
        assertThat(jdbc.queryForList("SELECT version FROM public.flyway_schema_history ORDER BY installed_rank", String.class))
                .containsExactlyElementsOf(installed ? List.of("1", "2", "3", "4", "5", "6", "7", "8", "9")
                        : List.of("1", "2", "3", "4", "5", "6", "7", "8"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.flyway_schema_history WHERE version='9' AND success", Integer.class))
                .isEqualTo(installed ? 1 : 0);
        assertThat(jdbc.queryForObject("SELECT to_regprocedure('public.foundation_require_read_committed()') IS NOT NULL", Boolean.class))
                .isEqualTo(installed);
        for (String table : FOUNDATION) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgrelid=CAST(? AS regclass) AND tgname=?",
                    Integer.class, "public." + table, table + "_require_read_committed")).isEqualTo(installed ? 1 : 0);
        }
    }

    private static boolean causeContainsIdentity(Throwable failure, Throwable expected) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current == expected) {
                return true;
            }
        }
        return false;
    }

    private record Point(String event, int pid, String virtualTransaction, String xid, boolean autoCommit) {
    }

    private static final class Trace {
        private final boolean failAfterHistory;
        private final SQLException injectedFailure = new SQLException("COMPAT_AFTER_REAL_V9_HISTORY_INSERT", "XX000");
        private final List<Point> events = new ArrayList<>();
        private final List<String> boundaryViolations = new ArrayList<>();
        private final AtomicBoolean configured = new AtomicBoolean();
        private String applicationProperty;
        private Connection migrationConnection;
        private boolean terminal;
        private boolean historyVisibleBeforeInjection;

        private Trace(boolean failAfterHistory) {
            this.failAfterHistory = failAfterHistory;
        }

        private void inspectConfiguration(Flyway flyway) {
            assertThat(flyway.getConfiguration().isExecuteInTransaction()).isTrue();
            assertThat(flyway.getConfiguration().isGroup()).isFalse();
            assertThat(flyway.getConfiguration().getConfigurationExtension(PostgreSQLConfigurationExtension.class)
                    .isTransactionalLock()).isFalse();
            configured.set(true);
        }

        private void assertConfigured(String path) {
            assertThat(configured).isTrue();
            if (path.equals("BOOT")) {
                assertThat(applicationProperty).as("candidate consumed from application configuration").isEqualTo("false");
            }
        }

        private void before(Connection raw, String sql) throws SQLException {
            if (migrationConnection == null && sql.contains("CREASTRIX_FOUNDATION_MIGRATION_ISOLATION_V1")
                    && !sql.contains("CREASTRIX_FOUNDATION_MIGRATION_VALIDATION_V1")) {
                migrationConnection = raw;
                point(raw, "isolation");
            }
            if (raw == migrationConnection && sql.contains("CREASTRIX_FOUNDATION_MIGRATION_VALIDATION_V1")) {
                point(raw, "validation-before");
            }
        }

        private void after(Connection raw, String sql) throws SQLException {
            if (migrationConnection == null || terminal) {
                return;
            }
            if (sql.contains("CREASTRIX_FOUNDATION_MIGRATION_VALIDATION_V1")) {
                point(raw, "validation-after");
            }
            if (sql.contains("CREATE FUNCTION public.foundation_require_read_committed")) {
                point(raw, "function");
            }
            for (String table : FOUNDATION) {
                if (sql.contains("LOCK TABLE ONLY public." + table + " IN SHARE ROW EXCLUSIVE MODE")) {
                    point(raw, "locked:" + table);
                }
                if (sql.contains("CREATE TRIGGER " + table + "_require_read_committed")) {
                    point(raw, "trigger:" + table);
                }
            }
            if (sql.matches("(?is).*INSERT\\s+INTO\\s+.*flyway_schema_history.*")) {
                point(raw, "history-insert-return");
                try (Statement check = raw.createStatement(); ResultSet result = check.executeQuery(
                        "SELECT count(*) FROM public.flyway_schema_history WHERE version='9' AND success")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).as("real successful V9 history row exists before injected failure").isOne();
                    historyVisibleBeforeInjection = true;
                }
                if (failAfterHistory) {
                    throw injectedFailure;
                }
            }
        }

        private void beforeConnectionCall(Connection raw, Method method, Object[] args) throws SQLException {
            if (raw != migrationConnection || terminal) {
                return;
            }
            String name = method.getName();
            if ((name.equals("setAutoCommit") && Boolean.TRUE.equals(args[0])) || name.equals("close")) {
                boundaryViolations.add(name);
            }
            if (name.equals("commit") || name.equals("rollback")) {
                point(raw, name);
            }
        }

        private void afterConnectionCall(Connection raw, Method method) {
            if (raw == migrationConnection && !terminal
                    && (method.getName().equals("commit") || method.getName().equals("rollback"))) {
                terminal = true;
            }
        }

        private void point(Connection raw, String event) throws SQLException {
            try (Statement statement = raw.createStatement(); ResultSet result = statement.executeQuery("""
                    SELECT pg_backend_pid(), pg_current_xact_id_if_assigned()::text,
                        (SELECT virtualtransaction FROM pg_locks
                         WHERE pid=pg_backend_pid() AND locktype='virtualxid' AND granted),
                        current_setting('transaction_isolation')
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(4)).isEqualTo("read committed");
                events.add(new Point(event, result.getInt(1), result.getString(3), result.getString(2), raw.getAutoCommit()));
            }
        }

        private void assertAtomic(boolean expectedRollback) {
            System.out.printf("ATOMICITY_TRACE rollback=%s points=%s boundaryViolations=%s%n",
                    expectedRollback, events, boundaryViolations);
            assertThat(events.stream().map(Point::event)).containsExactly("isolation", "locked:organizations",
                    "locked:organization_memberships", "locked:workspaces", "locked:workspace_memberships",
                    "validation-before", "validation-after", "function", "trigger:organizations",
                    "trigger:organization_memberships", "trigger:workspaces", "trigger:workspace_memberships",
                    "history-insert-return", expectedRollback ? "rollback" : "commit");
            assertThat(events.stream().map(Point::pid).distinct()).hasSize(1);
            assertThat(events).allSatisfy(point -> {
                assertThat(point.virtualTransaction()).isNotBlank();
                assertThat(point.autoCommit()).isFalse();
            });
            assertThat(events.stream().map(Point::virtualTransaction).distinct()).hasSize(1);
            assertThat(events.stream().map(Point::xid).filter(value -> value != null).distinct()).hasSize(1);
            assertThat(events.stream().filter(point -> point.event().equals("function")
                    || point.event().startsWith("trigger:") || point.event().equals("history-insert-return")))
                    .allSatisfy(point -> assertThat(point.xid()).isNotBlank());
            assertThat(boundaryViolations).isEmpty();
            assertThat(terminal).isTrue();
        }
    }

    /** Never replaces SQL/results and never invokes commit itself. Only the history failure is injected. */
    private static final class ObservedDataSource extends AbstractDataSource implements AutoCloseable {
        private final DataSource delegate;
        private final Trace trace;

        private ObservedDataSource(DataSource delegate, Trace trace) {
            this.delegate = delegate;
            this.trace = trace;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return observe(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return observe(delegate.getConnection(username, password));
        }

        private Connection observe(Connection raw) throws SQLException {
            try (Statement statement = raw.createStatement()) {
                statement.execute("SET lock_timeout='30s'");
                statement.execute("SET statement_timeout='40s'");
            }
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        trace.beforeConnectionCall(raw, method, args);
                        Object result = invoke(raw, method, args);
                        trace.afterConnectionCall(raw, method);
                        if (result instanceof Statement statement) {
                            String prepared = args != null && args.length > 0 && args[0] instanceof String text ? text : null;
                            Class<?> type = result instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                            return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] {type},
                                    (statementProxy, statementMethod, statementArgs) -> {
                                        String sql = statementArgs != null && statementArgs.length > 0
                                                && statementArgs[0] instanceof String text ? text : prepared;
                                        boolean execute = statementMethod.getName().startsWith("execute") && sql != null;
                                        if (execute) {
                                            trace.before(raw, sql);
                                        }
                                        Object actualResult = invoke(statement, statementMethod, statementArgs);
                                        if (execute) {
                                            trace.after(raw, sql);
                                        }
                                        return actualResult;
                                    });
                        }
                        return result;
                    });
        }

        @Override
        public void close() throws Exception {
            if (delegate instanceof AutoCloseable resource) {
                resource.close();
            }
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }
}
