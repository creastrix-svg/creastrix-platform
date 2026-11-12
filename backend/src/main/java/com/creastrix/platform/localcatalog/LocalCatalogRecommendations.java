package com.creastrix.platform.localcatalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.creastrix.platform.localcatalog.LocalCatalogReader.Entry;
import com.creastrix.platform.localcatalog.LocalCatalogReader.Recommendation;

/** Reserve the seller's opening first, then return the entire ranked lower carousel.
 * Its page size is a display concern; related and section retain their distinct provenance.
 * The local storefront has one seller; a multi-seller feed requires explicit seller scoping. */
final class LocalCatalogRecommendations {
    private static final int SELLER_LIMIT = 4;

    private LocalCatalogRecommendations() {}

    static List<Recommendation> select(List<Entry> entries, List<Recommendation> configured) {
        var offers = entries.stream().filter(e -> "offer".equals(e.kind())).toList();
        var visible = offers.stream().map(Entry::id).collect(Collectors.toSet());
        Map<String, List<String>> sellerRows = new HashMap<>();
        for (var rail : configured) {
            if ("studio".equals(rail.section())) {
                sellerRows.put(rail.productId(), rail.itemIds().stream().filter(visible::contains).toList());
            }
        }
        var similarity = new CatalogTextSimilarity(offers);
        var result = new ArrayList<Recommendation>();
        for (var source : offers) {
            var studio = sellerRows.getOrDefault(source.id(), List.of());
            var others = offers.stream().filter(e -> !source.id().equals(e.id())).toList();
            var shown = seller(source, others, studio);
            if (!shown.isEmpty()) result.add(new Recommendation(source.id(), "studio", List.copyOf(shown)));

            // Score each unseen candidate once. Zero means the text provides too
            // little evidence; the section shelf never poses as a text match.
            var matches = others.stream().filter(e -> !shown.contains(e.id()))
                    .map(e -> new Match(e.id(), similarity.score(source, e)))
                    .filter(m -> m.score() > 0)
                    .sorted(Comparator.comparingDouble(Match::score).reversed())
                    .map(Match::id).toList();
            if (!matches.isEmpty()) {
                result.add(new Recommendation(source.id(), "related", matches));
                shown.addAll(matches);
            }
            String section = department(source);
            if (section == null) continue;

            // One actual department, not a union of broad merchandising tags.
            // More specific overlap wins, then items for which this is also the
            // primary department. Ties retain DB order (display_order, id).
            var sourceCategories = new HashSet<>(source.categories());
            sourceCategories.remove("gifts");
            sourceCategories.remove("living");
            var order = Comparator.comparingLong((Entry e) -> e.categories().stream().distinct()
                    .filter(sourceCategories::contains).count()).reversed()
                    .thenComparing(e -> !section.equals(department(e)));
            var sameSection = others.stream().filter(e -> !shown.contains(e.id()) && e.categories().contains(section))
                    .sorted(order).map(Entry::id).toList();
            if (!sameSection.isEmpty()) result.add(new Recommendation(source.id(), "section", sameSection));
        }
        return List.copyOf(result);
    }

    private record Match(String id, double score) {}

    private static String department(Entry entry) {
        // Categories are in stored editorial order. "Gifts" spans unrelated
        // departments; "living" is a last resort only if it is assigned.
        return entry.categories().stream().filter(c -> !c.equals("gifts") && !c.equals("living"))
                .findFirst().orElse(entry.categories().contains("living") ? "living" : null);
    }

    private static Set<String> seller(Entry source, List<Entry> others, List<String> configured) {
        var order = Comparator.comparing((Entry e) -> !sameFamily(source, e))
                .thenComparing(e -> substitutes(source, e))
                .thenComparing(preferredOrder(configured));
        return others.stream().sorted(order).limit(SELLER_LIMIT).map(Entry::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Comparator<Entry> preferredOrder(List<String> ids) {
        var rank = new HashMap<String, Integer>();
        for (int i = 0; i < ids.size(); i++) rank.put(ids.get(i), i);
        return Comparator.comparingInt(e -> rank.getOrDefault(e.id(), Integer.MAX_VALUE));
    }

    private static boolean sameFamily(Entry a, Entry b) {
        return a.collectionId() != null && a.collectionId().equals(b.collectionId());
    }

    /** Legacy merchandising hint for the upper seller opening only. */
    private static boolean substitutes(Entry a, Entry b) {
        return a.substitutionKey() != null && a.substitutionKey().equals(b.substitutionKey());
    }
}
