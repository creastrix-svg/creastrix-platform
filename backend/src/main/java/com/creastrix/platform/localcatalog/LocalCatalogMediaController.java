package com.creastrix.platform.localcatalog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import com.creastrix.platform.storage.ImmutableObjectStore.Reference;
import com.creastrix.platform.storage.LocalImmutableObjectStore;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Reads a published derivative, never an object-store key. Removing or replacing the cover
 * makes its old public URL unavailable on the next request; neither URLs nor responses are cached. */
@RestController
@Profile("local-catalog")
final class LocalCatalogMediaController {
    private final JdbcTemplate db;
    private final String directory;

    LocalCatalogMediaController(JdbcTemplate db, Environment env) {
        this.db = db;
        this.directory = env.getProperty("CREASTRIX_CATALOG_MEDIA_DERIVATIVES");
    }

    @GetMapping("/api/local-catalog/media/{publication}/{rendition}")
    ResponseEntity<byte[]> read(@PathVariable String publication, @PathVariable String rendition) {
        if (!rendition.equals("thumb.jpg") && !rendition.equals("detail.jpg")) return failure(HttpStatus.NOT_FOUND);
        UUID id;
        try { id = UUID.fromString(publication); }
        catch (IllegalArgumentException malformed) { return failure(HttpStatus.NOT_FOUND); }
        if (!id.toString().equals(publication)) return failure(HttpStatus.NOT_FOUND);

        boolean small = rendition.equals("thumb.jpg");
        try {
            // Resolve approval and the exact verified derivative in one statement. Never fetch
            // the original key, and never use the user-facing UUID as a filesystem path.
            var ref = db.query("""
                    SELECT CASE WHEN ? THEN u.thumb_key ELSE u.detail_key END AS object_key,
                           CASE WHEN ? THEN v.thumb_bytes ELSE v.detail_bytes END AS byte_count,
                           CASE WHEN ? THEN v.thumb_sha ELSE v.detail_sha END AS sha256
                      FROM local_catalog.published_photo p
                      JOIN local_catalog.photo_version v ON v.id=p.publication_id
                      JOIN local_catalog.photo_upload u ON u.id=v.id
                     WHERE p.publication_id=?
                    """, (row, n) -> new Reference(row.getObject(1, UUID.class), row.getInt(2), row.getString(3)),
                    small,small,small,id).stream().findFirst();
            if (ref.isEmpty()) return failure(HttpStatus.NOT_FOUND);
            if (directory == null || directory.isBlank()) return failure(HttpStatus.SERVICE_UNAVAILABLE);
            byte[] bytes = LocalImmutableObjectStore.open(Path.of(directory)).read(ref.get());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG)
                    .header("X-Content-Type-Options","nosniff").contentLength(bytes.length).body(bytes);
        } catch (IOException | DataAccessException | IllegalArgumentException unavailable) {
            // Do not disclose an object key, SQL details, path or original filename.
            return failure(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private static ResponseEntity<byte[]> failure(HttpStatus status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).build();
    }
}
