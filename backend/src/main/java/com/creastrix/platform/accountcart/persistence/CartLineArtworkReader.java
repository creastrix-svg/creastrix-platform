package com.creastrix.platform.accountcart.persistence;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

import com.creastrix.platform.accountcart.assets.PrivateCartAssetStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

/** Reads a single PNG source, not a rendered composition. Keys never come from the HTTP request. */
public final class CartLineArtworkReader {
    static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_PIXELS = 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final PrivateCartAssetStore files;

    public CartLineArtworkReader(JdbcTemplate jdbc, PrivateCartAssetStore files) {
        this.jdbc = jdbc;
        this.files = files;
    }

    private record Ref(UUID design, long revision, PrivateCartAssetStore.Stored object) { }

    private Optional<Ref> resolve(UUID owner, UUID line) {
        return jdbc.query("""
                SELECT d.design_id, d.revision, a.object_key, a.byte_count, a.sha256
                  FROM account_cart.cart_line l
                  JOIN public.users u ON u.id=l.owner_id AND u.status='ACTIVE'
                  JOIN account_cart.design_revision d ON (d.owner_id,d.design_id,d.revision,d.offer_id,d.variant_id)
                       =(l.owner_id,l.design_id,l.design_revision,l.offer_id,l.variant_id)
                  JOIN account_cart.design_asset da ON (da.owner_id,da.design_id,da.revision)=(d.owner_id,d.design_id,d.revision)
                  JOIN account_cart.asset a ON (a.owner_id,a.asset_id)=(da.owner_id,da.asset_id)
                 WHERE l.owner_id=? AND l.id=? AND a.kind='IMAGE' AND a.completed
                 LIMIT 2
                """, (ResultSetExtractor<Optional<Ref>>) rs -> {
            if (!rs.next()) return Optional.empty();
            long count = rs.getLong("byte_count");
            if (count < 1 || count > MAX_BYTES) return Optional.empty();
            var ref = new Ref(rs.getObject("design_id", UUID.class), rs.getLong("revision"),
                    new PrivateCartAssetStore.Stored(rs.getObject("object_key", UUID.class),
                            PrivateCartAssetStore.Kind.IMAGE, (int) count, rs.getString("sha256")));
            return rs.next() ? Optional.empty() : Optional.of(ref);
        }, owner, line);
    }

    public Optional<byte[]> read(UUID owner, UUID line) {
        var ref = resolve(owner, line);
        if (ref.isEmpty()) return Optional.empty();
        try {
            // Verify size, immutable private object permissions and the database digest before use.
            // Filesystem I/O is between standalone SELECTs; no transaction or session lock spans it.
            byte[] bytes = files.read(ref.get().object());
            if (!boundedPng(bytes) || !ref.equals(resolve(owner, line))) return Optional.empty();
            return Optional.of(bytes);
        } catch (IOException unavailable) {
            // Expose neither the storage path nor whether a retained private object is missing.
            return Optional.empty();
        }
    }

    static boolean boundedPng(byte[] bytes) {
        if (bytes.length < 33 || bytes.length > MAX_BYTES) return false;
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return false;
            var reader = readers.next();
            try {
                if (!"PNG".equalsIgnoreCase(reader.getFormatName())) return false;
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                return width > 0 && height > 0 && (long) width * height <= MAX_PIXELS
                        && reader.read(0) != null;
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException invalid) {
            return false;
        }
    }
}
