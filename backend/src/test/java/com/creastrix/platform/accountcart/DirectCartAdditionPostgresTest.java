package com.creastrix.platform.accountcart;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.creastrix.platform.accountcart.LocalCartCommands.*;
import com.creastrix.platform.accountcart.assets.PrivateCartAssetStore;
import com.creastrix.platform.accountcart.catalog.IsolatedCatalogAuthority;
import com.creastrix.platform.accountcart.persistence.PermanentCartReader;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class DirectCartAdditionPostgresTest {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("creastrix_account_cart_local").withUsername("account_cart_local_app")
            .withCreateContainerCmdModifier(c->c.getHostConfig().withNetworkMode("bridge").withPublishAllPorts(false)
                    .withPortBindings(new Ports(ExposedPort.tcp(5432),Ports.Binding.bindIp("127.0.0.1"))));
    @TempDir static Path temp;
    static JdbcTemplate db;
    static LocalCartCommands api;
    static DriverManagerDataSource cartSource;
    static DriverManagerDataSource storefront;
    static PrivateCartAssetStore files;
    static final String TIDE="tide-cobalt";
    static UUID legacyOwner;
    static final UUID LEGACY_INTENT=UUID.randomUUID(), LEGACY_LINE=UUID.randomUUID();
    String sid;
    Claim buyer;

    @BeforeAll static void start() throws Exception {
        var ds=cartSource=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        var config=Flyway.configure().dataSource(ds).locations("classpath:db/migration","classpath:db/account-cart-local")
                .configuration(Map.of("flyway.postgresql.transactional.lock","false"));
        config.target("26").load().migrate();
        db=new JdbcTemplate(ds);
        for (String file:List.of("fixture.sql","lane-fixture.sql","tide-add-fixture.sql","tide-add-fixture.sql"))
            db.execute(new ClassPathResource("account-cart-local/"+file).getContentAsString(StandardCharsets.UTF_8));
        // Exercise a populated upgrade, not just a fresh empty receipt table.
        legacyOwner=newOwner();
        db.update("INSERT INTO account_cart.cart(owner_id,version) VALUES (?,1)",legacyOwner);
        db.update("INSERT INTO account_cart.cart_line(owner_id,id,offer_id,variant_id,quantity) VALUES (?,?,'tide','cobalt',2)",legacyOwner,LEGACY_LINE);
        db.update("INSERT INTO account_cart.direct_add_receipt VALUES (?,?,?,2,1)",LEGACY_INTENT,legacyOwner,TIDE);
        config.target("latest").load().migrate();
        files=PrivateCartAssetStore.open(Files.createDirectory(temp.resolve("assets"),PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------"))).toRealPath());
        api=new LocalCartCommands(ds,files);
        db.execute("CREATE DATABASE storefront_direct_test");
        storefront=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:"+PG.getMappedPort(5432)+"/storefront_direct_test",
                PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(storefront).defaultSchema("local_catalog").schemas("local_catalog")
                .locations("classpath:db/local-catalog").configuration(Map.of("flyway.postgresql.transactional.lock","false"))
                .load().migrate();
    }
    @BeforeEach void freshOwner() {
        sid=UUID.randomUUID().toString();
        buyer=login(newOwner(),sid);
    }
    private static UUID newOwner() {
        UUID owner=UUID.randomUUID();
        db.update("""
                WITH created AS (INSERT INTO public.users(id,status) VALUES (?,'ACTIVE') RETURNING id)
                INSERT INTO public.user_profiles(user_id) SELECT id FROM created
                """,owner);
        return owner;
    }
    private static Claim login(UUID owner,String session) {
        var c=api.beginLogin(api.loginEpoch(),owner,api.eligibilityGeneration(owner),session);
        assertThat(api.bind(c,session)).isEqualTo(BindResult.BOUND);
        return c;
    }

    @Test void privateLibraryVersionChangesAndCopiesNeverRewriteTheCartSnapshot() throws Exception {
        var shop=new LocalCartCommands(cartSource,files,IsolatedCatalogAuthority.from(storefront));
        var anna=StudioDesignTest.artwork();
        StudioDesignTest.withText(anna,"rounded").put("text","Anna");
        var maria=anna.deepCopy();
        ((tools.jackson.databind.node.ObjectNode)maria.get("objects").get(1)).put("text","Maria");
        UUID initial=shop.stageDesign(buyer,sid,buyer.namespace(),UUID.randomUUID(),StudioDesign.parse(anna));
        UUID updated=shop.stageDesign(buyer,sid,buyer.namespace(),UUID.randomUUID(),StudioDesign.parse(maria));
        UUID id=UUID.randomUUID(),copy=UUID.randomUUID(),create=UUID.randomUUID(),save=UUID.randomUUID(),delete=UUID.randomUUID();
        var createAnna=new SavedDesignLibrary.Change(SavedDesignLibrary.Action.SAVE,id,0,initial,null);
        var saveMaria=new SavedDesignLibrary.Change(SavedDesignLibrary.Action.SAVE,id,1,updated,null);
        var copyAnna=new SavedDesignLibrary.Change(SavedDesignLibrary.Action.COPY,id,1,null,copy);
        var remove=new SavedDesignLibrary.Change(SavedDesignLibrary.Action.DELETE,id,2,null,null);
        assertThat(shop.library(buyer,sid)).isEmpty();
        assertThat(shop.changeLibrary(buyer,sid,buyer.namespace(),create,createAnna)).isEqualTo(new SavedDesignLibrary.Result(id,"1"));
        assertThat(shop.changeLibrary(buyer,sid,buyer.namespace(),UUID.randomUUID(),copyAnna)).isEqualTo(new SavedDesignLibrary.Result(copy,"1"));
        assertThat(read(buyer.owner())).isEmpty();
        var v1=shop.openSaved(buyer,sid,id).orElseThrow();
        assertThat(shop.library(buyer,sid)).hasSize(2);
        assertThat(v1.item().caption()).isEqualTo("Anna");
        assertThat(v1.design()).isEqualTo(anna);
        UUID premature=UUID.randomUUID();
        assertThatThrownBy(()->shop.changeLibrary(buyer,sid,buyer.namespace(),premature,remove)).isInstanceOf(Conflict.class);
        var line=shop.add(buyer,sid,buyer.namespace(),UUID.randomUUID(),new Addition("cobalt",1,initial,1L)).lineId();
        assertThat(shop.changeLibrary(buyer,sid,buyer.namespace(),save,saveMaria)).isEqualTo(new SavedDesignLibrary.Result(id,"2"));
        var v2=shop.openSaved(buyer,sid,id).orElseThrow();
        assertThat(v2.design()).isEqualTo(maria);
        assertThat(v2.item().caption()).isEqualTo("Maria");
        // It was refused before version 2 existed. A later delivery of the
        // very same command cannot delete a design which is now at version 2.
        assertThatThrownBy(()->shop.changeLibrary(buyer,sid,buyer.namespace(),premature,remove)).isInstanceOf(Conflict.class);
        assertThat(shop.openSaved(buyer,sid,copy).orElseThrow().design()).isEqualTo(v1.design());
        assertThat(shop.lineDesign(buyer,sid,line).orElseThrow()).isEqualTo(v1.design());
        assertThatThrownBy(()->shop.changeLibrary(buyer,sid,buyer.namespace(),UUID.randomUUID(),saveMaria)).isInstanceOf(Conflict.class);
        assertThat(shop.changeLibrary(buyer,sid,buyer.namespace(),delete,remove)).isEqualTo(new SavedDesignLibrary.Result(id,"3"));
        assertThat(shop.openSaved(buyer,sid,id)).isEmpty();
        assertThat(shop.changeLibrary(buyer,sid,buyer.namespace(),save,saveMaria)).isEqualTo(new SavedDesignLibrary.Result(id,"2"));
        assertThat(shop.changeLibrary(buyer,sid,buyer.namespace(),create,createAnna)).isEqualTo(new SavedDesignLibrary.Result(id,"1"));
        assertThatThrownBy(()->shop.changeLibrary(buyer,sid,buyer.namespace(),UUID.randomUUID(),createAnna)).isInstanceOf(Conflict.class);
        assertThat(shop.library(buyer,sid)).extracting(SavedDesignLibrary.Item::id).containsExactly(copy);
        assertThat(shop.lineDesign(buyer,sid,line).orElseThrow()).isEqualTo(v1.design());
        assertThat(read(buyer.owner())).singleElement().extracting(PermanentCartReader.Line::quantity).isEqualTo("1");
        var next=login(newOwner(),"other-library-owner");
        assertThat(shop.library(next,"other-library-owner")).isEmpty();
        assertThat(shop.openSaved(next,"other-library-owner",copy)).isEmpty();
        assertThatThrownBy(()->shop.changeLibrary(next,"other-library-owner",next.namespace(),UUID.randomUUID(),createAnna))
                .isInstanceOf(InvalidBatch.class);
        assertThatThrownBy(()->shop.openSaved(buyer,sid,copy)).isInstanceOf(Conflict.class);
    }
    Receipt add(UUID id,long quantity) { return api.add(buyer,sid,buyer.namespace(),id,new Addition(TIDE,quantity)); }
    List<PermanentCartReader.Line> read(UUID owner) { return new PermanentCartReader(db).lines(owner).orElseThrow(); }
    List<Long> receipts(UUID owner) {
        return db.queryForList("SELECT applied_version FROM account_cart.direct_add_receipt WHERE owner_id=? AND state='APPLIED' ORDER BY applied_version",Long.class,owner);
    }

    @Test void originalPositiveReceiptsAndLinesRemainReplayableAfterTheV26Upgrade() {
        buyer=login(legacyOwner,sid);
        assertThat(add(LEGACY_INTENT,2)).isEqualTo(new Receipt("APPLIED",1L));
        assertThat(receipts(legacyOwner)).containsExactly(1L);
        assertThat(read(legacyOwner)).singleElement().satisfies(l->{assertThat(l.lineId()).isEqualTo(LEGACY_LINE);assertThat(l.quantity()).isEqualTo("2");});
    }

    @Test void aNewAdditionCanUseASlotFromAbandonedUndoWithoutChangingActiveLinesOrReplayingAnAddition() {
        UUID owner=buyer.owner(),intent=UUID.randomUUID();
        db.update("INSERT INTO account_cart.cart(owner_id) VALUES (?)",owner);
        db.update("""
                INSERT INTO account_cart.cart_line(owner_id,id,offer_id,variant_id,quantity,removed)
                SELECT ?,gen_random_uuid(),'tide','cobalt',1,true FROM generate_series(1,1000)
                """,owner);
        var applied=add(intent,2);
        assertThat(applied).isEqualTo(new Receipt("APPLIED",1L));
        var result=read(owner);
        assertThat(result).hasSize(1000);
        assertThat(result).filteredOn(l->!l.removed()).singleElement().satisfies(l->assertThat(l.quantity()).isEqualTo("2"));
        assertThat(add(intent,2)).isEqualTo(applied);
        assertThat(read(owner)).isEqualTo(result);
    }

    @Test void concurrentIdenticalClicksAndLaterReplayReturnOriginalVersionWithoutAddingTwice() throws Exception {
        UUID id=UUID.randomUUID();
        var start=new CountDownLatch(1);
        Callable<Receipt> same=()->{start.await(); return add(id,2);};
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(same);var b=pool.submit(same);start.countDown();
            assertThat(a.get(15,TimeUnit.SECONDS)).isEqualTo(new Receipt("APPLIED",1L));
            assertThat(b.get(15,TimeUnit.SECONDS)).isEqualTo(a.get());
        }
        assertThat(read(buyer.owner())).singleElement().satisfies(line->{
            assertThat(line.offerId()).isEqualTo("tide");assertThat(line.variantId()).isEqualTo("cobalt");
            assertThat(line.designLabel()).isNull(); assertThat(line.quantity()).isEqualTo("2");
        });
        var original=read(buyer.owner()).getFirst().lineId();
        assertThat(add(UUID.randomUUID(),2)).isEqualTo(new Receipt("APPLIED",2L));
        assertThat(add(id,2)).isEqualTo(new Receipt("APPLIED",1L));
        assertThatThrownBy(()->add(id,1)).isInstanceOf(Conflict.class);
        assertThatThrownBy(()->api.add(buyer,sid,buyer.namespace(),id,new Addition("kiln-blue",2))).isInstanceOf(Conflict.class);
        assertThat(read(buyer.owner())).singleElement().satisfies(line->{
            assertThat(line.lineId()).isEqualTo(original);assertThat(line.quantity()).isEqualTo("4");
        });
        assertThat(receipts(buyer.owner())).containsExactly(1L,2L);
        assertThat(db.queryForObject("SELECT count(*) FROM account_cart.import_receipt WHERE owner_id=?",Long.class,buyer.owner())).isZero();
    }

    @Test void separateIntentsCannotPassStockCeilingTogetherAndDoNotReserveInventory() throws Exception {
        var start=new CountDownLatch(1);
        Callable<Boolean> buy=()->{
            start.await();
            try { add(UUID.randomUUID(),10); return true; } catch(InvalidBatch refusal) { return false; }
        };
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(buy);var b=pool.submit(buy);start.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(read(buyer.owner())).extracting(PermanentCartReader.Line::quantity).containsExactly("10");
        assertThat(receipts(buyer.owner())).containsExactly(1L);
        assertThat(db.queryForObject("SELECT stock FROM account_cart.variant WHERE sku=?",Integer.class,TIDE)).isEqualTo(18);
    }

    @Test void receiptSurvivesReloginAndDelistingButNeverBelongsToAnotherBuyer() {
        UUID id=UUID.randomUUID(), otherId=newOwner();
        Claim original=buyer;String oldSid=sid;
        add(id,2);
        var other=login(otherId,"other");
        assertThatThrownBy(()->api.add(original,oldSid,original.namespace(),UUID.randomUUID(),new Addition(TIDE,2)))
                .isInstanceOf(Conflict.class);
        assertThatThrownBy(()->api.add(other,"other",original.namespace(),id,new Addition(TIDE,2))).isInstanceOf(Conflict.class);
        assertThatThrownBy(()->api.add(other,"other",other.namespace(),id,new Addition(TIDE,2))).isInstanceOf(Conflict.class);
        assertThat(read(otherId)).isEmpty();assertThat(receipts(otherId)).isEmpty();
        sid="fresh-session"; buyer=login(original.owner(),sid);
        db.update("UPDATE account_cart.offer SET listed=false WHERE id='tide'");
        try {
            assertThat(add(id,2)).isEqualTo(new Receipt("APPLIED",1L));
            assertThatThrownBy(()->add(UUID.randomUUID(),1)).isInstanceOf(InvalidBatch.class);
            assertThat(receipts(buyer.owner())).containsExactly(1L);
            assertThat(read(buyer.owner())).extracting(PermanentCartReader.Line::quantity).containsExactly("2");
        } finally { db.update("UPDATE account_cart.offer SET listed=true WHERE id='tide'"); }
    }

    @Test void rollbackAtCommitLeavesNoLineVersionOrReceiptAndOnlyTheSameIntentIsNeededToRetry() {
        var id=UUID.randomUUID();
        db.execute("""
                CREATE FUNCTION account_cart_fixture.reject_direct_add_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Synthetic test rollback'; END $$;
                CREATE CONSTRAINT TRIGGER reject_direct_add_test AFTER INSERT ON account_cart.direct_add_receipt
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_cart_fixture.reject_direct_add_test()
                """);
        try {
            assertThatThrownBy(()->add(id,2)).isInstanceOf(Unavailable.class);
            // A failure to COMMIT a negative decision is also unknown, never terminal 422.
            assertThatThrownBy(()->add(UUID.randomUUID(),99)).isInstanceOf(Unavailable.class);
            assertThat(db.queryForObject("SELECT count(*) FROM account_cart.direct_add_receipt WHERE owner_id=?",Long.class,buyer.owner())).isZero();
            assertThat(read(buyer.owner())).isEmpty();assertThat(receipts(buyer.owner())).isEmpty();
            assertThat(db.queryForObject("SELECT count(*) FROM account_cart.cart WHERE owner_id=?",Long.class,buyer.owner())).isZero();
        } finally { db.execute("DROP TRIGGER reject_direct_add_test ON account_cart.direct_add_receipt"); }
        assertThat(add(id,2)).isEqualTo(new Receipt("APPLIED",1L));
        assertThat(add(id,2)).isEqualTo(new Receipt("APPLIED",1L));
    }

    @Test void unsupportedSkuRecordsARefusalWhilePermanentlyMalformedRequestsAreNeverCredited() {
        for (var bad:List.of(new Addition("kiln-blue",1),new Addition(TIDE,0),new Addition(TIDE,Long.MAX_VALUE)))
            assertThatThrownBy(()->api.add(buyer,sid,buyer.namespace(),UUID.randomUUID(),bad)).isInstanceOf(InvalidBatch.class);
        assertThat(read(buyer.owner())).isEmpty();assertThat(receipts(buyer.owner())).isEmpty();
        assertThat(db.queryForList("SELECT state FROM account_cart.direct_add_receipt WHERE owner_id=?",String.class,buyer.owner())).containsExactly("REJECTED");
    }

    @Test void delayedOriginalNeverBecomesAPurchaseAfterTheBuyerRetriesWithANewClick() {
        UUID rejected=UUID.randomUUID(), nextClick=UUID.randomUUID();
        db.update("UPDATE account_cart.variant SET stock=1 WHERE sku=?",TIDE);
        try {
            assertThatThrownBy(()->add(rejected,2)).isInstanceOf(InvalidBatch.class);
        } finally { db.update("UPDATE account_cart.variant SET stock=18 WHERE sku=?",TIDE); }
        assertThat(db.queryForObject("SELECT count(*) FROM account_cart.cart WHERE owner_id=?",Long.class,buyer.owner())).isZero();
        assertThat(add(nextClick,2)).isEqualTo(new Receipt("APPLIED",1L));
        // A late transport copy of the FIRST click must not credit another two.
        assertThatThrownBy(()->add(rejected,2)).isInstanceOf(InvalidBatch.class);
        assertThat(read(buyer.owner())).extracting(PermanentCartReader.Line::quantity).containsExactly("2");
        assertThat(receipts(buyer.owner())).containsExactly(1L);
        assertThat(db.queryForObject("SELECT state FROM account_cart.direct_add_receipt WHERE intent_id=?",String.class,rejected)).isEqualTo("REJECTED");
        assertThat(db.queryForObject("SELECT count(*) FROM account_cart.import_receipt WHERE owner_id=?",Long.class,buyer.owner())).isZero();
        assertThat(db.queryForObject("SELECT stock FROM account_cart.variant WHERE sku=?",Integer.class,TIDE)).isEqualTo(18);
    }

    @Test void anUnknownSkuRefusalRemainsTerminalAfterListingAndConflictsOnOwnerOrPayloadChange() {
        String sku="test-new-cup";
        UUID id=UUID.randomUUID();
        var item=new Addition(sku,1);
        assertThatThrownBy(()->api.add(buyer,sid,buyer.namespace(),id,item)).isInstanceOf(InvalidBatch.class);
        db.update("INSERT INTO account_cart.variant VALUES ('tide','test-new',?,'New',1800,18,'#244bcc')",sku);
        db.update("INSERT INTO account_cart.direct_add_variant VALUES (?)",sku);
        assertThatThrownBy(()->api.add(buyer,sid,buyer.namespace(),id,item)).isInstanceOf(InvalidBatch.class);
        assertThatThrownBy(()->api.add(buyer,sid,buyer.namespace(),id,new Addition(sku,2))).isInstanceOf(Conflict.class);
        assertThatThrownBy(()->add(id,1)).isInstanceOf(Conflict.class);
        var other=login(newOwner(),"other-new-cup");
        assertThatThrownBy(()->api.add(other,"other-new-cup",other.namespace(),id,item)).isInstanceOf(Conflict.class);
        assertThat(read(buyer.owner())).isEmpty();assertThat(read(other.owner())).isEmpty();
        assertThat(db.queryForObject("SELECT count(*) FROM account_cart.direct_add_receipt WHERE intent_id=? AND state='REJECTED'",Long.class,id)).isOne();
        assertThat(api.add(other,"other-new-cup",other.namespace(),UUID.randomUUID(),item)).isEqualTo(new Receipt("APPLIED",1L));
    }

    @Test void anUnseededSkuAndPersonalisedRevisionComeFromTheStorefrontNotFromACartAllowlist() throws Exception {
        // The same disposable cluster serves two separate databases; no account-side mapping,
        // allowlist, SKU convention, or hardcoded offer ID is inserted for this new variant.
        var merchandise=new JdbcTemplate(storefront);
        String sku="journal-limited-ginger";
        merchandise.update("""
                INSERT INTO local_catalog.variant(product_id,id,sku,cents,stock,colors,check_tone,name,photos,display_order)
                SELECT 'folio','ginger',?,1450,7,colors,check_tone,name,photos,99 FROM local_catalog.variant
                 WHERE product_id='folio' ORDER BY id LIMIT 1
                """,sku);
        merchandise.update("""
                INSERT INTO local_catalog.product_studio_template VALUES ('folio',2,jsonb_set(?::jsonb,'{variants}','["ginger"]'))
                """,StudioDesign.TEMPLATE_V2);
        merchandise.update("UPDATE local_catalog.product SET personalizable=true,active_studio_revision=2 WHERE id='folio'");
        assertThat(db.queryForObject("SELECT count(*) FROM account_cart.variant WHERE sku=?",Long.class,sku)).isZero();
        var shop=new LocalCartCommands(cartSource,files,IsolatedCatalogAuthority.from(storefront));
        var input=StudioDesignTest.artwork().put("sku",sku);
        StudioDesignTest.withText(input,"rounded").put("text","Eve");
        var original=shop.stageDesign(buyer,sid,buyer.namespace(),UUID.randomUUID(),StudioDesign.parse(input));
        var equivalent=shop.stageDesign(buyer,sid,buyer.namespace(),UUID.randomUUID(),StudioDesign.parse(input));
        var different=input.deepCopy(); ((tools.jackson.databind.node.ObjectNode)different.get("objects").get(1)).put("text","Ana");
        var otherDesign=shop.stageDesign(buyer,sid,buyer.namespace(),UUID.randomUUID(),StudioDesign.parse(different));
        UUID click=UUID.randomUUID(); var decorated=new Addition(sku,2,original,1L);
        var first=shop.add(buyer,sid,buyer.namespace(),click,decorated);
        assertThat(first.lineId()).isNotNull();
        assertThat(shop.add(buyer,sid,buyer.namespace(),click,decorated)).isEqualTo(first);
        assertThatThrownBy(()->shop.add(buyer,sid,buyer.namespace(),click,new Addition(sku,2,equivalent,1L))).isInstanceOf(Conflict.class);
        var equivalentCredit=shop.add(buyer,sid,buyer.namespace(),UUID.randomUUID(),new Addition(sku,1,equivalent,1L));
        assertThat(equivalentCredit.lineId()).isEqualTo(first.lineId());
        var another=shop.add(buyer,sid,buyer.namespace(),UUID.randomUUID(),new Addition(sku,2,otherDesign,1L));
        assertThat(another.lineId()).isNotEqualTo(first.lineId());
        shop.add(buyer,sid,buyer.namespace(),UUID.randomUUID(),new Addition(sku,2));
        UUID soldOut=UUID.randomUUID();
        assertThatThrownBy(()->shop.add(buyer,sid,buyer.namespace(),soldOut,new Addition(sku,1))).isInstanceOf(InvalidBatch.class);
        merchandise.update("UPDATE local_catalog.variant SET stock=12 WHERE sku=?",sku);
        assertThatThrownBy(()->shop.add(buyer,sid,buyer.namespace(),soldOut,new Addition(sku,1))).isInstanceOf(InvalidBatch.class);
        // Not enough to know a private ID; a replay cannot change the target or the revision.
        for(var wrong:List.of(new Addition(TIDE,1,original,1L),new Addition(sku,1,original,2L)))
            assertThatThrownBy(()->shop.add(buyer,sid,buyer.namespace(),UUID.randomUUID(),wrong)).isInstanceOf(InvalidBatch.class);
        var foreign=login(newOwner(),"foreign-journal");
        assertThatThrownBy(()->shop.add(foreign,"foreign-journal",foreign.namespace(),UUID.randomUUID(),decorated)).isInstanceOf(InvalidBatch.class);
        assertThat(shop.lineDesign(foreign,"foreign-journal",first.lineId())).isEmpty();
        var same=login(buyer.owner(),"re-open-journal");
        assertThat(shop.lineDesign(same,"re-open-journal",first.lineId())).contains(input);
        assertThat(shop.lineDesign(same,"re-open-journal",another.lineId())).contains(different);
        assertThat(read(buyer.owner())).extracting(PermanentCartReader.Line::quantity).containsExactlyInAnyOrder("3","2","2");
        merchandise.update("UPDATE local_catalog.product SET active_studio_revision=NULL WHERE id='folio'");
        assertThat(shop.add(same,"re-open-journal",same.namespace(),click,decorated)).isEqualTo(first);
        assertThatThrownBy(()->shop.add(same,"re-open-journal",same.namespace(),UUID.randomUUID(),decorated)).isInstanceOf(InvalidBatch.class);
        assertThat(shop.lineDesign(same,"re-open-journal",first.lineId())).contains(input);
        assertThat(receipts(buyer.owner())).containsExactly(1L,2L,3L,4L);
        assertThat(db.queryForObject("SELECT count(*) FROM account_cart.direct_add_variant WHERE sku=?",Long.class,sku)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM account_cart.import_receipt WHERE owner_id=?",Long.class,buyer.owner())).isZero();
        // The owner can still import their previously applied composition; an import isn't a
        // new shopping click. It preserves demand even after the seller turns the studio off.
        var batch=UUID.randomUUID();
        var guest=List.of(new Item(sku,equivalent,1L,1));
        assertThat(shop.prepare(same,"re-open-journal",same.namespace(),batch,guest)).isEqualTo(new Receipt("PENDING",null));
        assertThat(shop.apply(same,"re-open-journal",same.namespace(),batch)).isEqualTo(new Receipt("APPLIED",5L));
        assertThat(shop.apply(same,"re-open-journal",same.namespace(),batch)).isEqualTo(new Receipt("APPLIED",5L));
        assertThat(read(buyer.owner())).filteredOn(l->l.lineId().equals(first.lineId())).singleElement()
                .satisfies(l->assertThat(l.quantity()).isEqualTo("4"));
    }

    @Test void sharedGuestTransferPreservesKnownWithdrawnDemandAndNeverAdoptsFixtureOnlySkus() {
        var merchandise=new JdbcTemplate(storefront);
        String sku="gift-small-run";
        merchandise.update("""
                INSERT INTO local_catalog.variant(product_id,id,sku,cents,stock,colors,check_tone,name,photos,display_order)
                SELECT product_id,'limited',?,1450,1,colors,check_tone,name,photos,99 FROM local_catalog.variant
                 WHERE product_id='drift' ORDER BY id LIMIT 1
                """,sku);
        var shop=new LocalCartCommands(cartSource,files,IsolatedCatalogAuthority.from(storefront));
        var guest=List.of(new Item(sku,null,null,4)); var batch=UUID.randomUUID();
        assertThat(shop.prepare(buyer,sid,buyer.namespace(),batch,guest).state()).isEqualTo("PENDING");
        merchandise.update("DELETE FROM local_catalog.variant WHERE sku=?",sku);
        assertThat(shop.apply(buyer,sid,buyer.namespace(),batch)).isEqualTo(new Receipt("APPLIED",1L));
        assertThat(shop.apply(buyer,sid,buyer.namespace(),batch)).isEqualTo(new Receipt("APPLIED",1L));
        assertThat(read(buyer.owner())).extracting(PermanentCartReader.Line::quantity).containsExactly("4");
        assertThatThrownBy(()->shop.add(buyer,sid,buyer.namespace(),UUID.randomUUID(),new Addition(sku,1))).isInstanceOf(InvalidBatch.class);
        assertThatThrownBy(()->shop.prepare(buyer,sid,buyer.namespace(),UUID.randomUUID(),List.of(new Item("kiln-blue",null,null,1))))
                .isInstanceOf(InvalidBatch.class);
    }

    @Test void absoluteEditsKeepTheirReceiptAndUseStockAcrossTheVariant() {
        var source=IsolatedCatalogAuthority.from(storefront);
        var shop=new LocalCartCommands(cartSource,files,source);
        var view=new PermanentCartReader(db,source);
        assertThat(view.snapshot(buyer.owner()).orElseThrow().version()).isZero();
        shop.add(buyer,sid,buyer.namespace(),UUID.randomUUID(),new Addition(TIDE,2));
        var original=view.lines(buyer.owner()).orElseThrow().getFirst().lineId();
        var change=new Edit(original,EditAction.QUANTITY,1,3L);
        UUID id=UUID.randomUUID();
        assertThat(shop.edit(buyer,sid,buyer.namespace(),id,change)).isEqualTo(new Receipt("APPLIED",2L));
        assertThatThrownBy(()->shop.edit(buyer,sid,buyer.namespace(),id,new Edit(original,EditAction.QUANTITY,1,4L))).isInstanceOf(Conflict.class);
        assertThatThrownBy(()->shop.edit(buyer,sid,buyer.namespace(),UUID.randomUUID(),new Edit(original,EditAction.REMOVE,1,null))).isInstanceOf(Conflict.class);
        assertThat(shop.edit(buyer,sid,buyer.namespace(),UUID.randomUUID(),new Edit(original,EditAction.QUANTITY,2,3L)).version()).isEqualTo(2);
        var removal=new Edit(original,EditAction.REMOVE,2,null);
        var removeId=UUID.randomUUID();
        assertThat(shop.edit(buyer,sid,buyer.namespace(),removeId,removal).version()).isEqualTo(3);
        assertThat(shop.edit(buyer,sid,buyer.namespace(),id,change).version()).isEqualTo(2);
        assertThat(view.snapshot(buyer.owner()).orElseThrow()).satisfies(cart->{
            assertThat(cart.version()).isEqualTo(3);
            assertThat(cart.lines()).singleElement().satisfies(l->{assertThat(l.removed()).isTrue();assertThat(l.quantity()).isEqualTo("3");});
        });
        shop.add(buyer,sid,buyer.namespace(),UUID.randomUUID(),new Addition(TIDE,18));
        var undo=new Edit(original,EditAction.RESTORE,4,null);
        var undoId=UUID.randomUUID();
        assertThatThrownBy(()->shop.edit(buyer,sid,buyer.namespace(),undoId,undo)).isInstanceOf(InvalidBatch.class);
        var otherLine=view.lines(buyer.owner()).orElseThrow().stream().filter(l->!l.removed()).findFirst().orElseThrow().lineId();
        shop.edit(buyer,sid,buyer.namespace(),UUID.randomUUID(),new Edit(otherLine,EditAction.QUANTITY,4,1L));
        assertThatThrownBy(()->shop.edit(buyer,sid,buyer.namespace(),undoId,undo)).isInstanceOf(InvalidBatch.class);
        assertThat(shop.edit(buyer,sid,buyer.namespace(),UUID.randomUUID(),new Edit(original,EditAction.RESTORE,5,null)).version()).isEqualTo(6);
        assertThat(view.lines(buyer.owner()).orElseThrow()).singleElement().satisfies(l->{
            assertThat(l.lineId()).isEqualTo(original);assertThat(l.quantity()).isEqualTo("4");assertThat(l.removed()).isFalse();});
        assertThat(shop.edit(buyer,sid,buyer.namespace(),removeId,removal).version()).isEqualTo(3);
        var other=login(newOwner(),"line-edit-next-owner");
        assertThatThrownBy(()->shop.edit(other,"line-edit-next-owner",other.namespace(),id,change)).isInstanceOf(Conflict.class);
        assertThatThrownBy(()->shop.edit(buyer,sid,buyer.namespace(),UUID.randomUUID(),new Edit(original,EditAction.REMOVE,6,null))).isInstanceOf(Conflict.class);
        assertThat(view.snapshot(buyer.owner()).orElseThrow().version()).isEqualTo(6);
        assertThat(view.lines(other.owner()).orElseThrow()).isEmpty();
    }

    @Test void undoRestoresOriginalPrivateDesignAndMergesOnlyEquivalentNewGuestLines() {
        var shop=new LocalCartCommands(cartSource,files,IsolatedCatalogAuthority.from(storefront));
        var input=StudioDesignTest.artwork();StudioDesignTest.withText(input,"rounded").put("text","Anna");
        var original=shop.stageDesign(buyer,sid,buyer.namespace(),UUID.randomUUID(),StudioDesign.parse(input));
        var equivalent=shop.stageDesign(buyer,sid,buyer.namespace(),UUID.randomUUID(),StudioDesign.parse(input));
        var diff=input.deepCopy();((tools.jackson.databind.node.ObjectNode)diff.get("objects").get(1)).put("text","Joy");
        var different=shop.stageDesign(buyer,sid,buyer.namespace(),UUID.randomUUID(),StudioDesign.parse(diff));
        UUID click=UUID.randomUUID();var first=new Addition("cobalt",2,original,1L);var bought=shop.add(buyer,sid,buyer.namespace(),click,first);
        UUID line=bought.lineId();
        shop.edit(buyer,sid,buyer.namespace(),UUID.randomUUID(),new Edit(line,EditAction.REMOVE,1,null));
        var batch=UUID.randomUUID();
        shop.prepare(buyer,sid,buyer.namespace(),batch,List.of(new Item("cobalt",equivalent,1L,1),new Item("cobalt",different,1L,2)));
        var imported=shop.apply(buyer,sid,buyer.namespace(),batch); assertThat(imported.version()).isEqualTo(3);
        assertThat(read(buyer.owner())).hasSize(3);
        assertThat(shop.add(buyer,sid,buyer.namespace(),click,first)).isEqualTo(bought);
        var second=login(buyer.owner(),"line-undo-after-login");
        var undo=new Edit(line,EditAction.RESTORE,3,null);
        UUID id=UUID.randomUUID();
        assertThat(shop.edit(second,"line-undo-after-login",second.namespace(),id,undo).version()).isEqualTo(4);
        assertThat(shop.edit(second,"line-undo-after-login",second.namespace(),id,undo).version()).isEqualTo(4);
        assertThat(shop.apply(second,"line-undo-after-login",second.namespace(),batch)).isEqualTo(imported);
        assertThat(read(buyer.owner())).hasSize(2).allMatch(l->!l.removed());
        assertThat(read(buyer.owner()).stream().filter(l->l.lineId().equals(line))).singleElement().satisfies(l->{
            assertThat(l.designId()).isEqualTo(original);assertThat(l.quantity()).isEqualTo("3");});
        assertThat(shop.lineDesign(second,"line-undo-after-login",line)).contains(input);
        shop.edit(second,"line-undo-after-login",second.namespace(),UUID.randomUUID(),new Edit(line,EditAction.REMOVE,4,null));
        var dismiss=new Edit(line,EditAction.DISMISS,5,null);UUID dismissId=UUID.randomUUID();
        assertThat(shop.edit(second,"line-undo-after-login",second.namespace(),dismissId,dismiss).version()).isEqualTo(6);
        assertThat(shop.edit(second,"line-undo-after-login",second.namespace(),dismissId,dismiss).version()).isEqualTo(6);
        assertThat(shop.lineDesign(second,"line-undo-after-login",line)).isEmpty();
        assertThat(db.queryForObject("SELECT count(*) FROM account_cart.design_asset WHERE owner_id=? AND design_id=?",Long.class,buyer.owner(),original)).isPositive();
        assertThat(read(buyer.owner())).singleElement().satisfies(l->assertThat(l.designId()).isEqualTo(different));
    }

    @Test void twoTabsEditingTheSameCartVersionCannotOverwriteOneAnother() throws Exception {
        var shop=new LocalCartCommands(cartSource,files,IsolatedCatalogAuthority.from(storefront));
        shop.add(buyer,sid,buyer.namespace(),UUID.randomUUID(),new Addition(TIDE,2));
        var row=read(buyer.owner()).getFirst().lineId();var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var results=List.of(3L,5L).stream().map(q->pool.submit(()->{
                start.await();
                try{return shop.edit(buyer,sid,buyer.namespace(),UUID.randomUUID(),new Edit(row,EditAction.QUANTITY,1,q)).state();}
                catch(Conflict stale){return "STALE";}
            })).toList();
            start.countDown();
            assertThat(List.of(results.get(0).get(15,TimeUnit.SECONDS),results.get(1).get(15,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("APPLIED","STALE");
        }
        assertThat(read(buyer.owner())).singleElement().satisfies(l->assertThat(l.quantity()).isIn("3","5"));
        assertThat(db.queryForObject("SELECT version FROM account_cart.cart WHERE owner_id=?",Long.class,buyer.owner())).isEqualTo(2);
    }

    @Test void faultAtReceiptCommitRollsBackTheRemovedFlagAndCartVersion() {
        var shop=new LocalCartCommands(cartSource,files,IsolatedCatalogAuthority.from(storefront));
        shop.add(buyer,sid,buyer.namespace(),UUID.randomUUID(),new Addition(TIDE,2));
        var row=read(buyer.owner()).getFirst().lineId();var id=UUID.randomUUID();var remove=new Edit(row,EditAction.REMOVE,1,null);
        db.execute("""
                CREATE FUNCTION account_cart_fixture.rollback_edit_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'edit fixture'; END $$;
                CREATE CONSTRAINT TRIGGER rollback_edit_test AFTER INSERT ON account_cart.line_edit_receipt
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_cart_fixture.rollback_edit_test()
                """);
        try {
            assertThatThrownBy(()->shop.edit(buyer,sid,buyer.namespace(),id,remove)).isInstanceOf(Unavailable.class);
            assertThat(new PermanentCartReader(db).snapshot(buyer.owner()).orElseThrow()).satisfies(c->{
                assertThat(c.version()).isEqualTo(1);assertThat(c.lines().getFirst().removed()).isFalse();});
            assertThat(db.queryForObject("SELECT count(*) FROM account_cart.line_edit_receipt WHERE intent_id=?",Long.class,id)).isZero();
        } finally {db.execute("DROP TRIGGER rollback_edit_test ON account_cart.line_edit_receipt; DROP FUNCTION account_cart_fixture.rollback_edit_test()");}
        assertThat(shop.edit(buyer,sid,buyer.namespace(),id,remove).version()).isEqualTo(2);
    }

    @Test void retiredItemsCanBeReducedOrRemovedButCannotBeIncreasedOrRestored() {
        var shop=new LocalCartCommands(cartSource,files,IsolatedCatalogAuthority.from(storefront));
        String sku="retiring-slate";var merchandise=new JdbcTemplate(storefront);
        merchandise.update("""
                INSERT INTO local_catalog.variant(product_id,id,sku,cents,stock,colors,check_tone,name,photos,display_order)
                SELECT product_id,?,?,1450,7,colors,check_tone,name,photos,99 FROM local_catalog.variant
                 WHERE product_id='drift' ORDER BY id LIMIT 1
                """,sku,sku);
        shop.add(buyer,sid,buyer.namespace(),UUID.randomUUID(),new Addition(sku,4));
        var line=read(buyer.owner()).getFirst().lineId();
        merchandise.update("DELETE FROM local_catalog.variant WHERE sku=?",sku);
        assertThatThrownBy(()->shop.edit(buyer,sid,buyer.namespace(),UUID.randomUUID(),new Edit(line,EditAction.QUANTITY,1,5L))).isInstanceOf(InvalidBatch.class);
        assertThat(shop.edit(buyer,sid,buyer.namespace(),UUID.randomUUID(),new Edit(line,EditAction.QUANTITY,1,3L)).version()).isEqualTo(2);
        assertThat(shop.edit(buyer,sid,buyer.namespace(),UUID.randomUUID(),new Edit(line,EditAction.REMOVE,2,null)).version()).isEqualTo(3);
        assertThatThrownBy(()->shop.edit(buyer,sid,buyer.namespace(),UUID.randomUUID(),new Edit(line,EditAction.RESTORE,3,null))).isInstanceOf(InvalidBatch.class);
        assertThat(shop.edit(buyer,sid,buyer.namespace(),UUID.randomUUID(),new Edit(line,EditAction.DISMISS,3,null)).version()).isEqualTo(4);
    }
}
