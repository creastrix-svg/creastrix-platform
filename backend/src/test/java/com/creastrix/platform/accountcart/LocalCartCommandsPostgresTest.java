package com.creastrix.platform.accountcart;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.creastrix.platform.accountcart.LocalCartCommands.*;
import com.creastrix.platform.accountcart.assets.LocalCartFixtureAsset;
import com.creastrix.platform.accountcart.assets.PrivateCartAssetStore;
import com.creastrix.platform.accountcart.persistence.PermanentCartReader;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class LocalCartCommandsPostgresTest {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("creastrix_account_cart_local").withUsername("account_cart_local_app")
            .withCreateContainerCmdModifier(c->c.getHostConfig().withNetworkMode("bridge").withPublishAllPorts(false)
                    .withPortBindings(new Ports(ExposedPort.tcp(5432),Ports.Binding.bindIp("127.0.0.1"))));
    @TempDir static Path dir;
    static DriverManagerDataSource ds;
    static JdbcTemplate sql;
    static LocalCartCommands api;
    static PermanentCartReader reader;
    static final UUID OWNER=UUID.fromString("10000000-0000-4000-8000-000000000001");
    static final UUID OTHER=UUID.fromString("10000000-0000-4000-8000-000000000002");
    static final UUID DESIGN=UUID.fromString("30000000-0000-4000-8000-000000000001");
    static final String SKU="kiln-blue";

    @BeforeAll static void init() throws Exception {
        ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:"+PG.getMappedPort(5432)+"/"+PG.getDatabaseName(),PG.getUsername(),PG.getPassword());
        var flyway=Flyway.configure().dataSource(ds).locations("classpath:db/migration","classpath:db/account-cart-local")
                .configuration(Map.of("flyway.postgresql.transactional.lock","false")).load();
        flyway.migrate(); assertThat(flyway.info().current().getVersion().toString()).isEqualTo("33");
        sql=new JdbcTemplate(ds);
        var files=privateStore("private");
        LocalCartFixtureAsset.ensurePresent(files);
        for (String file:List.of("fixture.sql","lane-fixture.sql","loop-studio-fixture.sql")) {
            var script=new ClassPathResource("account-cart-local/"+file).getContentAsString(StandardCharsets.UTF_8);
            sql.execute(script); sql.execute(script);
        }
        api=new LocalCartCommands(ds,files); reader=new PermanentCartReader(sql);
    }
    static Claim login(UUID who,String session) {
        return api.beginLogin(api.loginEpoch(),who,api.eligibilityGeneration(who),session);
    }
    static PrivateCartAssetStore privateStore(String name) throws Exception {
        return PrivateCartAssetStore.open(Files.createDirectory(dir.resolve(name),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))).toRealPath());
    }
    static long receipts() { return sql.queryForObject("SELECT count(*) FROM account_cart.import_receipt",Long.class); }

    @Test void fittedImagesIgnoreInactiveFreeTransformsButDifferentFreePlacementsStaySeparate() {
        String session="studio-fit"; var claim=login(OWNER,session); api.bind(claim,session);
        for(String fit:List.of("contain","cover","free")) {
            for(int n=0;n<2;n++) {
                var doc=StudioDesignTest.artwork().put("sku","coral");
                var image=(tools.jackson.databind.node.ObjectNode)doc.get("objects").get(0);
                image.put("fit",fit).put("x",225.0+n*10).put("y",272.0+n*6);
                if(!fit.equals("free")) image.put("size",60.0+n*20);
                var design=api.stageDesign(claim,session,claim.namespace(),UUID.randomUUID(),StudioDesign.parse(doc));
                var batch=UUID.randomUUID();
                api.prepare(claim,session,claim.namespace(),batch,List.of(new Item("coral",design,1L,1)));
                api.apply(claim,session,claim.namespace(),batch);
            }
        }
        var rows=reader.lines(OWNER).orElseThrow().stream().filter(l->l.offerId().equals("loop") && l.variantId().equals("coral")).toList();
        assertThat(rows).hasSize(4);
        int free=0;
        for(var row:rows) {
            var saved=api.lineDesign(claim,session,row.lineId()).orElseThrow().get("objects").get(0);
            if(saved.get("fit").asString().equals("free")) { assertThat(row.quantity()).isEqualTo("1"); free++; }
            else {
                assertThat(row.quantity()).isEqualTo("2");
                // Exact owner's saved source survives; the comparison projection was never written over it.
                assertThat(saved.get("x").asDouble()).isEqualTo(225.0);
                assertThat(saved.get("size").asDouble()).isEqualTo(60.0);
            }
        }
        assertThat(free).isEqualTo(2);
        api.logout(claim,session);
    }

    @Test void stagedFontAndImageRetryMergeAndReadByOwnerAfterFreshLogin() throws Exception {
        String sid="studio"; var claim=login(OTHER,sid); api.bind(claim,sid);
        UUID stage=UUID.randomUUID(), batch=UUID.randomUUID();
        var input=StudioDesignTest.artwork();
        StudioDesignTest.withText(input,"resource").put("data",StudioDesignTest.testFont());
        var parsed=StudioDesign.parse(input);
        // A later published catalog revision does not rebase the guest's applied v2 material.
        sql.update("INSERT INTO account_cart.template_revision(offer_id,revision,configuration) VALUES ('loop',3,'{}') ON CONFLICT DO NOTHING");
        assertThatThrownBy(()->StudioDesign.parse(input.deepCopy().put("templateRevision","3"))).isInstanceOf(InvalidBatch.class);
        // A direct reference to a historical unsupported layout cannot bypass staging validation.
        var unknown=UUID.randomUUID();
        sql.update("""
            INSERT INTO account_cart.design_revision(owner_id,design_id,revision,offer_id,variant_id,template_revision,
                label,canonical_bytes,layout_format) VALUES (?,?,1,'loop','cobalt',2,'Unresolved layout',?,1)
            """,OTHER,unknown,new byte[]{0,0,0,1});
        assertThatThrownBy(()->api.prepare(claim,sid,claim.namespace(),UUID.randomUUID(),
                List.of(new Item("cobalt",unknown,1L,1)))).isInstanceOf(InvalidBatch.class);
        // Two requests with the same stable ID can race after a lost reply; neither creates extra files or revisions.
        var start=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<UUID> save=()->{ start.await(); return api.stageDesign(claim,sid,claim.namespace(),stage,parsed); };
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(save); var b=pool.submit(save); start.countDown();
            assertThat(a.get()).isEqualTo(stage); assertThat(b.get()).isEqualTo(stage);
        }
        assertThat(sql.queryForObject("SELECT count(*) FROM account_cart.design_asset WHERE owner_id=? AND design_id=?",Long.class,OTHER,stage)).isEqualTo(2);
        var changed=input.deepCopy(); ((tools.jackson.databind.node.ObjectNode)changed.get("objects").get(1)).put("text","Different");
        assertThatThrownBy(()->api.stageDesign(claim,sid,claim.namespace(),stage,StudioDesign.parse(changed))).isInstanceOf(Conflict.class);
        assertThatThrownBy(()->api.stageDesign(claim,sid,claim.namespace(),UUID.randomUUID(),StudioDesign.parse(input.deepCopy().put("templateRevision","99")))).isInstanceOf(InvalidBatch.class);
        assertThatThrownBy(()->api.stageDesign(claim,sid,claim.namespace(),UUID.randomUUID(),StudioDesign.parse(input.deepCopy().put("sku",SKU)))).isInstanceOf(InvalidBatch.class);
        var missing=new LocalCartCommands(ds,privateStore("missing-studio-assets"));
        var lines=List.of(new Item("cobalt",null,null,1),new Item("cobalt",stage,1L,1));
        assertThatThrownBy(()->missing.prepare(claim,sid,claim.namespace(),batch,lines)).isInstanceOf(Unavailable.class);
        assertThat(api.status(claim,sid,claim.namespace(),batch).state()).isEqualTo("UNKNOWN");
        var equivalent=api.stageDesign(claim,sid,claim.namespace(),UUID.randomUUID(),parsed);
        assertThat(api.prepare(claim,sid,claim.namespace(),batch,lines).state()).isEqualTo("PENDING");
        assertThat(api.prepare(claim,sid,claim.namespace(),batch,List.of(new Item("cobalt",equivalent,1L,1),
                new Item("cobalt",null,null,1))).state()).isEqualTo("PENDING");
        assertThatThrownBy(()->missing.apply(claim,sid,claim.namespace(),batch)).isInstanceOf(Unavailable.class);
        var ack=api.apply(claim,sid,claim.namespace(),batch);
        api.logout(claim,sid);
        String otherSid="cannot-read-studio"; var other=login(OWNER,otherSid); api.bind(other,otherSid);
        UUID line=sql.queryForObject("SELECT id FROM account_cart.cart_line WHERE owner_id=? AND design_id=?",UUID.class,OTHER,stage);
        assertThat(api.lineDesign(other,otherSid,line)).isEmpty();
        assertThatThrownBy(()->api.prepare(other,otherSid,other.namespace(),UUID.randomUUID(),lines)).isInstanceOf(InvalidBatch.class);
        api.logout(other,otherSid);
        String freshSid="reopen-studio"; var fresh=login(OTHER,freshSid); api.bind(fresh,freshSid);
        assertThat(api.lineDesign(fresh,freshSid,line).filter(input::equals)).isPresent();
        assertThatThrownBy(()->missing.lineDesign(fresh,freshSid,line)).isInstanceOf(Unavailable.class);
        assertThat(api.apply(fresh,freshSid,fresh.namespace(),batch)).isEqualTo(ack);
        assertThat(reader.lines(OTHER).orElseThrow()).filteredOn(l->l.lineId().equals(line)).singleElement()
                .satisfies(l->assertThat(l.quantity()).isEqualTo("1"));
        var second=UUID.randomUUID();
        api.prepare(fresh,freshSid,fresh.namespace(),second,List.of(new Item("cobalt",equivalent,1L,1)));
        api.apply(fresh,freshSid,fresh.namespace(),second);
        assertThat(reader.lines(OTHER).orElseThrow()).filteredOn(l->l.offerId().equals("loop") && l.designLabel()!=null)
                .singleElement().satisfies(l->{ assertThat(l.lineId()).isEqualTo(line); assertThat(l.quantity()).isEqualTo("2"); });
        api.logout(fresh,freshSid);
    }

    @Test void onlyOneConcurrentLoginCanReserveTheOpaqueGuestIntentAndItNeverBecomesAvailableAgain() throws Exception {
        UUID id=UUID.randomUUID();
        var start=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> reserve=()->{
            start.await();
            try { api.reserveGuestIntent(id); return true; }
            catch(Conflict expected) { return false; }
        };
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(reserve); var b=pool.submit(reserve); start.countDown();
            assertThat(List.of(a.get(15,java.util.concurrent.TimeUnit.SECONDS),b.get(15,java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true,false);
        }
        assertThatThrownBy(()->api.reserveGuestIntent(id)).isInstanceOf(Conflict.class);
        assertThatThrownBy(()->sql.update("DELETE FROM account_cart.guest_login_intent WHERE intent_id=?",id))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @ParameterizedTest @ValueSource(ints={1,2}) void onlyKnownBindingRollbackAllowsSameSessionRecoveryWithoutImports(int failures) {
        var before=reader.lines(OWNER); long receiptCount=receipts();
        var session=UUID.randomUUID().toString(); var claim=login(OWNER,session);
        assertThat(claim.toString()).isEqualTo("LocalCartClaim[redacted]");
        assertThatThrownBy(()->api.inspect(claim,session)).isInstanceOf(Unavailable.class);
        assertThatThrownBy(()->api.recover(claim,session,claim.ticket())).isInstanceOf(Conflict.class);
        assertThat(sql.queryForList("SELECT account_cart_fixture.fail_next_bindings(?)",failures)).hasSize(1);
        assertThat(api.bind(claim,session)).isEqualTo(BindResult.RETRYABLE);
        assertThat(api.inspect(claim,session)).isEqualTo(Access.PENDING);
        assertThatThrownBy(()->api.recover(claim,"different-session",claim.ticket())).isInstanceOf(Conflict.class);
        assertThatThrownBy(()->api.recover(claim,session,UUID.randomUUID())).isInstanceOf(Conflict.class);
        if (failures==2) assertThat(api.recover(claim,session,claim.ticket())).isEqualTo(BindResult.RETRYABLE);
        assertThat(api.recover(claim,session,claim.ticket())).isEqualTo(BindResult.BOUND);
        assertThat(api.recover(claim,session,claim.ticket())).isEqualTo(BindResult.BOUND);
        assertThat(api.inspect(claim,session)).isEqualTo(Access.BOUND);
        assertThat(reader.lines(OWNER)).isEqualTo(before); assertThat(receipts()).isEqualTo(receiptCount);
        api.logout(claim,session);
        assertThatThrownBy(()->api.inspect(claim,session)).isInstanceOf(Conflict.class);
    }

    @Test void oldEpochPendingLogoutAndForeignNamespaceNeverActAsAnotherOwner() {
        long original=api.loginEpoch(); var a=api.beginLogin(original,OWNER,1,"session-a");
        assertThatThrownBy(()->api.beginLogin(original,OTHER,1,"session-b")).isInstanceOf(Conflict.class);
        api.logout(a,"session-a");
        assertThatThrownBy(()->api.bind(a,"session-a")).isInstanceOf(Conflict.class);
        var b=login(OTHER,"session-b"); api.bind(b,"session-b");
        assertThat(b.namespace()).isNotEqualTo(a.namespace());
        assertThatThrownBy(()->api.logout(a,"session-a")).isInstanceOf(Conflict.class);
        UUID id=UUID.randomUUID();
        assertThatThrownBy(()->api.status(b,"session-b",a.namespace(),id)).isInstanceOf(Conflict.class);
        assertThatThrownBy(()->api.retire(b,"session-b",a.namespace(),id)).isInstanceOf(Conflict.class);
        assertThat(api.status(b,"session-b",b.namespace(),id)).isEqualTo(new Receipt("UNKNOWN",null));
        api.logout(b,"session-b");
    }

    @Test void anotherCommitFailureWithTheSameSqlstateIsNotARecoverableBindingRollback() {
        String session="unclassified-bind"; var claim=login(OWNER,session);
        sql.execute("""
                CREATE FUNCTION account_cart_fixture.unrelated_commit_fault() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Synthetic unrelated fault' USING ERRCODE='PCA01',
                    SCHEMA='account_cart_fixture',CONSTRAINT='different_constraint'; END $$;
                CREATE CONSTRAINT TRIGGER unrelated_commit_fault AFTER UPDATE ON account_cart.lane_claim
                  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.ticket='%s' AND NEW.state='BOUND')
                  EXECUTE FUNCTION account_cart_fixture.unrelated_commit_fault()
                """.formatted(claim.ticket()));
        assertThatThrownBy(()->api.bind(claim,session)).isInstanceOf(Unavailable.class).hasMessage("CART_UNAVAILABLE").hasNoCause();
        assertThatThrownBy(()->api.inspect(claim,session)).isInstanceOf(Unavailable.class);
        assertThatThrownBy(()->api.recover(claim,session,claim.ticket())).isInstanceOf(Conflict.class);
        api.logout(claim,session);
    }

    @Test void verifiedDesignAndPlainAreImportedExactlyOnceAfterReorderedReplayAndRelogin() {
        String session="first-import"; var a=login(OWNER,session); api.bind(a,session);
        var before=reader.lines(OWNER).orElseThrow();
        var plain=before.stream().filter(l->l.designLabel()==null).findFirst().orElseThrow();
        var motif=before.stream().filter(l->l.designLabel()!=null).findFirst().orElseThrow();
        UUID batch=UUID.randomUUID();
        var pending=new Receipt("PENDING",null);
        assertThat(api.prepare(a,session,a.namespace(),batch,List.of(new Item(SKU,null,null,1),new Item(SKU,DESIGN,1L,1),new Item(SKU,null,null,2)))).isEqualTo(pending);
        assertThat(api.prepare(a,session,a.namespace(),batch,List.of(new Item(SKU,DESIGN,1L,1),new Item(SKU,null,null,3)))).isEqualTo(pending);
        assertThatThrownBy(()->api.prepare(a,session,a.namespace(),batch,List.of(new Item(SKU,null,null,4)))).isInstanceOf(Conflict.class);
        assertThat(sql.queryForObject("SELECT count(*) FROM account_cart.pending_asset WHERE owner_id=? AND batch_id=?",Long.class,OWNER,batch)).isOne();
        var ack=api.apply(a,session,a.namespace(),batch);
        assertThat(ack.state()).isEqualTo("APPLIED");
        api.logout(a,session);
        assertThatThrownBy(()->api.apply(a,session,a.namespace(),batch)).isInstanceOf(Conflict.class);
        var same=login(OWNER,"next-session"); api.bind(same,"next-session");
        assertThat(same.namespace()).isEqualTo(a.namespace());
        assertThat(api.apply(same,"next-session",same.namespace(),batch)).isEqualTo(ack);
        assertThat(api.status(same,"next-session",same.namespace(),batch)).isEqualTo(ack);
        assertThat(api.retire(same,"next-session",same.namespace(),batch)).isEqualTo(ack);
        var rows=reader.lines(OWNER).orElseThrow();
        assertThat(rows).hasSize(before.size());
        assertThat(rows).filteredOn(l->l.lineId().equals(plain.lineId())).singleElement().satisfies(l->
                assertThat(l.quantity()).isEqualTo(String.valueOf(Long.parseLong(plain.quantity())+3)));
        assertThat(rows).filteredOn(l->l.lineId().equals(motif.lineId())).singleElement().satisfies(l->{
            assertThat(l.quantity()).isEqualTo(String.valueOf(Long.parseLong(motif.quantity())+1)); assertThat(l.templateRevision()).isEqualTo("1");
        });
        assertThat(sql.queryForObject("SELECT count(*) FROM account_cart.pending_asset WHERE owner_id=? AND batch_id=?",Long.class,OWNER,batch)).isZero();
        api.logout(same,"next-session");
    }

    private static String insertCatalogSku(int length) {
        String sku="a".repeat(length);
        sql.update("""
                INSERT INTO account_cart.variant(offer_id,id,sku,label,cents,stock,swatch)
                VALUES ('kiln-cup',?,?,'Test glaze',2400,12,'#365899')
                ""","length-"+length,sku);
        return sku;
    }

    @ParameterizedTest @ValueSource(ints={128,129,200}) void knownPlainSkuImportsAtSupportedLengths(int length) {
        String sku=insertCatalogSku(length);
        String session="sku-length-"+length; var claim=login(OTHER,session); api.bind(claim,session);
        var batch=UUID.randomUUID();
        assertThat(api.prepare(claim,session,claim.namespace(),batch,List.of(new Item(sku,null,null,3))))
                .isEqualTo(new Receipt("PENDING",null));
        assertThat(api.apply(claim,session,claim.namespace(),batch).state()).isEqualTo("APPLIED");
        assertThat(reader.lines(OTHER).orElseThrow()).filteredOn(l->l.variantId().equals("length-"+length))
                .singleElement().satisfies(l->{
                    assertThat(l.designLabel()).isNull();
                    assertThat(l.quantity()).isEqualTo("3");
                });
        api.logout(claim,session);
    }

    @Test void unsupportedPlainSkuRejectsTheWholeBatchEvenIfCatalogKnowsTheOverlongSku() {
        String overlong=insertCatalogSku(201);
        String session="sku-outside-contract"; var claim=login(OTHER,session); api.bind(claim,session);
        var before=reader.lines(OTHER);
        for (String sku:List.of(overlong,"invalid--sku","unknown-sku")) {
            var batch=UUID.randomUUID();
            assertThatThrownBy(()->api.prepare(claim,session,claim.namespace(),batch,
                    List.of(new Item(SKU,null,null,1),new Item(sku,null,null,3)))).isInstanceOf(InvalidBatch.class);
            assertThat(api.status(claim,session,claim.namespace(),batch)).isEqualTo(new Receipt("UNKNOWN",null));
        }
        assertThat(reader.lines(OTHER)).isEqualTo(before);
        api.logout(claim,session);
    }

    @Test void invalidMixedBatchMissingFileUnknownApplyAndLatePrepareNeverBecomeFreshCredit() throws Exception {
        String session="batch-validation"; var claim=login(OWNER,session); api.bind(claim,session);
        var before=reader.lines(OWNER); var batch=UUID.randomUUID(); long count=receipts();
        assertThatThrownBy(()->api.prepare(claim,session,claim.namespace(),batch,
                List.of(new Item(SKU,null,null,4),new Item("unknown-sku",null,null,2)))).isInstanceOf(InvalidBatch.class);
        var missing=new LocalCartCommands(ds,privateStore("separate-empty"));
        assertThatThrownBy(()->missing.prepare(claim,session,claim.namespace(),batch,List.of(new Item(SKU,DESIGN,1L,1)))).isInstanceOf(Unavailable.class);
        assertThatThrownBy(()->api.apply(claim,session,claim.namespace(),batch)).isInstanceOf(Conflict.class);
        assertThat(receipts()).isEqualTo(count);
        assertThat(api.retire(claim,session,claim.namespace(),batch)).isEqualTo(new Receipt("RETIRED",null));
        assertThatThrownBy(()->api.prepare(claim,session,claim.namespace(),batch,List.of())).isInstanceOf(Conflict.class);
        assertThatThrownBy(()->api.apply(claim,session,claim.namespace(),batch)).isInstanceOf(Conflict.class);
        assertThat(reader.lines(OWNER)).isEqualTo(before);
        api.logout(claim,session);
    }
}
