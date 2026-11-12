package com.creastrix.platform.accountcart.assets;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/** Launcher fixture only: exact bytes of a generated two-colour 16-by-16 RGB PNG. */
public final class LocalCartFixtureAsset {
    private LocalCartFixtureAsset() {}

    static final PrivateCartAssetStore.Stored REFERENCE = new PrivateCartAssetStore.Stored(
            UUID.fromString("91940aca-2a1a-49ce-849f-639f792ee201"), PrivateCartAssetStore.Kind.IMAGE, 90,
            "c7d7f035cb9bdc77a79d7a67f51916fe5c82e3f4222a8e3bc3ee96f4ad4b5f21");
    private static final String CHECKER =
            "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAIUlEQVR4nGPgsW3Fiv5vs8SKGEY10EQDLglcBo1qoIkGADj4XhCV6O9FAAAAAElFTkSuQmCC";

    public static byte[] expectedBytes() { return Base64.getDecoder().decode(CHECKER); }

    public static void ensurePresent(PrivateCartAssetStore store) throws IOException {
        byte[] bytes = expectedBytes();
        try {
            if (!REFERENCE.equals(store.writeNew(REFERENCE.objectKey(), REFERENCE.kind(), bytes)))
                throw new IOException("Synthetic fixture reference mismatch");
        } catch (FileAlreadyExistsException existing) {
            // Reuse only after verifying the actual content below.
        }
        if (!Arrays.equals(bytes, store.read(REFERENCE))) throw new IOException("Synthetic fixture bytes differ");
    }

    public static void main(String[] args) throws IOException {
        String value = System.getenv("CART_LOCAL_ASSET_ROOT");
        if (args.length != 0 || value == null || value.isBlank())
            throw new IllegalArgumentException("Set the dedicated CART_LOCAL_ASSET_ROOT directory");
        ensurePresent(PrivateCartAssetStore.open(Path.of(value)));
        System.out.printf("%s\t%d\t%s%n", REFERENCE.objectKey(), REFERENCE.byteCount(), REFERENCE.sha256());
    }
}
