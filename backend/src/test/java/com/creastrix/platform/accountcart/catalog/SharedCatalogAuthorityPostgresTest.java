package com.creastrix.platform.accountcart.catalog;

import com.creastrix.platform.accountcart.LocalCartCommands;
import com.creastrix.platform.accountcart.assets.PrivateCartAssetStore;
import com.creastrix.platform.accountcart.persistence.PermanentCartReader;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/** Two real databases; the buyer process can call only the catalog's bound lookup. */
@Testcontainers
class SharedCatalogAuthorityPostgresTest {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("creastrix_account_cart_local").withUsername("account_cart_local_app")
            .withCreateContainerCmdModifier(c->c.getHostConfig().withNetworkMode("bridge").withPublishAllPorts(false)
                    .withPortBindings(new Ports(ExposedPort.tcp(5432),Ports.Binding.bindIp("127.0.0.1"))));
    @TempDir static Path temp;
    static JdbcTemplate cart, seller;
    static DriverManagerDataSource lookup, destination;
    static SharedCatalogAuthority catalog;
    static Throwable v12Admission;
    static LocalCartCommands commands;
    static final String OFFER="plume", VARIANT="soft-silver", SKU="plume-finish-03";

    @BeforeAll static void prepare() throws Exception {
        destination=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        cart=new JdbcTemplate(destination);
        cart.execute("CREATE DATABASE creastrix_local_catalog");
        var sourceUrl="jdbc:postgresql://127.0.0.1:"+PG.getMappedPort(5432)+"/creastrix_local_catalog";
        var source=new DriverManagerDataSource(sourceUrl,PG.getUsername(),PG.getPassword());
        var migration=Flyway.configure().dataSource(source).defaultSchema("local_catalog").schemas("local_catalog")
                .locations("classpath:db/local-catalog").configuration(Map.of("flyway.postgresql.transactional.lock","false"));
        migration.target("12").load().migrate();
        seller=new JdbcTemplate(source);
        // This credential exists only in the disposable container.
        seller.execute("CREATE ROLE local_catalog_cart_reader LOGIN NOINHERIT PASSWORD 'disposable-reader'");
        seller.execute("GRANT USAGE ON SCHEMA local_catalog TO local_catalog_cart_reader");
        seller.execute("GRANT EXECUTE ON FUNCTION local_catalog.cart_variants(text[]) TO local_catalog_cart_reader");
        lookup=new DriverManagerDataSource(sourceUrl,SharedCatalogAuthority.ROLE,"disposable-reader");
        catalog=new SharedCatalogAuthority(lookup);
        v12Admission=catchThrowable(catalog::verifyProvisioning);
        migration.target("latest").load().migrate();
        catalog.verifyProvisioning();
        Flyway.configure().dataSource(destination).locations("classpath:db/migration","classpath:db/account-cart-local")
                .configuration(Map.of("flyway.postgresql.transactional.lock","false")).load().migrate();
        for(String file:List.of("fixture.sql","lane-fixture.sql"))
            cart.execute(new ClassPathResource("account-cart-local/"+file).getContentAsString(StandardCharsets.UTF_8));
        var files=PrivateCartAssetStore.open(Files.createDirectory(temp.resolve("assets"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath());
        commands=new LocalCartCommands(destination,files,catalog);
    }

    @Test void startupRequiresTheSourceIdentityContractNotJustAVersionOrExecuteGrant() {
        // Connection, role and public lookup are identical before and after the source upgrade.
        assertThat(v12Admission).isInstanceOf(IllegalStateException.class).hasMessageContaining("permanent variant identity");
        catalog.verifyProvisioning();
        seller.execute("ALTER TABLE local_catalog.variant DISABLE TRIGGER retain_catalog_variant_identity");
        try { assertThatThrownBy(catalog::verifyProvisioning).isInstanceOf(IllegalStateException.class); }
        finally { seller.execute("ALTER TABLE local_catalog.variant ENABLE TRIGGER retain_catalog_variant_identity"); }
        catalog.verifyProvisioning();
    }

    @Test void arbitraryListedVariantUsesCurrentStockAndLabelsWhileOldReceiptsAndLinesSurviveWithdrawal() {
        seller.update("""
            INSERT INTO local_catalog.product(id,name,href,personalizable,currency,design_fee_cents,display_order)
            VALUES (?,'Plume','/preview/items/plume',false,'EUR',0,101)
            """,OFFER);
        seller.update("""
            INSERT INTO local_catalog.variant(product_id,id,sku,cents,stock,colors,check_tone,name,photos,display_order)
            SELECT ?,?,?,5200,3,ARRAY['#9a9a9a'],'dark',name,ARRAY[]::text[],0 FROM local_catalog.variant WHERE sku='tide-cobalt'
            """,OFFER,VARIANT,SKU);
        // A draft without a presentation is not for sale, even if its variant already exists.
        assertThat((boolean)catalog.withVariant(SKU, row->row.isEmpty())).isTrue();
        seller.update("""
            INSERT INTO local_catalog.item_presentation(product_id,title,material,description,categories)
            SELECT ?,title,material,description,categories FROM local_catalog.item_presentation WHERE product_id='tide'
            """,OFFER);
        UUID owner=UUID.randomUUID(), firstIntent=UUID.randomUUID();
        cart.update("""
            WITH u AS (INSERT INTO public.users(id,status) VALUES (?,'ACTIVE') RETURNING id)
            INSERT INTO public.user_profiles(user_id) SELECT id FROM u
            """,owner);
        String session=UUID.randomUUID().toString();
        var claim=commands.beginLogin(commands.loginEpoch(),owner,commands.eligibilityGeneration(owner),session);
        assertThat(commands.bind(claim,session)).isEqualTo(LocalCartCommands.BindResult.BOUND);
        var first=new LocalCartCommands.Addition(SKU,2);
        assertThat(commands.add(claim,session,claim.namespace(),firstIntent,first).version()).isOne();
        assertThat(cart.queryForObject("SELECT stock FROM account_cart.variant WHERE sku=?",Integer.class,SKU)).isZero();
        assertThat(cart.queryForObject("SELECT count(*) FROM account_cart.direct_add_variant WHERE sku=?",Long.class,SKU)).isZero();
        seller.update("UPDATE local_catalog.variant SET stock=2 WHERE sku=?",SKU);
        assertThatThrownBy(()->commands.add(claim,session,claim.namespace(),UUID.randomUUID(),new LocalCartCommands.Addition(SKU,1)))
                .isInstanceOf(LocalCartCommands.InvalidBatch.class);
        seller.update("UPDATE local_catalog.product SET name='Plume II',href='/collections/objects/plume' WHERE id=?",OFFER);
        var reader=new PermanentCartReader(cart,catalog);
        assertThat(reader.lines(owner).orElseThrow()).singleElement().satisfies(line->{
            assertThat(line.sku()).isEqualTo(SKU);
            assertThat(line.variantId()).isEqualTo(VARIANT);
            assertThat(line.quantity()).isEqualTo("2");
            assertThat(line.offerLabel()).isEqualTo("Plume II");
            assertThat(line.href()).isEqualTo("/collections/objects/plume");
        });
        seller.update("DELETE FROM local_catalog.item_presentation WHERE product_id=?",OFFER);
        assertThat(commands.add(claim,session,claim.namespace(),firstIntent,first).version()).isOne();
        assertThatThrownBy(()->commands.add(claim,session,claim.namespace(),UUID.randomUUID(),first))
                .isInstanceOf(LocalCartCommands.InvalidBatch.class);
        assertThat(reader.lines(owner).orElseThrow()).singleElement().satisfies(line->{
            assertThat(line.quantity()).isEqualTo("2");
            assertThat(line.listed()).isFalse();
            assertThat(line.href()).isNull();
        });
        assertThatThrownBy(()->commands.add(claim,session,claim.namespace(),UUID.randomUUID(),new LocalCartCommands.Addition("kiln-blue",1)))
                .isInstanceOf(LocalCartCommands.InvalidBatch.class);
        // No fallback to historical rows or an invented negative decision during an outage.
        var undecided=UUID.randomUUID();
        seller.execute("REVOKE EXECUTE ON FUNCTION local_catalog.cart_variants(text[]) FROM local_catalog_cart_reader");
        try {
            assertThat(commands.add(claim,session,claim.namespace(),firstIntent,first).version()).isOne();
            assertThatThrownBy(()->commands.add(claim,session,claim.namespace(),undecided,first))
                    .isInstanceOf(LocalCartCommands.Unavailable.class);
            assertThat(cart.queryForObject("SELECT count(*) FROM account_cart.direct_add_receipt WHERE intent_id=?",Long.class,undecided)).isZero();
        } finally { seller.execute("GRANT EXECUTE ON FUNCTION local_catalog.cart_variants(text[]) TO local_catalog_cart_reader"); }
    }

    @Test void buyerLookupHoldsActualSourceRowsAndCannotReadOrWritePrivateTables() {
        var direct=new JdbcTemplate(lookup);
        assertThatThrownBy(()->direct.queryForList("SELECT * FROM local_catalog.variant LIMIT 0")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->direct.update("UPDATE local_catalog.product SET name=name WHERE false")).isInstanceOf(DataAccessException.class);
        assertThat((boolean)catalog.withVariant("tide-cobalt", row->{
            assertThat(row.orElseThrow().stock()).isPositive();
            var transaction=new TransactionTemplate(new DataSourceTransactionManager(seller.getDataSource()));
            assertThatThrownBy(()->transaction.executeWithoutResult(s->{
                seller.execute("SET LOCAL lock_timeout='150ms'");
                seller.update("UPDATE local_catalog.variant SET stock=stock WHERE sku='tide-cobalt'");
            })).isInstanceOf(DataAccessException.class).rootCause().isInstanceOfSatisfying(java.sql.SQLException.class,
                    sql->assertThat(sql.getSQLState()).isEqualTo("55P03"));
            return true;
        })).isTrue();
        assertThat(seller.update("UPDATE local_catalog.variant SET stock=stock WHERE sku='tide-cobalt'")).isOne();
        seller.execute("GRANT UPDATE(stock) ON local_catalog.variant TO local_catalog_cart_reader");
        try { assertThatThrownBy(catalog::verifyProvisioning).isInstanceOf(IllegalStateException.class); }
        finally { seller.execute("REVOKE UPDATE(stock) ON local_catalog.variant FROM local_catalog_cart_reader"); }
    }

    @Test void activeStudioIsExplicitAndHistoricalTemplateSurvivesDeactivation() {
        assertThat((Long)catalog.withVariant("cobalt", row->row.orElseThrow().templateRevision())).isEqualTo(2L);
        assertThat((Long)catalog.withVariant("tide-cobalt", row->row.orElseThrow().templateRevision())).isNull();
        seller.update("UPDATE local_catalog.product SET active_studio_revision=NULL WHERE id='loop'");
        try {
            catalog.withVariant("cobalt", row->{
                assertThat(row.orElseThrow().templateRevision()).isNull();
                assertThat(row.orElseThrow().retainedRevision()).isEqualTo(2);
                return null;
            });
        } finally { seller.update("UPDATE local_catalog.product SET active_studio_revision=2 WHERE id='loop'"); }
    }

    @Test void cannotReassignAnExistingSkuToTheNewSourceTuple() {
        String sku="drift-standard";
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(destination));
        transaction.executeWithoutResult(scope->{
            cart.update("""
                INSERT INTO account_cart.variant(offer_id,id,sku,label,cents,stock,swatch)
                VALUES ('kiln-cup','old-tray',?,'Historic tray',10,1,'#ffffff')
                """,sku);
            assertThatThrownBy(()->catalog.withVariant(sku,row->{row.orElseThrow().remember(cart);return null;}))
                    .isInstanceOf(LocalCartCommands.Conflict.class);
            assertThat(cart.queryForObject("SELECT offer_id FROM account_cart.variant WHERE sku=?",String.class,sku)).isEqualTo("kiln-cup");
            assertThat(cart.queryForObject("SELECT count(*) FROM account_cart.storefront_variant WHERE sku=?",Integer.class,sku)).isZero();
            scope.setRollbackOnly();
        });
    }
}
