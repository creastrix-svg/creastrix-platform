package com.creastrix.platform.accountcart.assets;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIOException;

class PrivateCartAssetStoreTest {
    @TempDir Path scratch;
    Path directory;
    PrivateCartAssetStore store;

    @BeforeEach
    void setUp() throws IOException {
        directory = Files.createDirectory(scratch.toRealPath().resolve("private-objects"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        store = PrivateCartAssetStore.open(directory);
    }

    @Test
    void reopensExactOwnerOnlyBytesAfterRestartWithoutRetainingTheCallersArray() throws IOException {
        byte[] payload = {0, 12, -1, 100};
        var key = UUID.randomUUID();
        var reference = store.writeNew(key, PrivateCartAssetStore.Kind.FONT, payload);
        payload[0] = 77;
        assertThat(reference.byteCount()).isEqualTo(4);
        var reopened = PrivateCartAssetStore.open(directory);
        byte[] restored = reopened.read(reference);
        assertThat(restored).containsExactly(0, 12, -1, 100);
        restored[1] = 0;
        assertThat(reopened.read(reference)).containsExactly(0, 12, -1, 100);
        assertThat(Files.getPosixFilePermissions(directory.resolve(key.toString())))
                .isEqualTo(PosixFilePermissions.fromString("r--------"));
        assertThatIOException().isThrownBy(() -> reopened.writeNew(key, reference.kind(), new byte[]{42}));
        assertThat(reopened.read(reference)).containsExactly(0, 12, -1, 100);
    }

    @Test
    void refusesNonprivateAndRedirectedObjectsWithoutRepairingAnything() throws IOException {
        var id = UUID.randomUUID();
        var ref = store.writeNew(id, PrivateCartAssetStore.Kind.IMAGE, new byte[]{1, 2});
        var alias = Files.createSymbolicLink(scratch.resolve("alias"), directory);
        assertThatIOException().isThrownBy(() -> PrivateCartAssetStore.open(alias));
        var otherId = UUID.randomUUID();
        Files.createSymbolicLink(directory.resolve(otherId.toString()), directory.resolve(id.toString()));
        var linked = new PrivateCartAssetStore.Stored(otherId, ref.kind(), ref.byteCount(), ref.sha256());
        assertThatIOException().isThrownBy(() -> store.read(linked));
        assertThatIOException().isThrownBy(() -> store.writeNew(otherId, ref.kind(), new byte[]{5}));
        assertThat(store.read(ref)).containsExactly(1, 2);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-x---"));
        assertThatIOException().isThrownBy(() -> PrivateCartAssetStore.open(directory));
        assertThatIOException().isThrownBy(() -> store.read(ref));
        assertThatIOException().isThrownBy(() -> store.writeNew(UUID.randomUUID(), ref.kind(), new byte[]{1}));
        assertThat(Files.getPosixFilePermissions(directory)).isEqualTo(PosixFilePermissions.fromString("rwxr-x---"));
    }

    @Test
    void refusesCorruptMetadataEmptyFilesAndOversizedPayloads() throws IOException {
        var id = UUID.randomUUID();
        var kind = PrivateCartAssetStore.Kind.FONT;
        assertThatIllegalArgumentException().isThrownBy(() -> store.writeNew(id, kind, new byte[0]));
        assertThatIllegalArgumentException().isThrownBy(() -> store.writeNew(id, kind, new byte[2 * 1024 * 1024 + 1]));
        assertThatIllegalArgumentException().isThrownBy(() -> store.writeNew(id, PrivateCartAssetStore.Kind.IMAGE,
                new byte[10 * 1024 * 1024 + 1]));
        var actual = store.writeNew(id, kind, new byte[]{42, 1});
        var wrongSize = new PrivateCartAssetStore.Stored(id, kind, 1, actual.sha256());
        var wrongDigest = new PrivateCartAssetStore.Stored(id, kind, 2, "0".repeat(64));
        assertThatIOException().isThrownBy(() -> store.read(wrongSize));
        assertThatIOException().isThrownBy(() -> store.read(wrongDigest));
        Files.setPosixFilePermissions(directory.resolve(id.toString()), PosixFilePermissions.fromString("rw-r--r--"));
        assertThatIOException().isThrownBy(() -> store.read(actual));
    }

    @Test
    void concurrentWritersCannotReplaceACompleteObject() throws Exception {
        var key = UUID.randomUUID();
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(attempt(key, (byte) 10, gate));
            var second = pool.submit(attempt(key, (byte) 20, gate));
            gate.countDown();
            var a = first.get(5, TimeUnit.SECONDS);
            var b = second.get(5, TimeUnit.SECONDS);
            assertThat((a == null) ^ (b == null)).isTrue();
            var result = a != null ? a : b;
            assertThat(store.read(result)).containsExactly(a != null ? 10 : 20);
        }
        try (var items = Files.list(directory)) {
            assertThat(items.map(path -> path.getFileName().toString())).containsExactly(key.toString());
        }
    }

    private Callable<PrivateCartAssetStore.Stored> attempt(UUID key, byte value, CountDownLatch gate) {
        return () -> {
            if (!gate.await(5, TimeUnit.SECONDS)) throw new AssertionError("Writer gate timeout");
            try {
                return store.writeNew(key, PrivateCartAssetStore.Kind.IMAGE, new byte[]{value});
            } catch (FileAlreadyExistsException expected) { return null; }
        };
    }

    @Test
    void localFixtureCanBeRebuiltAndReopenedButCannotReplaceOtherBytes() throws IOException {
        LocalCartFixtureAsset.ensurePresent(store);
        LocalCartFixtureAsset.ensurePresent(PrivateCartAssetStore.open(directory));
        var ref = LocalCartFixtureAsset.REFERENCE;
        var image = ImageIO.read(new ByteArrayInputStream(store.read(ref)));
        assertThat(image.getWidth()).isEqualTo(16);
        assertThat(image.getHeight()).isEqualTo(16);
        assertThat(image.getRGB(0, 0)).isNotEqualTo(image.getRGB(15, 0));
        var differentDirectory = Files.createDirectory(scratch.toRealPath().resolve("different"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var other = PrivateCartAssetStore.open(differentDirectory);
        var otherReference = other.writeNew(ref.objectKey(), ref.kind(), new byte[]{1, 11});
        assertThatIOException().isThrownBy(() -> LocalCartFixtureAsset.ensurePresent(other));
        assertThat(other.read(otherReference)).containsExactly(1, 11);
    }
}
