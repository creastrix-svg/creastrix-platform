package com.creastrix.platform.accountcart.persistence;

import java.nio.charset.StandardCharsets;
import com.creastrix.platform.accountcart.assets.LocalCartFixtureAsset;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CartLineArtworkReaderTest {
    @Test void inlinePreviewIsBoundedAndPngOnly() {
        var png = LocalCartFixtureAsset.expectedBytes();
        assertThat(CartLineArtworkReader.boundedPng(png)).isTrue();
        assertThat(CartLineArtworkReader.boundedPng(new byte[CartLineArtworkReader.MAX_BYTES + 1])).isFalse();
        assertThat(CartLineArtworkReader.boundedPng("<svg xmlns='http://www.w3.org/2000/svg'></svg>"
                .getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(CartLineArtworkReader.boundedPng(java.util.Arrays.copyOf(png, 40))).isFalse();
        var largeDeclaredRaster = png.clone();
        java.nio.ByteBuffer.wrap(largeDeclaredRaster).putInt(16, 4096).putInt(20, 4096);
        assertThat(CartLineArtworkReader.boundedPng(largeDeclaredRaster)).isFalse();
    }
}
