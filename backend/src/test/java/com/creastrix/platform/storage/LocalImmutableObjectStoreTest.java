package com.creastrix.platform.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalImmutableObjectStoreTest {
    @TempDir Path scratch;
    Path root;
    ImmutableObjectStore store;

    @BeforeEach void open() throws IOException {
        root=Files.createDirectory(scratch.toRealPath().resolve("objects"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        store=LocalImmutableObjectStore.open(root);
    }

    @Test void opaqueObjectRoundTripUsesExistingDiskLayoutAndIndependentMetadata() throws Exception {
        var id=UUID.randomUUID(); byte[] content={1,2,3};
        var actual=store.writeNew(id,content);
        content[0]=42;
        assertThat(actual.objectKey()).isEqualTo(id);
        assertThat(actual.byteCount()).isEqualTo(3);
        assertThat(actual.sha256()).isEqualTo("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81");
        assertThat(Files.readAllBytes(root.resolve(id.toString()))).containsExactly(1,2,3);
        var reopened=LocalImmutableObjectStore.open(root);
        assertThat(reopened.read(actual)).containsExactly(1,2,3);
        assertThatIOException().isThrownBy(()->reopened.read(new ImmutableObjectStore.Reference(id,3,"0".repeat(64))));
        assertThatIOException().isThrownBy(()->reopened.read(new ImmutableObjectStore.Reference(id,2,actual.sha256())));
        assertThatIllegalArgumentException().isThrownBy(()->new ImmutableObjectStore.Reference(id,Integer.MAX_VALUE,actual.sha256()));
        assertThatIllegalArgumentException().isThrownBy(()->reopened.writeNew(UUID.randomUUID(),new byte[0]));
        assertThatIllegalArgumentException().isThrownBy(()->reopened.writeNew(UUID.randomUUID(),new byte[ImmutableObjectStore.MAX_BYTES+1]));
    }

    @Test void collidingWriterMustSyncDirectoryBeforeReportingAnOccupiedDurableKey() throws Exception {
        var key=UUID.randomUUID(); var first=store.writeNew(key,new byte[]{3});
        var failedParent=mock(FileChannel.class);
        doThrow(new IOException("TEST_DIRECTORY_SYNC_FAILED")).when(failedParent).force(true);
        // Inject failure only on the directory channel, not the temporary file's write/force.
        try(var channels=mockStatic(FileChannel.class,call->
                call.getMethod().getName().equals("open") && root.equals(call.getArgument(0)) ?
                        failedParent : call.callRealMethod())) {
            assertThatThrownBy(()->store.writeNew(key,new byte[]{9})).isExactlyInstanceOf(IOException.class)
                    .hasMessage("TEST_DIRECTORY_SYNC_FAILED");
            verify(failedParent).force(true);
        }
        assertThatThrownBy(()->store.writeNew(key,new byte[]{9})).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(store.read(first)).containsExactly(3);
        try(var files=Files.list(root)) { assertThat(files.map(Path::getFileName)).containsExactly(Path.of(key.toString())); }
    }
}
