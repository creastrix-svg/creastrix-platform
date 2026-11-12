package com.creastrix.platform.storage;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Storage port for small immutable binary objects. Callers generate and reserve opaque keys
 * on the server, enforce content/ownership policy and persist the verified reference. Neither
 * a key nor its digest grants access. This port exposes no listing, URL, overwrite or deletion.
 * Large models or streaming transfers require a separate bounded protocol.
 */
public interface ImmutableObjectStore {
    int MAX_BYTES = 16 * 1024 * 1024;

    record Reference(UUID objectKey, int byteCount, String sha256) {
        private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
        public Reference {
            Objects.requireNonNull(objectKey);
            if (byteCount < 1 || byteCount > MAX_BYTES || sha256 == null || !SHA256.matcher(sha256).matches())
                throw new IllegalArgumentException("Invalid object reference");
        }
    }

    /**
     * Create once; success means the exact bytes and their name have been made durable.
     * @throws FileAlreadyExistsException if the key is already occupied; an idempotent
     * caller must still read and compare the authorised existing bytes, not assume a match.
     */
    Reference writeNew(UUID objectKey, byte[] validatedBytes) throws IOException;

    /** Read only through an independently authorised reference; verify length and digest. */
    byte[] read(Reference reference) throws IOException;
}
