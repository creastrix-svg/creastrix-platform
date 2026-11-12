package com.creastrix.platform.accountcart;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import com.creastrix.platform.accountcart.LocalCartCommands.InvalidBatch;
import com.creastrix.platform.accountcart.assets.LocalCartFixtureAsset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class StudioDesignTest {
    static ObjectNode artwork() {
        var doc=StudioDesign.JSON.createObjectNode().put("sku","cobalt").put("templateRevision","2").put("zone","surface");
        doc.putArray("objects").addObject().put("kind","image").put("fit","free").put("data",Base64.getEncoder()
                .encodeToString(LocalCartFixtureAsset.expectedBytes())).put("size",85.0).put("x",224.36351152983551).put("y",278.0);
        return doc;
    }
    static ObjectNode withText(ObjectNode doc,String font) {
        return doc.withArray("objects").addObject().put("kind","text").put("font",font).put("text"," Л🦕\n o ")
                .put("color","#ffffff").put("size",24.0).put("x",235.62713359187622).put("y",274.125);
    }
    static String testFont() throws Exception {
        return Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of("../frontend/public/fonts/AllertaStencil.ttf")));
    }
    @Test void exactNativeFractionalCoordinatesSpacesOrderAndCustomFontBytesRoundTrip() throws Exception {
        var doc=artwork(); withText(doc,"resource").put("data",testFont());
        var parsed=StudioDesign.parse(doc);
        assertThat(StudioDesign.restore(parsed.sku,parsed.template,parsed.layout,parsed.resources).equals(doc)).isTrue();
        assertThat(parsed.layout.length).isLessThan(256);
        var reversed=doc.deepCopy(); var objects=reversed.withArray("objects"); objects.add(objects.remove(0));
        assertThat(StudioDesign.parse(reversed).layout).isNotEqualTo(parsed.layout);
        var moved=doc.deepCopy(); ((ObjectNode)moved.get("objects").get(1)).put("x",Math.nextUp(235.62713359187622));
        assertThat(StudioDesign.parse(moved).layout).isNotEqualTo(parsed.layout);
    }
    @Test void badFontImageUnknownFieldsOversizeOrEmptyDesignCannotBecomeAPlainItem() {
        for(String change:new String[]{"font","image","zone","position","extra","empty","stencil","text"}) {
            var doc=artwork(); var text=withText(doc,"rounded");
            switch(change) {
                case "font" -> text.put("font","resource").put("data","AAEAAAABAAAAAA==");
                case "image" -> ((ObjectNode)doc.get("objects").get(0)).put("data","aW1hZ2U=");
                case "zone" -> doc.put("zone","base");
                case "position" -> text.put("x",-1000);
                case "extra" -> text.put("owner","someone");
                case "empty" -> { doc.withArray("objects").remove(0); text.put("text","  "); }
                case "stencil" -> text.put("font","stencil");
                case "text" -> text.put("text","x".repeat(33));
            }
            assertThatThrownBy(()->StudioDesign.parse(doc)).as(change).isInstanceOf(InvalidBatch.class).hasNoCause();
        }
    }
    @Test void onlyVersionPinnedFontDefinitionsCanBeSavedAndReopened() throws Exception {
        var files=java.util.Map.of("rounded","Nunito.ttf","editorial","CormorantGaramond.ttf","handwritten","Caveat.ttf");
        for(var entry:files.entrySet()) {
            var bytes=Files.readAllBytes(Path.of("../frontend/public/fonts",entry.getValue()));
            var digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            assertThat(StudioDesign.FONT_PINS.get(entry.getKey())).isEqualTo(digest);
            var doc=artwork(); withText(doc,entry.getKey());
            var parsed=StudioDesign.parse(doc);
            String fields=new String(parsed.layout,java.nio.charset.StandardCharsets.ISO_8859_1);
            assertThat(fields).contains(entry.getKey()+"@"+digest);
            assertThat(StudioDesign.restore(parsed.sku,parsed.template,parsed.layout,parsed.resources).equals(doc)).isTrue();
            byte[] unknown=parsed.layout.clone(); unknown[fields.indexOf(digest)]^=1;
            assertThat(StudioDesign.supportedLayout(unknown)).isFalse();
            assertThatIOException().isThrownBy(()->StudioDesign.restore(parsed.sku,parsed.template,unknown,parsed.resources));
        }
        for(String os:java.util.List.of("sans","serif","mono")) {
            var doc=artwork(); withText(doc,os);
            assertThatThrownBy(()->StudioDesign.parse(doc)).isInstanceOf(InvalidBatch.class);
        }
    }
}
