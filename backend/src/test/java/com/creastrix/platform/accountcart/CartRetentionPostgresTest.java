package com.creastrix.platform.accountcart;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.creastrix.platform.accountcart.persistence.CartAssetRetentionReader;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static com.creastrix.platform.accountcart.assets.CartAssetRetention.Eligibility.*;
import static com.creastrix.platform.accountcart.assets.CartAssetRetention.classify;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class CartRetentionPostgresTest {
    private static final UUID OWNER=UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final Duration WEEK=Duration.ofDays(7);
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("creastrix_account_cart_local").withUsername("account_cart_local_app")
            .withCreateContainerCmdModifier(c->c.getHostConfig().withNetworkMode("bridge").withPublishAllPorts(false)
                    .withPortBindings(new Ports(ExposedPort.tcp(5432),Ports.Binding.bindIp("127.0.0.1"))));
    private static JdbcTemplate sql;
    private static TransactionTemplate tx;
    private static CartAssetRetentionReader reader;
    private static UUID legacyUnlinked;

    @BeforeAll static void migrate() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:"+DB.getMappedPort(5432)+"/"+DB.getDatabaseName(),
                DB.getUsername(),DB.getPassword());
        sql=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        reader=new CartAssetRetentionReader(sql);
        var flyway=Flyway.configure().dataSource(ds).locations("classpath:db/migration","classpath:db/account-cart-local")
                .configuration(Map.of("flyway.postgresql.transactional.lock","false"));
        flyway.target("18").load().migrate();
        sql.execute(new ClassPathResource("account-cart-local/fixture.sql").getContentAsString(StandardCharsets.UTF_8));
        legacyUnlinked=asset(false);
        UUID previous=tx.execute(s->pin(legacyUnlinked));
        terminal(previous,"RETIRED");
        flyway.target("20").load().migrate();
    }
    private static UUID asset(boolean completed) {
        var asset=UUID.randomUUID();
        if (completed) sql.update("INSERT INTO account_cart.asset(owner_id,asset_id,kind,object_key,byte_count,sha256) VALUES (?,?,'IMAGE',?,1,repeat('a',64))",
                OWNER,asset,UUID.randomUUID());
        else sql.update("INSERT INTO account_cart.asset(owner_id,asset_id,kind) VALUES (?,?,'IMAGE')",OWNER,asset);
        return asset;
    }
    private static UUID design(UUID asset) {
        var design=UUID.randomUUID();
        sql.update("""
                INSERT INTO account_cart.design_revision(owner_id,design_id,revision,offer_id,variant_id,template_revision,label,canonical_bytes)
                VALUES (?,?,1,'kiln-cup','blue',1,'retention',decode('01','hex'))
                """,OWNER,design);
        sql.update("INSERT INTO account_cart.design_asset(owner_id,design_id,revision,asset_id) VALUES (?,?,1,?)",OWNER,design,asset);
        return design;
    }
    private static UUID pin(UUID asset) {
        var batch=UUID.randomUUID();
        sql.update("INSERT INTO account_cart.import_receipt(owner_id,batch_id,request_bytes) VALUES (?,?,decode('01','hex'))",OWNER,batch);
        sql.update("INSERT INTO account_cart.pending_asset(owner_id,batch_id,asset_id) VALUES (?,?,?)",OWNER,batch,asset);
        return batch;
    }
    private static void terminal(UUID batch,String state) {
        sql.update("UPDATE account_cart.import_receipt SET state=?,applied_version=? WHERE owner_id=? AND batch_id=?",
                state,state.equals("APPLIED")?1L:null,OWNER,batch);
    }
    private static Instant observe(UUID asset) {
        var when=sql.queryForObject("SELECT account_cart.observe_unreferenced(?,?)",Timestamp.class,OWNER,asset);
        return when==null?null:when.toInstant();
    }

    @ParameterizedTest @ValueSource(strings={"APPLIED","RETIRED"})
    void lastReceiptTransitionEndsARealPinEvenIfItsPhysicalRowRemains(String state) {
        var ids=tx.execute(s->{ var asset=asset(true); return new UUID[]{asset,design(asset),pin(asset)}; });
        sql.update("DELETE FROM account_cart.design_revision WHERE owner_id=? AND design_id=?",OWNER,ids[1]);
        var earlier=sql.queryForObject("SELECT last_confirmed_removed_at FROM account_cart.asset WHERE owner_id=? AND asset_id=?",
                Timestamp.class,OWNER,ids[0]).toInstant();
        assertThat(observe(ids[0])).isNull();
        tx.executeWithoutResult(s->{ terminal(ids[2],state); assertThat(observe(ids[0])).isNull(); s.setRollbackOnly(); });
        assertThat(reader.facts(OWNER,ids[0]).orElseThrow().pendingPins()).isOne();
        tx.executeWithoutResult(s->{ terminal(ids[2],state); assertThat(observe(ids[0])).isNull(); });
        assertThat(sql.queryForObject("SELECT count(*) FROM account_cart.pending_asset WHERE owner_id=? AND batch_id=?",
                Long.class,OWNER,ids[2])).isOne();
        var unobserved=reader.facts(OWNER,ids[0]).orElseThrow();
        assertThat(classify(unobserved,earlier.plus(Duration.ofDays(200)))).isEqualTo(RETAIN);
        var clock=observe(ids[0]);
        assertThat(clock).isAfter(earlier);
        var observed=reader.facts(OWNER,ids[0]).orElseThrow();
        assertThat(observed.unreferencedObservedAt()).isEqualTo(clock);
        assertThat(classify(observed,earlier.plus(WEEK))).isEqualTo(RETAIN);
        assertThat(classify(observed,clock.plus(WEEK))).isEqualTo(LAST_REFERENCE_REMOVED);
        sql.update("DELETE FROM account_cart.pending_asset WHERE owner_id=? AND batch_id=?",OWNER,ids[2]);
        assertThat(observe(ids[0])).isEqualTo(clock);
    }

    @Test void repinningCancelsTheGracePeriodAndAnotherCommittedLastUnlinkStartsANewOne() {
        var asset=asset(false);
        var first=tx.execute(s->pin(asset));
        terminal(first,"RETIRED");
        var old=observe(asset);
        assertThat(classify(reader.facts(OWNER,asset).orElseThrow(),old.plus(WEEK))).isEqualTo(LAST_REFERENCE_REMOVED);
        var one=tx.execute(s->pin(asset)); var two=tx.execute(s->pin(asset));
        assertThat(reader.facts(OWNER,asset).orElseThrow().unreferencedObservedAt()).isNull();
        terminal(one,"RETIRED");
        assertThat(observe(asset)).isNull();
        terminal(two,"APPLIED");
        var fresh=observe(asset);
        assertThat(fresh).isAfter(old);
        var facts=reader.facts(OWNER,asset).orElseThrow();
        assertThat(classify(facts,old.plus(WEEK))).isEqualTo(RETAIN);
        assertThat(classify(facts,fresh.plus(WEEK))).isEqualTo(LAST_REFERENCE_REMOVED);
    }

    @Test void noClockForForgottenLegacyTimingOrAnEdgeNeverKnownToHaveCommitted() {
        var asset=asset(false);
        tx.executeWithoutResult(s->{ var batch=pin(asset); terminal(batch,"RETIRED"); });
        for (var unproven : new UUID[]{legacyUnlinked,asset}) {
            assertThat(observe(unproven)).isNull();
            var state=reader.facts(OWNER,unproven).orElseThrow();
            assertThat(classify(state,state.uploadStartedAt().plus(Duration.ofDays(1000)))).isEqualTo(RETAIN);
        }
    }

    @Test void observationSkipsAnUncommittedFinalReleaseOnAnotherPhysicalConnection() throws Exception {
        var asset=asset(false);
        var batch=tx.execute(s->pin(asset));
        try (var writer=sql.getDataSource().getConnection()) {
            writer.setAutoCommit(false);
            try (var release=writer.prepareStatement("UPDATE account_cart.import_receipt SET state='RETIRED' WHERE owner_id=? AND batch_id=?")) {
                release.setObject(1,OWNER); release.setObject(2,batch);
                assertThat(release.executeUpdate()).isOne();
            }
            tx.executeWithoutResult(s->{
                sql.execute("SET LOCAL statement_timeout='2s'");
                assertThat(observe(asset)).isNull();
                assertThat(reader.facts(OWNER,asset).orElseThrow().pendingPins()).isOne();
            });
            writer.commit();
        }
        assertThat(observe(asset)).isNotNull();
    }
}
