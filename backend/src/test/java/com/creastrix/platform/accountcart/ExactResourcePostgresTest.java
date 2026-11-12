package com.creastrix.platform.accountcart;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.creastrix.platform.accountcart.assets.LocalCartFixtureAsset;
import com.creastrix.platform.accountcart.assets.PrivateCartAssetStore;
import com.creastrix.platform.accountcart.assets.PrivateCartAssetStore.Kind;
import com.creastrix.platform.accountcart.persistence.PermanentCartReader;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class ExactResourcePostgresTest {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("creastrix_account_cart_local").withUsername("account_cart_local_app")
            .withCreateContainerCmdModifier(c -> c.getHostConfig().withNetworkMode("bridge").withPublishAllPorts(false)
                    .withPortBindings(new Ports(ExposedPort.tcp(5432),Ports.Binding.bindIp("127.0.0.1"))));
    static DriverManagerDataSource source;
    static JdbcTemplate sql;
    static TransactionTemplate tx;
    static final byte[] LAYOUT = "layout-v1:test:face:ordered-slots".getBytes(UTF_8);
    @BeforeAll static void init() throws IOException {
        source = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:"+PG.getMappedPort(5432)+"/"+PG.getDatabaseName(),
                PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration","classpath:db/account-cart-local")
                .configuration(Map.of("flyway.postgresql.transactional.lock","false")).load().migrate();
        sql = new JdbcTemplate(source);
        sql.execute(new ClassPathResource("account-cart-local/fixture.sql").getContentAsString(UTF_8));
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    private static PreparedCartImportStore.Admission buyer() {
        UUID id=UUID.randomUUID(),scope=UUID.randomUUID();
        tx.executeWithoutResult(s->{
            sql.update("INSERT INTO users(id) VALUES (?)",id);
            sql.update("INSERT INTO user_profiles(user_id) VALUES (?)",id);
            sql.update("INSERT INTO account_cart.write_scope(id,generation,owner_id) VALUES (?,1,?)",scope,id);
        });
        return new PreparedCartImportStore.Admission(scope,1,id,1);
    }
    record Upload(UUID asset,PrivateCartAssetStore.Stored object) {}
    private static Upload upload(PrivateCartAssetStore files,byte[] bytes,Kind kind) throws IOException {
        return new Upload(UUID.randomUUID(),files.writeNew(UUID.randomUUID(),kind,bytes));
    }
    // Construct trusted test revisions only; this does not parse browser designs or expose a registrar.
    private static UUID design(UUID who, List<Upload> slots, boolean trusted) {
        UUID design=UUID.randomUUID();
        tx.executeWithoutResult(s->{
            sql.update("""
                    INSERT INTO account_cart.design_revision(owner_id,design_id,revision,offer_id,variant_id,template_revision,
                    label,canonical_bytes,layout_format) VALUES (?,?,1,'kiln-cup','blue',1,'Synthetic layout',?,?::smallint)
                    """,who,design,LAYOUT,trusted?1:null);
            for (var up : slots.stream().distinct().toList()) {
                var obj=up.object();
                sql.update("""
                        INSERT INTO account_cart.asset(owner_id,asset_id,kind,object_key,byte_count,sha256)
                        VALUES (?,?,?,?,?,?) ON CONFLICT(owner_id,asset_id) DO NOTHING
                        """,who,up.asset(),obj.kind().name(),obj.objectKey(),obj.byteCount(),obj.sha256());
                sql.update("INSERT INTO account_cart.design_asset(owner_id,design_id,revision,asset_id) VALUES (?,?,1,?)",who,design,up.asset());
            }
            if(trusted) for(int i=0;i<slots.size();i++) {
                sql.update("INSERT INTO account_cart.design_resource_slot(owner_id,design_id,revision,slot,asset_id) VALUES (?,?,1,?,?)",
                        who,design,i,slots.get(i).asset());
            }
        });
        return design;
    }
    private static UUID batch(UUID owner,List<UUID> versions) {
        UUID batch=UUID.randomUUID();
        tx.executeWithoutResult(s->{
            sql.update("INSERT INTO account_cart.import_receipt(owner_id,batch_id,request_bytes) VALUES (?,?,?)",owner,batch,new byte[]{1});
            for(int i=0;i<versions.size();i++) {
                sql.update("""
                        INSERT INTO account_cart.import_line(owner_id,batch_id,position,offer_id,variant_id,design_id,design_revision,quantity)
                        VALUES (?,?,?,'kiln-cup','blue',?,1,1)
                        """,owner,batch,i,versions.get(i));
                sql.update("""
                        INSERT INTO account_cart.pending_asset(owner_id,batch_id,asset_id)
                        SELECT owner_id,?,asset_id FROM account_cart.design_asset WHERE owner_id=? AND design_id=? AND revision=1
                        ON CONFLICT DO NOTHING
                        """,batch,owner,versions.get(i));
            }
        }); return batch;
    }

    @Test void independentUploadsMatchExactBytesButContentAndSlotOrderAreNotInterchangeable(@TempDir Path dir) throws Exception {
        var files=PrivateCartAssetStore.open(dir.toRealPath());
        var admission=buyer(); UUID who=admission.owner();
        byte[] same=LocalCartFixtureAsset.expectedBytes();
        var alternative=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(same));
        alternative.setRGB(0,0,0xff00ff00);
        var encoded=new java.io.ByteArrayOutputStream();
        assertThat(javax.imageio.ImageIO.write(alternative,"png",encoded)).isTrue();
        byte[] different=encoded.toByteArray();
        var a=upload(files,same,Kind.IMAGE); var b=upload(files,same,Kind.IMAGE); var c=upload(files,different,Kind.IMAGE);
        var first=design(who,List.of(a),true); var second=design(who,List.of(b),true);
        var other=design(who,List.of(c),true); var forward=design(who,List.of(a,c),true); var reversed=design(who,List.of(c,b),true);
        var registrar=new ExactResourceRegistrar(source,files);
        long verified=registrar.register(admission,a.asset());
        assertThat(registrar.register(admission,b.asset())).isEqualTo(verified);
        assertThat(registrar.register(admission,c.asset())).isNotEqualTo(verified);
        assertThat(registrar.register(admission,a.asset())).isEqualTo(verified);
        var store=new PreparedCartImportStore(source);
        var reader=new PermanentCartReader(sql);
        store.apply(admission,batch(who,List.of(first)));
        var retained=reader.lines(who).orElseThrow().getFirst().lineId();
        UUID additions=batch(who,List.of(second,other,forward,reversed));
        assertThat(store.apply(admission,additions).version()).isEqualTo(2);
        assertThat(store.apply(admission,additions).version()).isEqualTo(2);
        var lines=reader.lines(who).orElseThrow();
        assertThat(lines).hasSize(4);
        assertThat(lines.stream().filter(l->l.lineId().equals(retained)).findFirst().orElseThrow().quantity()).isEqualTo("2");
        assertThat(lines).extracting(PermanentCartReader.Line::quantity).containsExactlyInAnyOrder("2","1","1","1");
    }

    @Test void requirePhysicalComparisonRatherThanAdoptingMatchingMetadata(@TempDir Path first,@TempDir Path second) throws Exception {
        var complete=PrivateCartAssetStore.open(first.toRealPath()); var incomplete=PrivateCartAssetStore.open(second.toRealPath());
        var owner=buyer(); var stranger=buyer(); var png=LocalCartFixtureAsset.expectedBytes();
        var a=upload(complete,png,Kind.IMAGE); var b=upload(complete,png,Kind.IMAGE);
        design(owner.owner(),List.of(a),true); UUID unresolved=design(owner.owner(),List.of(b),true);
        var registrar=new ExactResourceRegistrar(source,complete);
        registrar.register(owner,a.asset());
        assertThatThrownBy(()->registrar.register(stranger,a.asset())).hasMessage("CART_UNRESOLVED_ASSET");
        var missing=new ExactResourceRegistrar(source,incomplete);
        assertThatThrownBy(()->missing.register(owner,b.asset())).isInstanceOf(IOException.class);
        // Target reads successfully and both persisted digests match, but the original representative does not exist.
        incomplete.writeNew(b.object().objectKey(),Kind.IMAGE,png);
        assertThatThrownBy(()->missing.register(owner,b.asset())).isInstanceOf(IOException.class);
        assertThat(sql.queryForObject("SELECT count(*) FROM account_cart.verified_resource WHERE owner_id=?",Integer.class,owner.owner())).isOne();
        var store=new PreparedCartImportStore(source); UUID id=batch(owner.owner(),List.of(unresolved));
        assertThatThrownBy(()->store.apply(owner,id)).hasMessage("CART_UNRESOLVED_DESIGN");
        assertThat(sql.queryForObject("SELECT state FROM account_cart.import_receipt WHERE owner_id=? AND batch_id=?",String.class,
                owner.owner(),id)).isEqualTo("PENDING");
        assertThatThrownBy(()->tx.executeWithoutResult(status -> {
            try { registrar.register(owner,b.asset()); } catch(IOException unexpected) { throw new AssertionError(unexpected); }
        })).hasMessage("CART_RESOURCE_REQUIRES_NO_AMBIENT_TRANSACTION");
        var stale = new PreparedCartImportStore.Admission(owner.scope(),1,owner.owner(),2);
        assertThatThrownBy(()->registrar.register(stale,b.asset())).hasMessage("CART_ADMISSION_REFUSED");
        registrar.register(owner,b.asset());
        assertThat(store.apply(owner,id).version()).isEqualTo(1);
        var legacy=design(owner.owner(),List.of(b),false);
        assertThatThrownBy(()->store.apply(owner,batch(owner.owner(),List.of(legacy)))).hasMessage("CART_UNRESOLVED_DESIGN");
    }
}
