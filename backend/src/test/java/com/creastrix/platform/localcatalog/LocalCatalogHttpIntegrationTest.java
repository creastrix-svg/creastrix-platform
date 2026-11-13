package com.creastrix.platform.localcatalog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the HTTP boundary and the actual catalog-only migrations on a disposable database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local-catalog")
@Testcontainers
class LocalCatalogHttpIntegrationTest {
    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("creastrix_local_catalog_test")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withNetworkMode("bridge")
                    .withPublishAllPorts(false)
                    .withPortBindings(new Ports(ExposedPort.tcp(5432), Ports.Binding.bindIp("127.0.0.1"))));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("CREASTRIX_CATALOG_DB_URL",
                () -> "jdbc:postgresql://127.0.0.1:" + DATABASE.getMappedPort(5432) + "/" + DATABASE.getDatabaseName());
        registry.add("CREASTRIX_CATALOG_DB_USERNAME", DATABASE::getUsername);
        registry.add("CREASTRIX_CATALOG_DB_PASSWORD", DATABASE::getPassword);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> call(String method, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void returnsOneEntryPerProductAndLatestCommittedVariantData() throws Exception {
        var response = call("GET", LocalCatalogConfiguration.PRODUCTS_PATH);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        var products = json.readTree(response.body()).get("products");
        assertThat(products.size()).isEqualTo(39);
        var offers = products.values();
        assertThat(offers.stream().mapToInt(p -> p.get("variants").size()).sum()).isEqualTo(42);
        var generic = offers.stream().filter(p -> !p.get("id").asString().matches("loop|tide")).toList();
        assertThat(generic).hasSize(37).allSatisfy(product -> {
            var finishes = product.get("variants");
            assertThat(finishes.size()).isEqualTo(1);
            var standard = finishes.get(0);
            assertThat(standard.get("id").asString()).isEqualTo("standard");
            assertThat(standard.get("sku").asString()).isEqualTo(product.get("id").asString() + "-standard");
            assertThat(standard.get("photos").size()).isPositive();
            assertThat(standard.get("stock").asInt()).isPositive();
            assertThat(standard.get("cents").asInt()).isPositive();
            assertThat(standard.get("model").isNull()).isTrue();
            assertThat(product.get("personalizable").asBoolean()).isFalse();
        });
        var loop = products.get(0);
        assertThat(loop.get("id").asString()).isEqualTo("loop");
        assertThat(loop.get("personalizable").asBoolean()).isTrue();
        assertThat(loop.get("variants").size()).isEqualTo(4);
        var chalk = loop.get("variants").get(2);
        assertThat(chalk.get("id").asString()).isEqualTo("chalk");
        assertThat(chalk.get("cents").asInt()).isEqualTo(1990);
        assertThat(chalk.get("previousCents").asInt()).isEqualTo(2400);
        assertThat(chalk.get("stock").asInt()).isEqualTo(15);
        assertThat(chalk.get("name").get("de").asString()).isEqualTo("Kreide");
        assertThat(chalk.get("model").isString()).isTrue();
        assertThat(chalk.get("studioFormat").asString()).isEqualTo("surface-print-v1");
        assertThat(chalk.get("templateRevision").asString()).isEqualTo("2");
        assertThat(chalk.get("retainedStudioRevision").asString()).isEqualTo("2");

        var tide = products.get(1);
        assertThat(tide.get("name").asString()).isEqualTo("Tide");
        assertThat(tide.get("personalizable").asBoolean()).isFalse();
        var cup = tide.get("variants").get(0);
        assertThat(cup.get("sku").asString()).isEqualTo("tide-cobalt");
        assertThat(cup.get("cents").asInt()).isEqualTo(1800);
        assertThat(cup.get("model").isNull()).isTrue();
        assertThat(cup.get("studioFormat").isNull()).isTrue();
        assertThat(cup.get("templateRevision").isNull()).isTrue();

        assertThat(jdbc.update("UPDATE local_catalog.variant SET cents = 2050, stock = 7 WHERE sku = 'chalk'"))
                .isEqualTo(1);
        var refreshed = json.readTree(call("GET", LocalCatalogConfiguration.PRODUCTS_PATH).body()).get("products");
        var changed = refreshed.get(0).get("variants").get(2);
        assertThat(changed.get("cents").asInt()).isEqualTo(2050);
        assertThat(changed.get("stock").asInt()).isEqualTo(7);
        assertThat(refreshed.get(1)).isEqualTo(tide);
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.ready_made_products') IS NULL", Boolean.class))
                .isTrue();
    }

    @Test
    void exposesOneLocalOfferPerCardAndReadsRatingSummariesAndOrderedRailsFromPostgres() throws Exception {
        var first = json.readTree(call("GET", LocalCatalogConfiguration.PRODUCTS_PATH).body());
        assertThat(first.get("products").size()).isEqualTo(39);
        var entries = first.get("entries").values();
        assertThat(entries).hasSize(39);
        assertThat(entries.stream().map(e -> e.get("id").asString()).distinct().count()).isEqualTo(39);
        var ids = entries.stream().map(e -> e.get("id").asString()).toList();
        var routes = entries.stream().map(e -> e.get("href").asString()).toList();
        assertThat(routes).doesNotHaveDuplicates().contains("/products/loop", "/collections/objects/tide",
                "/collections/objects/halo", "/preview/items/loop-mini");
        assertThat(entries).allMatch(e -> e.get("kind").asString().equals("offer"));
        assertThat(entries.stream().filter(e -> e.get("homeOrder").isNumber()).count()).isEqualTo(32);
        assertThat(entries.stream().filter(e -> e.get("editionOrder").isNumber()).count()).isEqualTo(32);
        assertThat(entries.stream().filter(e -> e.get("rating").get("average").isNull()).count()).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM local_catalog.product WHERE seed_origin = 'buyer-catalog-v1'
                """, Integer.class)).isEqualTo(39);
        var halo = entries.stream().filter(e -> e.get("id").asString().equals("halo")).findFirst().orElseThrow();
        assertThat(halo.get("title").get("de").asString()).isEqualTo("Tischleuchte");
        assertThat(halo.get("homeOrder").isNumber()).isTrue();
        assertThat(halo.get("rating").get("count").asInt()).isEqualTo(30);
        assertThat(halo.get("rating").get("average").asDouble()).isEqualTo(4.7);
        var dot = entries.stream().filter(e -> e.get("id").asString().equals("dot")).findFirst().orElseThrow();
        assertThat(dot.get("rating").get("count").asInt()).isZero();
        assertThat(dot.get("rating").get("average").isNull()).isTrue();
        assertThat(halo.has("stock")).isFalse();
        assertThat(halo.has("cents")).isFalse();
        var loop = entries.stream().filter(e -> e.get("id").asString().equals("loop")).findFirst().orElseThrow();
        assertThat(loop.get("photo").asString()).isEqualTo("/product/reference-asset-01.webp");
        assertThat(entries.stream().filter(e -> !e.get("collectionId").isNull())
                .map(e -> e.get("id").asString())).containsExactlyInAnyOrder(
                "loop", "loop-mini", "loop-wide", "loop-tray", "loop-tall", "loop-duo", "loop-stand");
        assertThat(entries).allSatisfy(e -> {
            if (e.get("collectionId").isNull()) assertThat(e.get("collectionName").isNull()).isTrue();
            else {
                assertThat(e.get("collectionId").asString()).isEqualTo("loop");
                assertThat(e.get("collectionName").asString()).isEqualTo("Loop");
            }
        });
        var rails = first.get("recommendations").values();
        assertThat(rails).isNotEmpty().allSatisfy(rail -> {
            var owner = rail.get("productId").asString();
            var targets = rail.get("itemIds").values().stream().map(n -> n.asString()).toList();
            assertThat(rail.get("section").asString()).isIn("studio", "related", "section");
            assertThat(ids).contains(owner).containsAll(targets);
            assertThat(targets).isNotEmpty().doesNotContain(owner).doesNotHaveDuplicates();
        });
        var tideStudio = rails.stream()
                .filter(g -> g.get("productId").asString().equals("tide") && g.get("section").asString().equals("studio"))
                .findFirst().orElseThrow();
        assertThat(tideStudio.get("itemIds").values().stream().map(n -> n.asString()).toList())
                .containsExactly("halo", "flute", "reed", "prism");
        for (var owner : ids) {
            var collection = rails.stream().filter(g -> g.get("productId").asString().equals(owner)
                    && g.get("section").asString().equals("studio")).findFirst().orElseThrow();
            var related = rails.stream().filter(g -> g.get("productId").asString().equals(owner)
                    && g.get("section").asString().equals("related")).findFirst();
            var section = rails.stream().filter(g -> g.get("productId").asString().equals(owner)
                    && g.get("section").asString().equals("section")).findFirst();
            assertThat(collection.get("itemIds").size()).isLessThanOrEqualTo(4);
            var matches = related.map(g -> g.get("itemIds").values().stream().map(n -> n.asString()).toList())
                    .orElse(List.of());
            var fallback = section.map(g -> g.get("itemIds").values().stream().map(n -> n.asString()).toList())
                    .orElse(List.of());
            var shown = new HashSet<>(collection.get("itemIds").values().stream().map(n -> n.asString()).toList());
            shown.add(owner);
            assertThat(matches).allMatch(shown::add);
            var ownerSections = entries.stream().filter(e -> owner.equals(e.get("id").asString())).findFirst().orElseThrow()
                    .get("categories").values().stream().map(n -> n.asString()).toList();
            var chosenSection = ownerSections.stream().filter(s -> !s.equals("living") && !s.equals("gifts"))
                    .findFirst().orElse(ownerSections.contains("living") ? "living" : null);
            var expectedRemaining = entries.stream()
                    .filter(e -> e.get("categories").values().stream().anyMatch(c -> c.asString().equals(chosenSection)))
                    .map(e -> e.get("id").asString()).filter(id -> !shown.contains(id)).toList();
            assertThat(fallback).as("all remaining offers in %s for %s", chosenSection, owner)
                    .containsExactlyInAnyOrderElementsOf(expectedRemaining);
            assertThat(fallback).allMatch(shown::add);
            if (owner.equals("loop")) {
                assertThat(collection.get("itemIds").values().stream().map(n -> n.asString()))
                        .containsExactly("loop-tray", "loop-stand", "loop-mini", "loop-wide");
                assertThat(matches).contains("loop-tall", "loop-duo");
                assertThat(fallback).allMatch(id -> entries.stream().anyMatch(e -> e.get("id").asString().equals(id)
                        && e.get("categories").values().stream().anyMatch(cat -> cat.asString().equals("decor"))));
            } else if (owner.equals("tide")) {
                assertThat(fallback).isNotEmpty().allMatch(id -> entries.stream().anyMatch(e -> e.get("id").asString().equals(id)
                        && e.get("categories").values().stream().anyMatch(cat -> cat.asString().equals("kitchen"))));
            } else if (owner.equals("drift")) {
                // Both trays describe a similar use even though their legacy use keys differ.
                assertThat(matches).contains("dot");
                assertThat(fallback).allMatch(id -> entries.stream().anyMatch(e -> e.get("id").asString().equals(id)
                        && e.get("categories").values().stream().anyMatch(cat -> cat.asString().equals("desk"))));
            }
        }

        // A change to local illustrative summaries or recommendation membership
        // is visible on the next read; it does not create buyer review records.
        int originalPosition = jdbc.queryForObject("""
                SELECT position FROM local_catalog.recommendation
                 WHERE product_id = 'tide' AND section = 'studio' AND item_id = 'halo'
                """, Integer.class);
        try {
            assertThat(jdbc.update("""
                    UPDATE local_catalog.product SET rating_count = 2, rating_average = 4.5 WHERE id = 'halo'
                    """)).isEqualTo(1);
            assertThat(jdbc.update("""
                    UPDATE local_catalog.recommendation SET position = 99
                     WHERE product_id = 'tide' AND section = 'studio' AND item_id = 'halo'
                    """)).isEqualTo(1);
            var updated = json.readTree(call("GET", LocalCatalogConfiguration.PRODUCTS_PATH).body());
            var rated = updated.get("entries").values().stream()
                    .filter(e -> e.get("id").asString().equals("halo")).findFirst().orElseThrow();
            assertThat(rated.get("rating").get("count").asInt()).isEqualTo(2);
            assertThat(rated.get("rating").get("average").asDouble()).isEqualTo(4.5);
            var rail = updated.get("recommendations").values().stream()
                    .filter(g -> g.get("productId").asString().equals("tide") && g.get("section").asString().equals("studio"))
                    .findFirst().orElseThrow();
            assertThat(rail.get("itemIds").get(0).asString()).isEqualTo("flute");
        } finally {
            jdbc.update("UPDATE local_catalog.product SET rating_count = 30, rating_average = 4.7 WHERE id = 'halo'");
            jdbc.update("""
                    UPDATE local_catalog.recommendation SET position = ?
                     WHERE product_id = 'tide' AND section = 'studio' AND item_id = 'halo'
                    """, originalPosition);
        }
    }

    @Test
    void offerThumbnailFollowsTheCurrentVariantPhotoAndNeverResurrectsMissingMedia() throws Exception {
        var initial = entry("halo");
        assertThat(initial.get("photo").asString()).isEqualTo("/shop-edition/halo.webp");
        assertThat(initial.get("thumb").asString()).isEqualTo("/shop-edition/halo-small.webp");
        try {
            assertThat(jdbc.update("""
                    UPDATE local_catalog.variant SET photos=ARRAY['/shop-edition/arc.webp'] WHERE sku='halo-standard'
                    """)).isOne();
            var changed = entry("halo");
            assertThat(changed.get("photo").asString()).isEqualTo("/shop-edition/arc.webp");
            assertThat(changed.get("thumb").asString()).isEqualTo("/shop-edition/arc.webp");
            assertThat(jdbc.update("""
                    UPDATE local_catalog.variant SET photos=ARRAY[]::text[] WHERE sku='halo-standard'
                    """)).isOne();
            var missing = entry("halo");
            assertThat(missing.get("photo").isNull()).isTrue();
            assertThat(missing.get("thumb").isNull()).isTrue();
        } finally {
            jdbc.update("UPDATE local_catalog.variant SET photos=ARRAY['/shop-edition/halo.webp'] WHERE sku='halo-standard'");
        }
    }

    private tools.jackson.databind.JsonNode entry(String id) throws Exception {
        return json.readTree(call("GET", LocalCatalogConfiguration.PRODUCTS_PATH).body()).get("entries").values()
                .stream().filter(entry -> entry.get("id").asString().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void upgradingTheOriginalTwoProductsKeepsEditedPricesStockAndNames() {
        // A second database in this class's disposable container lets us test a
        // real V2 -> current upgrade without touching the HTTP application's data.
        jdbc.execute("CREATE DATABASE local_catalog_upgrade_test");
        String url = "jdbc:postgresql://127.0.0.1:" + DATABASE.getMappedPort(5432) + "/local_catalog_upgrade_test";
        var db = new DriverManagerDataSource(url, DATABASE.getUsername(), DATABASE.getPassword());
        var migration = Flyway.configure().dataSource(db).defaultSchema("local_catalog").schemas("local_catalog")
                .locations("classpath:db/local-catalog")
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"));
        migration.target("2").load().migrate();
        var sql = new JdbcTemplate(db);
        assertThat(sql.update("UPDATE local_catalog.variant SET cents=1911, stock=4 WHERE sku='chalk'")).isOne();
        assertThat(sql.update("UPDATE local_catalog.product SET name='Edited cup' WHERE id='tide'")).isOne();
        migration.target("latest").load().migrate();
        assertThat(sql.queryForObject("SELECT count(*) FROM local_catalog.product", Integer.class)).isEqualTo(39);
        assertThat(sql.queryForObject("SELECT name FROM local_catalog.product WHERE id='tide'", String.class))
                .isEqualTo("Edited cup");
        assertThat(sql.queryForObject("""
                SELECT count(*) FROM local_catalog.variant WHERE sku='chalk' AND cents=1911 AND stock=4
                """, Integer.class)).isOne();
    }

    @Test
    void offersCannotAdvertiseAnUnrelatedStorefrontRoute() {
        assertThatThrownBy(() -> jdbc.update("UPDATE local_catalog.product SET href = '/account' WHERE id = 'loop'"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("offer_route_is_supported");
    }

    @Test
    void aPlainSkuCannotAliasAPersonalisedCartLine() {
        assertThatThrownBy(() -> jdbc.update("UPDATE local_catalog.variant SET sku = 'design-1' WHERE sku = 'tide-cobalt'"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("sku_outside_design_line_namespace");
    }

    @Test
    void publishedIdentityKeysFitTheCartProtocolBoundary() {
        String limit="s".repeat(200), over=limit+"s";
        assertThatThrownBy(()->insertProduct(over)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("product_id_fits_cart_protocol");
        assertThat(insertProduct(limit)).isOne();
        try {
            assertThatThrownBy(()->insertVariant(limit,limit,over))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("sku_fits_cart_protocol");
            assertThatThrownBy(()->insertVariant(limit,over,limit))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("variant_id_fits_cart_protocol");
            assertThat(insertVariant(limit,limit,limit)).isOne();
        } finally {
            jdbc.update("DELETE FROM local_catalog.variant WHERE product_id=?",limit);
            jdbc.update("DELETE FROM local_catalog.product WHERE id=?",limit);
        }
    }

    private int insertProduct(String id) {
        return jdbc.update("""
            INSERT INTO local_catalog.product(id,name,href,personalizable,currency,design_fee_cents,display_order)
            VALUES (?,'Boundary item','/preview/items/'||?,false,'EUR',0,120)
            """,id,id);
    }
    private int insertVariant(String product,String variant,String sku) {
        return jdbc.update("""
            INSERT INTO local_catalog.variant(product_id,id,sku,cents,stock,colors,check_tone,name,photos,display_order)
            SELECT ?,?,?,100,10,colors,check_tone,name,photos,0 FROM local_catalog.variant WHERE sku='tide-cobalt'
            """,product,variant,sku);
    }

    @Test
    void anUnpurchasedVariantCannotChangeIdentityEvenAfterRemoval() {
        String first="original-tray", second="other-tray", variant="blue", sku="blue-tray-76";
        try {
            assertThat(insertProduct(first)).isOne();
            assertThat(insertProduct(second)).isOne();
            assertThat(insertVariant(first,variant,sku)).isOne();
            assertThatThrownBy(()->jdbc.update("UPDATE local_catalog.variant SET product_id=? WHERE sku=?",second,sku))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("cannot be reassigned");
            assertThat(jdbc.update("DELETE FROM local_catalog.variant WHERE sku=?",sku)).isOne();
            assertThatThrownBy(()->insertVariant(second,"other-blue",sku))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("cannot be reassigned");
            assertThatThrownBy(()->insertVariant(first,variant,"a-different-tray"))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("cannot be reassigned");
            assertThatThrownBy(()->jdbc.update("DELETE FROM local_catalog.variant_identity WHERE sku=?",sku))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("permanent");
            assertThat(insertVariant(first,variant,sku)).isOne();
        } finally {
            jdbc.update("DELETE FROM local_catalog.variant WHERE product_id IN (?,?)",first,second);
            jdbc.update("DELETE FROM local_catalog.product WHERE id IN (?,?)",first,second);
        }
    }

    @Test
    void doesNotEnableAWriteRouteOrOpenOtherApiPaths() throws Exception {
        assertThat(call("POST", LocalCatalogConfiguration.PRODUCTS_PATH).statusCode()).isIn(401, 403);
        assertThat(call("GET", "/api/local-catalog/unknown").statusCode()).isIn(401, 403);
        assertThat(call("GET", "/api/me").statusCode()).isIn(401, 403);
    }
}
