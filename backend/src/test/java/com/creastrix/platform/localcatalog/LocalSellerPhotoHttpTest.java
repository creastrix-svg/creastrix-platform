package com.creastrix.platform.localcatalog;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import javax.imageio.ImageIO;

import com.creastrix.platform.productmedia.ProductPhotoProcessor;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local-catalog")
@Testcontainers
class LocalSellerPhotoHttpTest {
    @TempDir static Path privateArea;
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("creastrix_local_catalog_test")
            .withCreateContainerCmdModifier(c -> c.getHostConfig().withNetworkMode("bridge").withPublishAllPorts(false)
                    .withPortBindings(new Ports(ExposedPort.tcp(5432),Ports.Binding.bindIp("127.0.0.1"))));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry props) {
        props.add("CREASTRIX_CATALOG_DB_URL",() -> "jdbc:postgresql://127.0.0.1:"+PG.getMappedPort(5432)+"/"+PG.getDatabaseName());
        props.add("CREASTRIX_CATALOG_DB_USERNAME",PG::getUsername);
        props.add("CREASTRIX_CATALOG_DB_PASSWORD",PG::getPassword);
        props.add("CREASTRIX_CATALOG_MEDIA_DERIVATIVES",() -> {
            try { return privateArea.toRealPath().resolve("derivatives").toString(); }
            catch (java.io.IOException e) { throw new IllegalStateException(e); }
        });
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired LocalCatalogReader reader;
    private final HttpClient client=HttpClient.newHttpClient();

    private HttpResponse<byte[]> request(String method,String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    private static String url(UUID id,String rendition) { return "/api/local-catalog/media/"+id+"/"+rendition; }
    private LocalCatalogReader.Entry entry(String id) {
        return reader.read().entries().stream().filter(e->e.id().equals(id)).findFirst().orElseThrow();
    }
    private LocalCatalogReader.Variant variant(String id,String finish) {
        return reader.read().products().stream().filter(p->p.id().equals(id)).flatMap(p->p.variants().stream())
                .filter(v->v.id().equals(finish)).findFirst().orElseThrow();
    }

    @Test void onlyCompleteCurrentCoversServeVerifiedDerivativesAndNeverTheirOriginals() throws Exception {
        var mode=PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
        var originals=Files.createDirectory(privateArea.toRealPath().resolve("originals"),mode);
        var derivatives=Files.createDirectory(privateArea.toRealPath().resolve("derivatives"),mode);
        var processor=ProductPhotoProcessor.open(originals,derivatives);
        var bitmap=new BufferedImage(1800,900,BufferedImage.TYPE_INT_RGB);
        bitmap.setRGB(15,21,0xc8952a);
        var stream=new ByteArrayOutputStream();
        assertThat(ImageIO.write(bitmap,"png",stream)).isTrue();
        byte[] source=stream.toByteArray();
        // SQL arrays may have a non-one lower bound. Reindex the same three stored
        // paths with a zero origin to ensure “first photo” means first in order.
        db.update("""
                UPDATE local_catalog.variant SET photos=('[0:2]=' || photos::text)::text[]
                 WHERE product_id='loop' AND id='cobalt'
                """);
        var initialTide=entry("tide"); var aliasCover=entry("loop");
        var originalGallery=variant("loop","cobalt").photos();
        assertThat(aliasCover.photo()).isEqualTo(originalGallery.getFirst());

        var coral=LocalSellerPhotoCommand.publish(db,processor,"loop","coral",source);
        assertThat(entry("loop")).isEqualTo(aliasCover);
        assertThat(variant("loop","coral").photos().getFirst()).isEqualTo(url(coral,"detail.jpg"));

        var id=LocalSellerPhotoCommand.publish(db,processor,"loop","cobalt",source);
        assertThat(entry("tide")).isEqualTo(initialTide);
        assertThat(entry("loop").thumb()).isEqualTo(url(id,"thumb.jpg"));
        assertThat(entry("loop").photo()).isEqualTo(url(id,"detail.jpg"));
        var gallery=variant("loop","cobalt").photos();
        assertThat(gallery).hasSameSizeAs(originalGallery);
        assertThat(gallery.getFirst()).isEqualTo(url(id,"detail.jpg"));
        assertThat(gallery.subList(1,gallery.size())).isEqualTo(originalGallery.subList(1,originalGallery.size()));
        for (var edge : new int[]{480,1600}) {
            var response=request("GET",url(id,edge==480?"thumb.jpg":"detail.jpg"));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).contains("image/jpeg");
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            var head=request("HEAD",url(id,edge==480?"thumb.jpg":"detail.jpg"));
            assertThat(head.statusCode()).isEqualTo(200);
            assertThat(head.body()).isEmpty();
            assertThat(head.headers().firstValue("Content-Type")).isEqualTo(response.headers().firstValue("Content-Type"));
            assertThat(head.headers().firstValue("Content-Length")).contains(Integer.toString(response.body().length));
            assertThat(response.body()).isNotEqualTo(source);
            var image=ImageIO.read(new ByteArrayInputStream(response.body()));
            assertThat(image.getWidth()).isEqualTo(edge); assertThat(image.getHeight()).isEqualTo(edge/2);
        }
        var sourceKey=db.queryForObject("SELECT original_key FROM local_catalog.photo_upload WHERE id=?",UUID.class,id);
        assertThat(Files.readAllBytes(originals.resolve(sourceKey.toString()))).isEqualTo(source);
        assertThat(request("GET",url(id,"original.jpg")).statusCode()).isEqualTo(404);
        assertThat(request("HEAD",url(id,"original.jpg")).statusCode()).isIn(401,403);
        assertThat(request("GET",url(sourceKey,"detail.jpg")).statusCode()).isEqualTo(404);
        assertThat(request("POST",url(id,"detail.jpg")).statusCode()).isIn(401,403);
        var rowsBefore=db.queryForObject("SELECT count(*) FROM local_catalog.photo_upload",Integer.class);
        assertThatThrownBy(()->LocalSellerPhotoCommand.publish(db,processor,"loop","missing",source))
                .isInstanceOf(LocalSellerPhotoCommand.InvalidTarget.class);
        assertThatThrownBy(()->LocalSellerPhotoCommand.publish(db,processor,"loop","cobalt",new byte[]{1,2}))
                .isInstanceOf(ProductPhotoProcessor.InvalidPhotoException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM local_catalog.photo_upload",Integer.class)).isEqualTo(rowsBefore);
        assertThat(entry("loop").photo()).isEqualTo(url(id,"detail.jpg"));
        var next=LocalSellerPhotoCommand.publish(db,processor,"loop","cobalt",source);
        assertThat(next).isNotEqualTo(id);
        assertThat(request("GET",url(id,"detail.jpg")).statusCode()).isEqualTo(404);
        assertThat(request("GET",url(next,"detail.jpg")).statusCode()).isEqualTo(200);
        assertThat(db.queryForObject("SELECT count(*) FROM local_catalog.photo_version WHERE id=?",Integer.class,id)).isOne();
        assertThat(Files.readAllBytes(originals.resolve(sourceKey.toString()))).isEqualTo(source);
        assertThat(entry("loop").thumb()).isEqualTo(url(next,"thumb.jpg"));
        var tide=LocalSellerPhotoCommand.publish(db,processor,"tide","cobalt",source);
        assertThat(entry("tide").photo()).isEqualTo(url(tide,"detail.jpg"));
        assertThat(entry("tide").thumb()).isEqualTo(url(tide,"thumb.jpg"));
        assertThat(entry("loop").photo()).isEqualTo(url(next,"detail.jpg"));
    }
}
