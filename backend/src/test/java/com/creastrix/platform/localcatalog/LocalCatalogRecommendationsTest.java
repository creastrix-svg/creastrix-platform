package com.creastrix.platform.localcatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.creastrix.platform.localcatalog.LocalCatalogReader.Entry;
import com.creastrix.platform.localcatalog.LocalCatalogReader.Recommendation;

import static org.assertj.core.api.Assertions.assertThat;

class LocalCatalogRecommendationsTest {
    private static Entry item(String id, String family, String use, List<String> categories,
                              String title, String description) {
        return new Entry(id, "offer", id, "/" + id, Map.of("en", title), Map.of(), Map.of("en", description),
                null, null, categories, null, null, null, family, family, use);
    }

    private static Entry plain(String id, String... categories) {
        return item(id, null, null, List.of(categories), "", "");
    }

    private static Recommendation rail(String section, String... ids) {
        return new Recommendation("owner", section, List.of(ids));
    }

    private static List<Recommendation> forOwner(List<Entry> entries, List<Recommendation> configured) {
        return LocalCatalogRecommendations.select(entries, configured).stream()
                .filter(rail -> "owner".equals(rail.productId())).toList();
    }

    @Test
    void preservesFamilyAccessoriesInSellerOpeningAndUsesSellerTextBeforeCategoryFallback() {
        var kitchen = List.of("living", "kitchen");
        var source = item("owner", "family", "cup", kitchen, "Ceramic espresso cup",
                "A small espresso cup for serving strong coffee.");
        var semantic = item("other-cup", null, "different-key", List.of("gifts"), "Ceramic espresso cup",
                "This espresso cup serves freshly brewed coffee.");
        var entries = List.of(source,
                item("mini","family","cup", kitchen, "", ""), item("wide","family","cup", kitchen, "", ""),
                item("tray","family","tray", kitchen, "", ""), item("stand","family","stand", kitchen, "", ""),
                item("unrelated", null, "cup", List.of("desk"), "Pencil holder", "A desk organiser for pencils."),
                semantic, plain("carafe", "kitchen"), plain("elsewhere", "desk"), plain("plate", "kitchen"),
                plain("cutlery", "kitchen"), plain("trivet", "kitchen"), plain("wine-rack", "kitchen"));
        assertThat(forOwner(entries, List.of(rail("studio","mini","wide","tray","stand")))).containsExactly(
                rail("studio","tray","stand","mini","wide"), rail("related","other-cup"),
                rail("section","carafe","plate","cutlery","trivet","wine-rack"));
    }

    @Test
    void returnsAllRankedMatchesFollowedByAllOtherSectionMembersAcrossCarouselPages() {
        var entries = new ArrayList<Entry>();
        for (String id : List.of("owner","e","f","g","h","i","j")) {
            entries.add(item(id, null, null, List.of("desk"), "Letter tray",
                    "An upright letter tray organises loose papers, envelopes and office stationery."));
        }
        for (String id : List.of("a","b","c","d","k","l","m","n","o","p","q")) entries.add(plain(id,"desk"));
        entries.add(plain("unrelated","kitchen"));
        var rails = forOwner(entries, List.of(rail("studio","a","b","c","d")));
        assertThat(rails).containsExactly(rail("studio","a","b","c","d"),
                rail("related","e","f","g","h","i","j"), rail("section","k","l","m","n","o","p","q"));
    }

    @Test
    void fallbackUsesOnlyTheFirstConcreteSourceDepartmentWithStableTieBreaks() {
        var entries = List.of(plain("owner","living","gifts","desk","kitchen"),
                plain("a"), plain("b"), plain("c"), plain("d"),
                plain("other-primary","carry","desk"), plain("desk-one","desk"),
                plain("kitchen-only","kitchen"), plain("gift-only","gifts"), plain("living-only","living"),
                plain("desk-two","desk"), plain("stronger-overlap","desk","kitchen"));
        assertThat(forOwner(entries, List.of(rail("studio","a","b","c","d")))).containsExactly(
                rail("studio","a","b","c","d"),
                rail("section","stronger-overlap","desk-one","desk-two","other-primary"));
    }

    @Test
    void broadLivingIsALastResortButGiftOnlyIsNotADepartment() {
        var entries = new ArrayList<Entry>();
        entries.add(plain("owner","gifts","living"));
        IntStream.range(1, 5).forEach(i -> entries.add(plain("open-" + i, "carry")));
        entries.add(plain("remaining","living","desk"));
        entries.add(plain("gift","gifts"));
        var preferred = rail("studio","open-1","open-2","open-3","open-4");
        assertThat(forOwner(entries, List.of(preferred))).containsExactly(preferred, rail("section","remaining"));
        entries.set(0, plain("owner","gifts"));
        assertThat(forOwner(entries, List.of(preferred))).containsExactly(preferred);
    }

    @Test
    void noRepetitionOrPaddingForSparseCatalogs() {
        var entries = List.of(plain("owner","living"), plain("sibling","living"));
        assertThat(forOwner(entries, List.of(rail("related", "missing")))).containsExactly(rail("studio","sibling"));
        assertThat(forOwner(entries.subList(0,1), List.of())).isEmpty();
    }
}
