package com.creastrix.platform.accountcart.catalog;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import com.creastrix.platform.accountcart.StudioDesign;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RetainedStudioCatalogPostgresTest {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("creastrix_account_cart_local").withUsername("account_cart_local_app")
            .withCreateContainerCmdModifier(c -> c.getHostConfig().withNetworkMode("bridge").withPublishAllPorts(false)
                    .withPortBindings(new Ports(ExposedPort.tcp(5432), Ports.Binding.bindIp("127.0.0.1"))));

    @Test void latestTemplateNeverSubstitutesForACompatibleImmutableRevision() throws Exception {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration","classpath:db/account-cart-local")
                .configuration(Map.of("flyway.postgresql.transactional.lock","false")).load().migrate();
        var db = new JdbcTemplate(ds);
        db.execute(new ClassPathResource("account-cart-local/fixture.sql").getContentAsString(StandardCharsets.UTF_8));
        // A template may look familiar and still enable an unsupported operation.
        // Exercise a separate hypothetical catalog transaction; never edit an immutable revision.
        try (var connection=ds.getConnection()) {
            connection.setAutoCommit(false);
            var trial=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            trial.update("""
                  INSERT INTO account_cart.offer(id,workspace_id,label)
                  SELECT 'loop',workspace_id,'Loop' FROM account_cart.offer WHERE id='kiln-cup'
                  """);
            trial.update("INSERT INTO account_cart.variant VALUES ('loop','cobalt','cobalt','Cobalt',2400,2,'#244bcc')");
            trial.update("INSERT INTO account_cart.template_revision VALUES ('loop',2,?::jsonb || '{\"other\":true}'::jsonb)",StudioDesign.TEMPLATE_V2);
            var incompatible=new PermanentCatalogController(trial).products().getBody().stream()
                    .filter(o -> o.id().equals("loop")).findFirst().orElseThrow();
            assertThat(incompatible.studioFormat()).isNull();
            assertThat(incompatible.variants()).singleElement().matches(v -> v.retainedStudioRevision()==null);
            connection.rollback();
        }
        db.execute(new ClassPathResource("account-cart-local/loop-studio-fixture.sql").getContentAsString(StandardCharsets.UTF_8));
        db.update("INSERT INTO account_cart.template_revision(offer_id,revision,configuration) VALUES ('loop',3,'{}'::jsonb)");
        db.update("""
                INSERT INTO account_cart.variant(offer_id,id,sku,label,cents,stock,swatch)
                VALUES ('loop','new','new','New',2500,5,'#dddddd')
                """);
        var offers = new PermanentCatalogController(db).products().getBody();
        assertThat(offers).isNotNull();
        var loop = offers.stream().filter(o -> o.id().equals("loop")).findFirst().orElseThrow();
        assertThat(loop.templateRevision()).isEqualTo("3");
        assertThat(loop.studioFormat()).isNull();
        assertThat(loop.variants()).filteredOn(v -> !"new".equals(v.id())).hasSize(4)
                .allMatch(v -> "2".equals(v.retainedStudioRevision()));
        assertThat(loop.variants()).filteredOn(v -> "new".equals(v.id())).singleElement()
                .matches(v -> v.retainedStudioRevision()==null);
        assertThat(offers).filteredOn(o -> !"loop".equals(o.id())).isNotEmpty()
                .allMatch(o -> o.variants().stream().noneMatch(v -> v.retainedStudioRevision()!=null));
    }
}
