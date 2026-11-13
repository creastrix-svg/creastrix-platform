package com.creastrix.platform.accountcart.assets;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import com.creastrix.platform.storage.ImmutableObjectStore;
import com.creastrix.platform.storage.LocalImmutableObjectStore;

/** Cart-specific limits and references over immutable object storage. Callers must first
 * validate uploads or resolve the internal owner's authorised SQL reference. No key is a grant. */
public final class PrivateCartAssetStore {
    public enum Kind {
        IMAGE(10 * 1024 * 1024), FONT(2 * 1024 * 1024);
        private final int maxBytes;
        Kind(int maxBytes) { this.maxBytes = maxBytes; }
    }

    public record Stored(UUID objectKey, Kind kind, int byteCount, String sha256) {
        public Stored {
            checkSize(kind, byteCount);
            new ImmutableObjectStore.Reference(objectKey, byteCount, sha256);
        }
    }

    private final ImmutableObjectStore objects;
    public PrivateCartAssetStore(ImmutableObjectStore objects) { this.objects = Objects.requireNonNull(objects); }

    public static PrivateCartAssetStore open(Path directory) throws IOException {
        return new PrivateCartAssetStore(LocalImmutableObjectStore.open(directory));
    }

    public Stored writeNew(UUID objectKey, Kind kind, byte[] validatedBytes) throws IOException {
        checkSize(kind, validatedBytes.length);
        var ref = objects.writeNew(objectKey, validatedBytes);
        return new Stored(ref.objectKey(), kind, ref.byteCount(), ref.sha256());
    }

    public byte[] read(Stored authorisedReference) throws IOException {
        return objects.read(new ImmutableObjectStore.Reference(authorisedReference.objectKey(),
                authorisedReference.byteCount(), authorisedReference.sha256()));
    }

    private static void checkSize(Kind kind, int size) {
        Objects.requireNonNull(kind);
        if (size <= 0 || size > kind.maxBytes) throw new IllegalArgumentException("Invalid asset size");
    }
}
