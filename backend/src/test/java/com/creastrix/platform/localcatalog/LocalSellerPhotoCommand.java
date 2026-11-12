package com.creastrix.platform.localcatalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;

import com.creastrix.platform.productmedia.ProductPhotoProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Test-classpath command for the sole local merchant. Invoking it with OS access to the
 * dedicated catalog credentials approves publication on the local storefront. This is
 * deliberately not an HTTP handler and assigns no seller rights to a customer session.
 */
public final class LocalSellerPhotoCommand {
    /** A definite refusal before any media references or publication have been committed. */
    static final class InvalidTarget extends IllegalArgumentException {}

    private LocalSellerPhotoCommand() {}

    public static void main(String[] args) {
        try {
            if (args.length != 3 || !slug(args[0]) || !slug(args[1])) throw new InvalidTarget();
            String url = required("CREASTRIX_CATALOG_DB_URL"), user = required("CREASTRIX_CATALOG_DB_USERNAME");
            if (!"jdbc:postgresql://127.0.0.1:55483/creastrix_local_catalog".equals(url) ||
                    !"local_catalog_app".equals(user)) throw new IllegalStateException();
            var db = new JdbcTemplate(new DriverManagerDataSource(url,user,required("CREASTRIX_CATALOG_DB_PASSWORD")));
            if (!Boolean.TRUE.equals(db.queryForObject("""
                    SELECT current_database()='creastrix_local_catalog' AND current_user='local_catalog_app'
                    """,Boolean.class))) throw new IllegalStateException();
            var processor = ProductPhotoProcessor.open(Path.of(required("CREASTRIX_CATALOG_MEDIA_ORIGINALS")),
                    Path.of(required("CREASTRIX_CATALOG_MEDIA_DERIVATIVES")));
            byte[] source;
            if ("-".equals(args[2])) source = bounded(System.in);
            else {
                Path file = Path.of(args[2]);
                if (!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new ProductPhotoProcessor.InvalidPhotoException();
                try (var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) { source=bounded(in); }
            }
            System.out.println("published " + publish(db,processor,args[0],args[1],source));
        } catch (InvalidTarget invalidTarget) {
            System.err.println("CATALOG_PHOTO_TARGET_INVALID: select a listed variant."); System.exit(3);
        } catch (ProductPhotoProcessor.InvalidPhotoException invalidFile) {
            System.err.println("CATALOG_PHOTO_REJECTED: select a supported JPEG or PNG."); System.exit(2);
        } catch (Exception unavailable) {
            // A database connection can fail after the publication commit. Inspect the local
            // catalog before retrying; never claim that the previous cover was restored.
            System.err.println("CATALOG_PHOTO_UNCONFIRMED: check local storage, database and current cover before retry."); System.exit(4);
        }
    }

    private static byte[] bounded(InputStream in) throws IOException {
        byte[] source=in.readNBytes(ProductPhotoProcessor.MAX_INPUT_BYTES+1);
        if (source.length==0 || source.length>ProductPhotoProcessor.MAX_INPUT_BYTES) throw new ProductPhotoProcessor.InvalidPhotoException();
        return source;
    }

    static UUID publish(JdbcTemplate db, ProductPhotoProcessor processor, String product, String variant, byte[] bytes) throws IOException {
        // Rejected inputs create no object or staging record. Do all decoding and derivative
        // generation before reserving keys, keeping the prepared result out of the database.
        var prepared=processor.prepare(bytes);
        var id=UUID.randomUUID(); var original=UUID.randomUUID(); var thumb=UUID.randomUUID(); var detail=UUID.randomUUID();
        // Leave a recoverable reservation (also on interruption). No filesystem I/O under
        // catalog locks; the buyer continues to see the previous publication while processing.
        if (db.update("""
                INSERT INTO local_catalog.photo_upload(id,product_id,variant_id,original_key,thumb_key,detail_key)
                SELECT ?,v.product_id,v.id,?,?,? FROM local_catalog.variant v
                  JOIN local_catalog.item_presentation i ON i.product_id=v.product_id
                 WHERE v.product_id=? AND v.id=?
                """, id,original,thumb,detail,product,variant)!=1) throw new InvalidTarget();
        var image=processor.publish(prepared,original,thumb,detail);
        var a=image.original(); var b=image.thumb(); var c=image.detail();
        // Version metadata and the current cover change in ONE commit. Concurrent promotions
        // serialize on (product,variant); the last committed selection is the local cover.
        if (db.update("""
                WITH approved AS (
                    INSERT INTO local_catalog.photo_version(id,product_id,variant_id,
                        original_type,original_bytes,original_sha,original_width,original_height,
                        thumb_bytes,thumb_sha,thumb_width,thumb_height,detail_bytes,detail_sha,detail_width,detail_height)
                    SELECT u.id,u.product_id,u.variant_id,?,?,?,?,?,?,?,?,?,?,?,?,?
                      FROM local_catalog.photo_upload u
                      JOIN local_catalog.item_presentation i ON i.product_id=u.product_id
                     WHERE u.id=? RETURNING id,product_id,variant_id
                ) INSERT INTO local_catalog.photo_cover(product_id,variant_id,publication_id)
                  SELECT product_id,variant_id,id FROM approved
                ON CONFLICT (product_id,variant_id) DO UPDATE SET publication_id=excluded.publication_id
                """, a.mediaType(),a.ref().byteCount(),a.ref().sha256(),a.width(),a.height(),
                b.ref().byteCount(),b.ref().sha256(),b.width(),b.height(),c.ref().byteCount(),c.ref().sha256(),c.width(),c.height(),id)!=1)
            throw new IllegalStateException("CATALOG_PHOTO_APPROVAL_UNAVAILABLE");
        return id;
    }

    private static boolean slug(String s) { return s.length()<=150 && s.matches("[a-z0-9]+(-[a-z0-9]+)*"); }
    private static String required(String name) {
        String v=System.getenv(name); if (v==null || v.isBlank()) throw new IllegalStateException(); return v;
    }
}
