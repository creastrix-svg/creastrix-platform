package com.creastrix.platform.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** One owner-only directory on a trusted local POSIX filesystem. Each object keeps its
 * existing flat UUID filename and 0400 permissions; there is no public/static serving root. */
public final class LocalImmutableObjectStore implements ImmutableObjectStore {
    private static final LinkOption NO_FOLLOW = LinkOption.NOFOLLOW_LINKS;
    private final Path directory;
    private final UserPrincipal owner;
    private final Object directoryKey;

    private LocalImmutableObjectStore(Path directory, UserPrincipal owner, Object directoryKey) {
        this.directory = directory;
        this.owner = owner;
        this.directoryKey = directoryKey;
    }

    /** Never create, resolve through a symlink, or repair the operator-provisioned root. */
    public static LocalImmutableObjectStore open(Path directory) throws IOException {
        Objects.requireNonNull(directory);
        var owner = directory.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        var attrs = checkDirectory(directory, owner);
        return new LocalImmutableObjectStore(directory, owner, attrs.fileKey());
    }

    private static PosixFileAttributes checkDirectory(Path directory, UserPrincipal owner) throws IOException {
        if (!directory.isAbsolute() || !directory.equals(directory.toRealPath()))
            throw new IOException("A canonical private object directory is required");
        var attrs = Files.readAttributes(directory, PosixFileAttributes.class, NO_FOLLOW);
        if (!attrs.isDirectory() || attrs.fileKey() == null || !attrs.owner().equals(owner)
                || !attrs.permissions().equals(PosixFilePermissions.fromString("rwx------")))
            throw new IOException("Private object directory ownership or permissions do not match");
        return attrs;
    }

    private void checkCurrentDirectory() throws IOException {
        if (!directoryKey.equals(checkDirectory(directory, owner).fileKey()))
            throw new IOException("Private object directory was replaced");
    }

    private void syncDirectory() throws IOException {
        try (var parent = FileChannel.open(directory, StandardOpenOption.READ, NO_FOLLOW)) {
            parent.force(true);
        }
    }

    @Override public Reference writeNew(UUID objectKey, byte[] validatedBytes) throws IOException {
        Objects.requireNonNull(objectKey);
        if (validatedBytes == null || validatedBytes.length < 1 || validatedBytes.length > MAX_BYTES)
            throw new IllegalArgumentException("Invalid object length");
        byte[] bytes = validatedBytes.clone();
        checkCurrentDirectory();
        var temporary = Files.createTempFile(directory, ".upload-", ".part",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (var file = FileChannel.open(temporary, StandardOpenOption.WRITE, NO_FOLLOW)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("r--------"));
                file.force(true);
            }
            try {
                Files.createLink(path(objectKey), temporary);
            } catch (FileAlreadyExistsException occupied) {
                // Another writer may have created its hard link but not synced the directory yet.
                // Do not signal a retryable occupied key until its name is independently durable.
                syncDirectory();
                throw new FileAlreadyExistsException("Immutable object key already occupied");
            }
            syncDirectory();
            return new Reference(objectKey, bytes.length, fingerprint(bytes));
        } finally {
            Files.delete(temporary); // Only this call's temporary name, never the immutable object.
        }
    }

    @Override public byte[] read(Reference reference) throws IOException {
        Objects.requireNonNull(reference);
        checkCurrentDirectory();
        var filePath = path(reference.objectKey());
        var attrs = Files.readAttributes(filePath, PosixFileAttributes.class, NO_FOLLOW);
        if (!attrs.isRegularFile() || !attrs.owner().equals(owner)
                || !attrs.permissions().equals(PosixFilePermissions.fromString("r--------")))
            throw new IOException("Invalid private object");
        var buffer = ByteBuffer.allocate(reference.byteCount());
        try (var file = FileChannel.open(filePath, StandardOpenOption.READ, NO_FOLLOW)) {
            if (file.size() != buffer.capacity()) throw new IOException("Private object length mismatch");
            while (buffer.hasRemaining()) {
                if (file.read(buffer) <= 0) throw new IOException("Incomplete private object");
            }
            if (file.size() != buffer.capacity()) throw new IOException("Private object length mismatch");
        }
        byte[] bytes = buffer.array();
        if (!fingerprint(bytes).equals(reference.sha256())) throw new IOException("Private object fingerprint mismatch");
        return bytes;
    }

    private Path path(UUID objectKey) { return directory.resolve(objectKey.toString()); }
    private static String fingerprint(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
