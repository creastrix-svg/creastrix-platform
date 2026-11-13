package com.creastrix.platform.accountcart;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.creastrix.platform.accountcart.persistence.PermanentCartReader;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PermanentCartPostgresTest {
    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("creastrix_account_cart_local").withUsername("account_cart_local_app")
            .withCreateContainerCmdModifier(c -> c.getHostConfig().withNetworkMode("bridge").withPublishAllPorts(false)
                    .withPortBindings(new Ports(ExposedPort.tcp(5432), Ports.Binding.bindIp("127.0.0.1"))));
    private static JdbcTemplate sql;
    private static TransactionTemplate tx;
    private static PreparedCartImportStore imports;
    private static PermanentCartReader reader;
    private static final UUID BUYER=UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID MOTIF=UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID FIXTURE_ASSET=UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID LEGACY_UNPROVEN=UUID.randomUUID(), LEGACY_PINNED=UUID.randomUUID();

    @BeforeAll static void initialise() throws Exception {
        var source = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:"+DATABASE.getMappedPort(5432)+"/"+DATABASE.getDatabaseName(),
                DATABASE.getUsername(),DATABASE.getPassword());
        var config=Flyway.configure().dataSource(source).locations("classpath:db/migration","classpath:db/account-cart-local")
                .configuration(Map.of("flyway.postgresql.transactional.lock","false"));
        config.target("17").load().migrate();
        sql = new JdbcTemplate(source);
        String fixture=new ClassPathResource("account-cart-local/fixture.sql").getContentAsString(StandardCharsets.UTF_8);
        sql.execute(fixture);
        sql.execute(fixture);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        // A populated V17 upgrade: deliberately forget a former pending-only pin
        // exactly as the old schema allowed. V18 must not backfill it as never linked.
        tx.executeWithoutResult(s->{
            for (var asset : List.of(LEGACY_UNPROVEN,LEGACY_PINNED)) {
                sql.update("INSERT INTO account_cart.asset(owner_id,asset_id,kind) VALUES (?,?,'IMAGE')",BUYER,asset);
                var batch=pin(BUYER,asset);
                if (asset.equals(LEGACY_UNPROVEN)) releasePin(BUYER,batch);
            }
        });
        var migrations=config.target("18").load();
        migrations.migrate();
        assertThat(migrations.info().current().getVersion().toString()).isEqualTo("18");
        // Keep the populated V17→V18 assertion independent of later opt-in migrations.
        config.target("latest").load().migrate();
        imports = new PreparedCartImportStore(source);
        reader = new PermanentCartReader(sql);
    }

    private static PreparedCartImportStore.Admission owner() {
        UUID owner=UUID.randomUUID(), scope=UUID.randomUUID();
        tx.executeWithoutResult(s->{
            sql.update("INSERT INTO users(id) VALUES (?)",owner);
            sql.update("INSERT INTO user_profiles(user_id) VALUES (?)",owner);
            sql.update("INSERT INTO account_cart.write_scope(id,generation,owner_id) VALUES (?,1,?)",scope,owner);
        });
        return new PreparedCartImportStore.Admission(scope,1,owner,1);
    }

    private static UUID prepare(UUID owner, Long quantity) {
        UUID batch=UUID.randomUUID();
        tx.executeWithoutResult(s->{
            sql.update("INSERT INTO account_cart.import_receipt(owner_id,batch_id,request_bytes) VALUES (?,?,?)",
                    owner,batch,("test-trusted:"+quantity).getBytes(StandardCharsets.UTF_8));
            if (quantity!=null) sql.update("""
                    INSERT INTO account_cart.import_line(owner_id,batch_id,position,offer_id,variant_id,quantity)
                    VALUES (?,?,0,'kiln-cup','blue',?)
                    """,owner,batch,quantity);
        });
        return batch;
    }

    @Test void readsAuthorisedRowsFromOneSnapshotAndForeignOwnerCannotReferenceTheDesign() {
        assertThat(reader.lines(BUYER).orElseThrow()).extracting(PermanentCartReader.Line::designLabel)
                .containsExactlyInAnyOrder(null,"Checker motif");
        var other=owner().owner();
        assertThat(reader.lines(other).orElseThrow()).isEmpty();
        assertThat(reader.lines(UUID.randomUUID())).isEmpty();
        sql.update("INSERT INTO account_cart.cart(owner_id) VALUES (?)",other);
        assertThatThrownBy(()->sql.update("""
                INSERT INTO account_cart.cart_line(owner_id,id,offer_id,variant_id,design_id,design_revision,quantity)
                VALUES (?,?,'kiln-cup','blue',?,1,1)
                """,other,UUID.randomUUID(),MOTIF)).isInstanceOf(DataAccessException.class);
    }

    @Test void secondFinishColourIsReadFromPublishedVariantPresentation() {
        tx.executeWithoutResult(status -> {
            sql.update("""
                    INSERT INTO account_cart.variant_presentation(offer_id,variant_id,second_swatch)
                    VALUES ('kiln-cup','blue','#eeeecc') ON CONFLICT (offer_id,variant_id)
                    DO UPDATE SET second_swatch=EXCLUDED.second_swatch
                    """);
            assertThat(reader.lines(BUYER).orElseThrow()).filteredOn(line -> "kiln-cup".equals(line.offerId()) && "blue".equals(line.variantId()))
                    .isNotEmpty().allMatch(line -> "#eeeecc".equals(line.secondSwatch()));
            status.setRollbackOnly();
        });
    }

    @Test void readsOnlyExactRetainedFixtureBytesFromTheOwnersLine(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        var files = com.creastrix.platform.accountcart.assets.PrivateCartAssetStore.open(dir.toRealPath());
        var artwork = new com.creastrix.platform.accountcart.persistence.CartLineArtworkReader(sql, files);
        UUID line = UUID.fromString("50000000-0000-4000-8000-000000000002");
        assertThat(artwork.read(BUYER, line)).isEmpty(); // PG metadata without its object is not success.
        com.creastrix.platform.accountcart.assets.LocalCartFixtureAsset.ensurePresent(files);
        assertThat(artwork.read(BUYER, line).orElseThrow())
                .isEqualTo(com.creastrix.platform.accountcart.assets.LocalCartFixtureAsset.expectedBytes());
        assertThat(artwork.read(BUYER, UUID.fromString("50000000-0000-4000-8000-000000000001"))).isEmpty();
        assertThat(artwork.read(owner().owner(), line)).isEmpty();
        assertThat(artwork.read(BUYER, UUID.randomUUID())).isEmpty();
        UUID secondKey = UUID.randomUUID();
        files.writeNew(secondKey, com.creastrix.platform.accountcart.assets.PrivateCartAssetStore.Kind.IMAGE,
                com.creastrix.platform.accountcart.assets.LocalCartFixtureAsset.expectedBytes());
        // A later design revision must neither replace the old resource nor make
        // an ambiguous revision resolve to the first asset returned by the database.
        tx.executeWithoutResult(status -> {
            UUID second = UUID.randomUUID(), secondLine = UUID.randomUUID();
            sql.update("""
                    INSERT INTO account_cart.design_revision(owner_id,design_id,revision,offer_id,variant_id,template_revision,label,canonical_bytes)
                    SELECT owner_id,design_id,revision+1,offer_id,variant_id,template_revision,'Two images',canonical_bytes
                    FROM account_cart.design_revision WHERE owner_id=? AND design_id=? AND revision=1
                    """, BUYER,MOTIF);
            sql.update("""
                    INSERT INTO account_cart.asset(owner_id,asset_id,kind,object_key,byte_count,sha256)
                    SELECT owner_id,?,'IMAGE',?,byte_count,sha256 FROM account_cart.asset WHERE owner_id=? AND asset_id=?
                    """,second,secondKey,BUYER,FIXTURE_ASSET);
            for (UUID id : List.of(second,FIXTURE_ASSET))
                sql.update("INSERT INTO account_cart.design_asset(owner_id,design_id,revision,asset_id) VALUES (?,?,2,?)", BUYER,MOTIF,id);
            sql.update("""
                    INSERT INTO account_cart.cart_line(owner_id,id,offer_id,variant_id,design_id,design_revision,quantity)
                    SELECT owner_id,?,offer_id,variant_id,design_id,2,quantity FROM account_cart.cart_line WHERE owner_id=? AND id=?
                    """,secondLine,BUYER,line);
            assertThat(artwork.read(BUYER,secondLine)).isEmpty();
            assertThat(artwork.read(BUYER,line).orElseThrow())
                    .isEqualTo(com.creastrix.platform.accountcart.assets.LocalCartFixtureAsset.expectedBytes());
            status.setRollbackOnly();
        });
    }

    @Test void duplicateConcurrentApplyReturnsOneReceiptAndNeverCreditsAnInterveningEditAgain() throws Exception {
        var auth=owner();
        UUID batch=prepare(auth.owner(),3L);
        var barrier=new CyclicBarrier(2);
        var pool=Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Long> work=()->{
                barrier.await(5,TimeUnit.SECONDS);
                return imports.apply(auth,batch).version();
            };
            var first=pool.submit(work); var second=pool.submit(work);
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactly(1L,1L);
        } finally { pool.shutdownNow(); }
        assertThat(reader.lines(auth.owner()).orElseThrow()).extracting(PermanentCartReader.Line::quantity).containsExactly("3");
        tx.executeWithoutResult(s->{
            sql.update("UPDATE account_cart.cart SET version=2 WHERE owner_id=?",auth.owner());
            sql.update("UPDATE account_cart.cart_line SET quantity=1 WHERE owner_id=?",auth.owner());
        });
        assertThat(imports.apply(auth,batch).version()).isEqualTo(1);
        assertThat(reader.lines(auth.owner()).orElseThrow()).extracting(PermanentCartReader.Line::quantity).containsExactly("1");
        var independent=prepare(auth.owner(),3L);
        assertThat(imports.apply(auth,independent).version()).isEqualTo(3);
        assertThat(reader.lines(auth.owner()).orElseThrow()).extracting(PermanentCartReader.Line::quantity).containsExactly("4");
    }

    @Test void commitFailureRollsBackPrivateChangesAndRetainsTheExactPreparation() {
        var auth=owner(); var batch=prepare(auth.owner(),4L);
        sql.execute("""
                CREATE FUNCTION account_cart_fixture.fail_apply_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'synthetic failure before commit' USING ERRCODE='23514'; END $$;
                CREATE CONSTRAINT TRIGGER deferred_test_receipt
                AFTER UPDATE ON account_cart.import_receipt DEFERRABLE INITIALLY DEFERRED
                FOR EACH ROW WHEN (NEW.batch_id='%s') EXECUTE FUNCTION account_cart_fixture.fail_apply_test()
                """.formatted(batch));
        assertThatThrownBy(()->imports.apply(auth,batch)).rootCause()
                .isInstanceOfSatisfying(org.postgresql.util.PSQLException.class, failure -> {
                    assertThat(failure.getSQLState()).isEqualTo("23514");
                    assertThat(failure.getServerErrorMessage().getMessage()).isEqualTo("synthetic failure before commit");
                });
        assertThat(reader.lines(auth.owner()).orElseThrow()).isEmpty();
        assertThat(sql.queryForObject("SELECT state FROM account_cart.import_receipt WHERE owner_id=? AND batch_id=?",
                String.class,auth.owner(),batch)).isEqualTo("PENDING");
        assertThat(sql.queryForObject("SELECT count(*) FROM account_cart.import_line WHERE owner_id=? AND batch_id=?",
                Long.class,auth.owner(),batch)).isEqualTo(1);
    }

    @Test void staleAdmissionAndSuspendedOwnerCannotApplyAndAnEmptyBatchCreatesNoCart() {
        var auth=owner(); var empty=prepare(auth.owner(),null);
        assertThat(imports.apply(auth,empty).version()).isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM account_cart.cart WHERE owner_id=?",Long.class,auth.owner())).isZero();
        var batch=prepare(auth.owner(),2L);
        sql.update("UPDATE account_cart.write_scope SET generation=2 WHERE id=?",auth.scope());
        assertThatThrownBy(()->imports.apply(auth,batch)).hasMessage("CART_ADMISSION_REFUSED");
        sql.update("UPDATE users SET status='SUSPENDED' WHERE id=?",auth.owner());
        assertThatThrownBy(()->imports.apply(new PreparedCartImportStore.Admission(auth.scope(),2,auth.owner(),auth.eligibilityGeneration()),batch))
                .hasMessage("CART_ADMISSION_REFUSED");
        assertThat(reader.lines(auth.owner())).isEmpty();
    }

    @Test void publicationNeedsARealReferenceInTheSameCommitAndVersionEdgesAreFrozen() {
        var buyer=owner().owner(); var asset=UUID.randomUUID();
        assertThatThrownBy(()->tx.executeWithoutResult(s->sql.update("""
                INSERT INTO account_cart.asset(owner_id,asset_id,kind,object_key,byte_count,sha256)
                VALUES (?,?,'IMAGE',?,3,repeat('a',64))
                """,buyer,asset,UUID.randomUUID()))).hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
        assertThat(sql.queryForObject("SELECT count(*) FROM account_cart.asset WHERE owner_id=?",Long.class,buyer)).isZero();
        assertThatThrownBy(()->sql.update("DELETE FROM account_cart.design_asset WHERE owner_id=? AND asset_id=?",
                BUYER,FIXTURE_ASSET)).isInstanceOf(DataAccessException.class);
        assertThat(sql.queryForObject("SELECT confirmed_count FROM account_cart.asset WHERE owner_id=? AND asset_id=?",
                Long.class,BUYER,FIXTURE_ASSET)).isEqualTo(1);
    }

    @Test void droppingConfirmedLinkDoesNotEndAStillPendingPinAndReattachmentClearsTheLegacyTimestamp() {
        var buyer=owner().owner(); var asset=UUID.randomUUID(); var design=UUID.randomUUID(); var batch=UUID.randomUUID();
        tx.executeWithoutResult(s->{
            sql.update("INSERT INTO account_cart.asset(owner_id,asset_id,kind,object_key,byte_count,sha256) VALUES (?,?,'FONT',?,3,repeat('a',64))",
                    buyer,asset,UUID.randomUUID());
            addVersion(buyer,design,asset);
            sql.update("INSERT INTO account_cart.import_receipt(owner_id,batch_id,request_bytes) VALUES (?,?,decode('01','hex'))",buyer,batch);
            sql.update("INSERT INTO account_cart.pending_asset(owner_id,batch_id,asset_id) VALUES (?,?,?)",buyer,batch,asset);
        });
        sql.update("DELETE FROM account_cart.design_revision WHERE owner_id=? AND design_id=?",buyer,design);
        var facts=sql.queryForMap("SELECT * FROM account_cart.asset_retention_facts WHERE owner_id=? AND asset_id=?",buyer,asset);
        assertThat(facts).containsEntry("confirmed_count",0L).containsEntry("pending_count",1L).containsEntry("ever_confirmed",true);
        assertThat(facts.get("last_confirmed_removed_at")).isNotNull();
        tx.executeWithoutResult(s->addVersion(buyer,UUID.randomUUID(),asset));
        facts=sql.queryForMap("SELECT * FROM account_cart.asset_retention_facts WHERE owner_id=? AND asset_id=?",buyer,asset);
        assertThat(facts).containsEntry("confirmed_count",1L).containsEntry("pending_count",1L)
                .containsEntry("last_confirmed_removed_at",null);
    }

    private static void addVersion(UUID owner,UUID design,UUID asset) {
        sql.update("""
                INSERT INTO account_cart.design_revision(owner_id,design_id,revision,offer_id,variant_id,template_revision,label,canonical_bytes)
                VALUES (?,?,1,'kiln-cup','blue',1,'test',decode('0102','hex'))
                """,owner,design);
        sql.update("INSERT INTO account_cart.design_asset(owner_id,design_id,revision,asset_id) VALUES (?,?,1,?)",owner,design,asset);
    }

    @Test void catalogOwnershipAndIdentityCannotBeRetargetedAndBulkResetCannotForgetReceiptHistory() {
        assertThatThrownBy(()->sql.update("UPDATE account_cart.offer SET workspace_id=? WHERE id='kiln-cup'",UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class).hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
        assertThatThrownBy(()->sql.update("UPDATE account_cart.variant SET sku='renamed' WHERE offer_id='kiln-cup' AND id='blue'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->sql.execute("TRUNCATE account_cart.import_receipt CASCADE")).isInstanceOf(DataAccessException.class);
        assertThat(sql.queryForObject("SELECT sku FROM account_cart.variant WHERE offer_id='kiln-cup' AND id='blue'",String.class)).isEqualTo("kiln-blue");
    }

    @Test void readBoundNeverTruncatesAnOwnerCartOrChangesQuantities() {
        UUID who=owner().owner();
        sql.update("INSERT INTO account_cart.cart(owner_id) VALUES (?)",who);
        sql.update("""
                INSERT INTO account_cart.cart_line(owner_id,id,offer_id,variant_id,quantity)
                SELECT ?,gen_random_uuid(),'kiln-cup','blue',9223372036854775807 FROM generate_series(1,1000)
                """,who);
        assertThat(reader.lines(who).orElseThrow()).hasSize(1000).allMatch(l->l.quantity().equals("9223372036854775807"));
        sql.update("INSERT INTO account_cart.cart_line(owner_id,id,offer_id,variant_id,quantity) VALUES (?,?,'kiln-cup','blue',1)",
                who,UUID.randomUUID());
        assertThatThrownBy(()->reader.lines(who)).isInstanceOf(PermanentCartReader.ReadUnavailable.class);
    }

    private static UUID pin(UUID owner, UUID asset) {
        UUID batch=UUID.randomUUID();
        sql.update("INSERT INTO account_cart.import_receipt(owner_id,batch_id,request_bytes) VALUES (?,?,decode('01','hex'))",owner,batch);
        sql.update("INSERT INTO account_cart.pending_asset(owner_id,batch_id,asset_id) VALUES (?,?,?)",owner,batch,asset);
        return batch;
    }

    private static void releasePin(UUID owner, UUID batch) {
        sql.update("UPDATE account_cart.import_receipt SET state='RETIRED' WHERE owner_id=? AND batch_id=?",owner,batch);
        sql.update("DELETE FROM account_cart.pending_asset WHERE owner_id=? AND batch_id=?",owner,batch);
    }

    @Test void previouslyOrPossiblyPinnedUnfinishedBytesAreNeverTreatedAsAbandonedUploads() {
        var who=owner().owner(); UUID asset=UUID.randomUUID();
        sql.update("INSERT INTO account_cart.asset(owner_id,asset_id,kind) VALUES (?,?,'FONT')",who,asset);
        var reader=new com.creastrix.platform.accountcart.persistence.CartAssetRetentionReader(sql);
        var never=reader.facts(who,asset).orElseThrow();
        var muchLater=never.uploadStartedAt().plus(java.time.Duration.ofDays(100));
        assertThat(com.creastrix.platform.accountcart.assets.CartAssetRetention.classify(never,muchLater))
                .isEqualTo(com.creastrix.platform.accountcart.assets.CartAssetRetention.Eligibility.UNFINISHED_UPLOAD);
        UUID batch=tx.execute(s->pin(who,asset));
        assertThat(reader.facts(who,asset).orElseThrow().pendingPins()).isOne();
        tx.executeWithoutResult(s->releasePin(who,batch));
        var previous=reader.facts(who,asset).orElseThrow();
        assertThat(previous.pendingPins()).isZero();
        assertThat(previous.everConfirmed()).isFalse();
        assertThat(previous.unreferencedObservedAt()).isNull();
        assertThat(previous.pinHistory().name()).isEqualTo("PREVIOUSLY_PINNED");
        assertThat(com.creastrix.platform.accountcart.assets.CartAssetRetention.classify(previous,muchLater))
                .isEqualTo(com.creastrix.platform.accountcart.assets.CartAssetRetention.Eligibility.RETAIN);
        var unknown=reader.facts(BUYER,LEGACY_UNPROVEN).orElseThrow();
        assertThat(unknown.pinHistory().name()).isEqualTo("UNKNOWN");
        assertThat(com.creastrix.platform.accountcart.assets.CartAssetRetention.classify(unknown,muchLater))
                .isEqualTo(com.creastrix.platform.accountcart.assets.CartAssetRetention.Eligibility.RETAIN);
        assertThat(reader.facts(BUYER,LEGACY_PINNED).orElseThrow().pinHistory().name()).isEqualTo("PREVIOUSLY_PINNED");
        assertThatThrownBy(()->sql.update("UPDATE account_cart.asset SET ever_pinned=false WHERE owner_id=? AND asset_id=?",who,asset))
                .isInstanceOf(DataAccessException.class);
    }

    @Test void withdrawingAnOfferAndPublishingANewTemplateDoNotRewriteTheBuyersDesign() {
        tx.executeWithoutResult(status->{
            sql.update("UPDATE account_cart.offer SET listed=false WHERE id='kiln-cup'");
            sql.update("INSERT INTO account_cart.template_revision(offer_id,revision,configuration) VALUES ('kiln-cup',2,'{}')");
            var rows=reader.lines(BUYER).orElseThrow();
            assertThat(rows).hasSize(2).allSatisfy(line->{
                assertThat(line.listed()).isFalse();
                assertThat(line.offerId()).isEqualTo("kiln-cup");
                assertThat(line.variantId()).isEqualTo("blue");
            });
            var decorated=rows.stream().filter(line->line.designLabel()!=null).findFirst().orElseThrow();
            assertThat(decorated.lineId()).hasToString("50000000-0000-4000-8000-000000000002");
            assertThat(decorated.templateRevision()).isEqualTo("1");
            assertThat(decorated.quantity()).isEqualTo("1");
            status.setRollbackOnly();
        });
    }

    @Test void activeAgainDoesNotRestoreAnOldAdmissionEvenWhenTheScopeNeverChanged() throws Exception {
        var admission = owner();
        var batch = prepare(admission.owner(),2L);
        var pool = Executors.newSingleThreadExecutor();
        try (var connection = sql.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            int blocker;
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT pg_backend_pid()")) {
                result.next();
                blocker = result.getInt(1);
            }
            try (var statement = connection.prepareStatement("UPDATE public.users SET status=? WHERE id=?")) {
                for (String next : List.of("SUSPENDED","ACTIVE")) {
                    statement.setString(1,next);
                    statement.setObject(2,admission.owner());
                    assertThat(statement.executeUpdate()).isOne();
                }
            }
            var waiter = pool.submit(()->imports.apply(admission,batch));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean waiting;
            do {
                waiting = Boolean.TRUE.equals(sql.queryForObject("""
                    SELECT EXISTS(SELECT FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))
                      AND query LIKE '%account_eligibility_generation=%FOR NO KEY UPDATE%')
                    """,Boolean.class,blocker));
            } while (!waiting && System.nanoTime()<deadline);
            assertThat(waiting).as("cart transaction blocked on this eligibility transition").isTrue();
            connection.commit();
            assertThatThrownBy(()->waiter.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("CART_ADMISSION_REFUSED");
        } finally { pool.shutdownNow(); }
        assertThat(reader.lines(admission.owner()).orElseThrow()).isEmpty();
        assertThat(sql.queryForObject("SELECT state FROM account_cart.import_receipt WHERE owner_id=? AND batch_id=?",
                String.class,admission.owner(),batch)).isEqualTo("PENDING");
        long fresh = sql.queryForObject("SELECT account_eligibility_generation FROM public.users WHERE id=?",
                Long.class,admission.owner());
        assertThat(fresh).isEqualTo(3);
        var readmitted = new PreparedCartImportStore.Admission(admission.scope(),admission.generation(),admission.owner(),fresh);
        assertThat(imports.apply(readmitted,batch).version()).isEqualTo(1);
        assertThatThrownBy(()->imports.apply(admission,batch)).hasMessage("CART_ADMISSION_REFUSED");
        assertThat(imports.apply(readmitted,batch).version()).isEqualTo(1);
    }

    private static String testVariant() {
        String variant="test-"+UUID.randomUUID();
        sql.update("""
                INSERT INTO account_cart.variant(offer_id,id,sku,label,cents,stock,swatch)
                VALUES ('kiln-cup',?,?,'Test finish',100,12,'#332211')
                """,variant,variant);
        return variant;
    }

    private static void addLegacyLines(UUID owner, String variant, int count) {
        sql.update("""
                INSERT INTO account_cart.cart_line(owner_id,id,offer_id,variant_id,quantity)
                SELECT ?,gen_random_uuid(),'kiln-cup',?,1 FROM generate_series(1,?)
                """,owner,variant,count);
    }

    private static UUID prepareVariants(UUID owner, List<String> variants) {
        UUID batch=UUID.randomUUID();
        tx.executeWithoutResult(s->{
            sql.update("INSERT INTO account_cart.import_receipt(owner_id,batch_id,request_bytes) VALUES (?,?,?)",
                    owner,batch,String.join("|",variants).getBytes(StandardCharsets.UTF_8));
            for (int i=0;i<variants.size();i++) {
                sql.update("""
                        INSERT INTO account_cart.import_line(owner_id,batch_id,position,offer_id,variant_id,quantity)
                        VALUES (?,?,?,'kiln-cup',?,1)
                        """,owner,batch,i,variants.get(i));
            }
        });
        return batch;
    }

    @Test void aGuestImportCanReclaimOnlyTheRemovedCapacityItNeeds() {
        var admission=owner();UUID who=admission.owner();
        sql.update("INSERT INTO account_cart.cart(owner_id) VALUES (?)",who);
        String existing=testVariant();
        addLegacyLines(who,existing,1000);
        sql.update("UPDATE account_cart.cart_line SET removed=true WHERE owner_id=? AND id IN (SELECT id FROM account_cart.cart_line WHERE owner_id=? ORDER BY id LIMIT 2)",who,who);
        // Existing plain duplicates would coalesce only for a matching imported variant.
        UUID batch=prepareVariants(who,List.of("blue"));
        assertThat(imports.apply(admission,batch).version()).isEqualTo(1);
        var result=reader.lines(who).orElseThrow();
        assertThat(result).hasSize(1000);
        assertThat(result).filteredOn(PermanentCartReader.Line::removed).hasSize(1);
        assertThat(result).filteredOn(l->!l.removed() && l.variantId().equals(existing)).hasSize(998);
        assertThat(imports.apply(admission,batch).version()).isEqualTo(1);
        assertThat(reader.lines(who).orElseThrow()).isEqualTo(result);
    }

    @Test void lineLimitAppliesBeforeReceiptAndRowsButNeverRechecksAnAlreadyCommittedBatchAgainstNewCartState() {
        var admission=owner(); UUID who=admission.owner();
        sql.update("INSERT INTO account_cart.cart(owner_id) VALUES (?)",who);
        addLegacyLines(who,testVariant(),999);
        UUID accepted=prepareVariants(who,List.of("blue")), refused=prepareVariants(who,List.of(testVariant()));
        assertThat(imports.apply(admission,accepted).version()).isEqualTo(1);
        var before=reader.lines(who).orElseThrow();
        assertThat(before).hasSize(1000);
        assertThatThrownBy(()->imports.apply(admission,refused)).hasMessage("CART_LINE_LIMIT");
        assertThat(reader.lines(who).orElseThrow()).isEqualTo(before);
        assertThat(sql.queryForObject("SELECT version FROM account_cart.cart WHERE owner_id=?",Long.class,who)).isEqualTo(1);
        assertThat(sql.queryForMap("SELECT state,applied_version FROM account_cart.import_receipt WHERE owner_id=? AND batch_id=?",
                who,refused)).containsEntry("state","PENDING").containsEntry("applied_version",null);
        // A historical cart can already be oversized. Replay proves only the original receipt;
        // do not resubmit, erase the excess, or replace its recorded version with today's.
        tx.executeWithoutResult(s->{
            sql.update("UPDATE account_cart.cart SET version=2 WHERE owner_id=?",who);
            addLegacyLines(who,"blue",1);
        });
        assertThat(imports.apply(admission,accepted).version()).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT count(*) FROM account_cart.cart_line WHERE owner_id=?",Long.class,who)).isEqualTo(1001);
        assertThat(sql.queryForObject("SELECT version FROM account_cart.cart WHERE owner_id=?",Long.class,who)).isEqualTo(2);
    }

    @Test void coalescingATouchedDuplicateMakesRoomForOneNewLineAtTheBoundary() {
        var admission=owner(); UUID who=admission.owner();
        sql.update("INSERT INTO account_cart.cart(owner_id) VALUES (?)",who);
        addLegacyLines(who,testVariant(),998);
        addLegacyLines(who,"blue",2);
        String extra=testVariant();
        var earliest=sql.queryForObject("""
                SELECT id FROM account_cart.cart_line WHERE owner_id=? AND variant_id='blue' ORDER BY created_at,id LIMIT 1
                """,UUID.class,who);
        assertThat(imports.apply(admission,prepareVariants(who,List.of("blue",extra))).version()).isEqualTo(1);
        var rows=reader.lines(who).orElseThrow();
        assertThat(rows).hasSize(1000);
        assertThat(rows).filteredOn(l->l.variantId().equals("blue")).singleElement().satisfies(l->{
            assertThat(l.lineId()).isEqualTo(earliest);
            assertThat(l.quantity()).isEqualTo("3");
        });
        assertThat(rows).filteredOn(l->l.variantId().equals(extra)).singleElement().satisfies(l->assertThat(l.quantity()).isEqualTo("1"));
    }
}
