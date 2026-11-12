package com.creastrix.platform.localcatalog;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.creastrix.platform.localcatalog.LocalCatalogReader.Entry;

/**
 * A small, offline content ranker for the local catalog, not a trained language model.
 * Descriptions supply the evidence; descriptive subtitles can confirm it. Neither merchandising
 * IDs, product names, sections, nor manually assigned comparison types are similarity signals.
 * Build once per catalog snapshot so corpus weights change together with the seller copy.
 */
final class CatalogTextSimilarity {
    private record Copy(Set<String> body, Set<String> subtitle) {}
    private final Map<String, Map<String, Copy>> documents = new HashMap<>();
    private final Map<String, Map<String, Integer>> documentFrequency = new HashMap<>();

    private static final Pattern WORD = Pattern.compile("[\\p{L}]+");
    private static final Pattern MARK = Pattern.compile("\\p{M}+");
    private static final Pattern LOW_SIDES = Pattern.compile("\\blow[- ](?:sides?|sided|rim(?:med)?)\\b");
    private static final Pattern POCKET_CHANGE = Pattern.compile("\\bpocket change\\b");
    private static final Pattern GIFT_COPY = Pattern.compile("\\b(?:gifts?|geschenk\\p{L}*|подар(?:ок|к\\p{L}*)|cadeaux?|regal[oi]s?|prezent\\p{L}*)\\b",
            Pattern.UNICODE_CHARACTER_CLASS);
    // Remove inflected promotional qualifiers, not entire sentences: an espresso mug is
    // still an espresso mug when a seller adds "a beautiful gift for any occasion".
    private static final Pattern GIFT_QUALIFIERS = Pattern.compile(
            "wunderschon\\p{L}*|handgefertigt\\p{L}*|handgemacht\\p{L}*|hochwertig\\p{L}*|einzigartig\\p{L}*|jed(?:en|er|es|em|e)|anlass\\p{L}*"
            + "|идеальн\\p{L}*|прекрасн\\p{L}*|ручно\\p{L}*|праздни\\p{L}*|люб(?:ои|ого|ую|ому|ом|ым|ых)|великолепн\\p{L}*"
            + "|any|every|occasions?|ocasiones|occasion[ei]|cualquier|qualunque|chaque|toutes?|tous|kazd\\p{L}*|okazj\\p{L}*");
    private static final List<String> LANGUAGES = List.of("en", "de", "ru", "fr", "es", "it", "pl");

    // Function words and commonplace merchandising/appearance copy aren't purchase intent.
    private static final Set<String> NOISE = terms("""
            the a an and for from with without of at in on to into by over across against its your
            this that these those it is are can has have as or both where which along through around
            keeps keep gives give make makes made look adds like designed features between beside when
            small little large fine wide tall compact short deep rounded round bright light dark flat
            soft warm natural color colour blue green white black red silver golden beautiful
            shape sides side surface finish edge rim wood wooden stone stoneware walnut oak metal steel
            glass brass linen leather ceramic hand handmade crafted unique artisan premium quality
            perfect ideal great gift present gifts loving stylish modern lovely day everyday home decor
            item product luxury luxurious elegant exquisite high highest best crafted craftsmanship
            geschenk geschenke handgemacht handgefertigt liebevoll hochwertige hochwertig qualitat
            einzigartig einzigartiges perfektes perfekt stilvoll elegant schon schones fur zuhause
            cadeau cadeaux artisanal artisanale fait faite main qualite beau belle parfait parfaite unique
            regalo regalos hecho hecha mano artesanal artesano calidad hermoso hermosa unico unica perfecto perfecta
            regalo regali fatto fatta mano artigianale qualita bello bella unico unica perfetto perfetta
            prezent prezenty recznie rekodzielo wykonany wykonana jakosc jakosci wyjatkowy wyjatkowa idealny idealna
            подарок подарки подарка ручной ручная работа работы красивый красивая уникальный уникальная
            идеальный идеальная высокое высокого качество качества стильный стильная авторский авторская
            les des une dans pour avec aux par sur son ses cet cette qui est ses le la du de et au un
            die der das den dem ein eine einen einer einer mit aus sie zur zum fur und ist sich auf des
            las los una unos con del su sus para por que en un el al la se
            gli una con della del dei delle che nel sul tra il lo da di un per al si
            dla oraz jest sie do od na pod przy jak w z o i a
            при для как или его над под без это для из по от со на не до за и в к с
            """);

    // These are general language aliases, not a product taxonomy. No seller selects a tag.
    private static final Map<String, String> ENGLISH_ALIASES = aliases(
            "organise organize organizes organises organised organized organising organizing store stores stow stows stash stashes storing",
            "coins coin coins change",
            "belongings belongings essentials possessions",
            "entryway entryway doorway entryways doorways",
            "lightup illuminate illuminates illuminating lightup brighten brightens",
            "carry carries carrying transport transports transporting",
            "write writing jot jotting scribble scribbling",
            "sheet sheets paper paperwork documents",
            "plant plants planting growing grow",
            "drink drinks beverage beverages",
            "protect protects protecting safeguard safeguards shield shields",
            "chill chills cool cools chilling");

    CatalogTextSimilarity(List<Entry> offers) {
        for (var entry : offers) {
            var copies = new HashMap<String, Copy>();
            entry.description().forEach((language, text) -> {
                var material = words(entry.material().getOrDefault(language, ""), language);
                var body = words(text, language);
                body.removeAll(material);
                if (body.isEmpty()) return;
                var title = words(entry.title().getOrDefault(language, ""), language);
                title.removeAll(material);
                copies.put(language, new Copy(body, title));
            });
            documents.put(entry.id(), copies);
        }
        var lexicon = learnAliases();
        for (var translations : documents.values()) {
            translations.replaceAll((language, copy) -> {
                var aliases = lexicon.getOrDefault(language, Map.of());
                var body = substitute(copy.body(), aliases);
                var frequencies = documentFrequency.computeIfAbsent(language, ignored -> new HashMap<>());
                body.forEach(word -> frequencies.merge(word, 1, Integer::sum));
                return new Copy(body, substitute(copy.subtitle(), aliases));
            });
        }
    }

    /** Zero means abstain; the separately labelled section shelf may still have enough products. */
    double score(Entry source, Entry candidate) {
        var left = documents.get(source.id());
        var right = documents.get(candidate.id());
        Double primary = null;
        double support = 0;
        for (var lang : LANGUAGES) {
            if (!left.containsKey(lang) || !right.containsKey(lang)) continue;
            double value = score(left.get(lang), right.get(lang), documentFrequency.get(lang));
            if (primary == null) {
                // All pilot entries carry an English rendering. If unavailable/uninformative,
                // use the next shared language. Do not cherry-pick a contradictory translation.
                if (value == 0) return 0;
                primary = value;
            } else support = Math.max(support, value);
        }
        return primary == null ? 0 : primary + 0.1 * Math.min(primary, support);
    }

    /**
     * Mine conservative local equivalents without maintaining an inventory of possible
     * goods. If two one-word terms have the same grounded subtitles in at least two other languages,
     * those translations can bridge future descriptions (e.g. "sofa" and "settee").
     * One ambiguous word with conflicting signatures is left as-is. Do not count a
     * learned synonym and its original spelling as separate evidence.
     */
    private Map<String, Map<String, String>> learnAliases() {
        Map<String, Map<String, Set<Map<String, String>>>> observations = new HashMap<>();
        for (var translations : documents.values()) {
            var anchors = new TreeMap<String, String>();
            translations.forEach((lang, copy) -> {
                if (copy.subtitle().size() == 1) {
                    String term = copy.subtitle().iterator().next();
                    if (copy.body().contains(term)) anchors.put(lang, term);
                }
            });
            if (anchors.size() < 3) continue;
            anchors.forEach((language, term) -> {
                var signature = new HashMap<>(anchors);
                signature.remove(language);
                observations.computeIfAbsent(language, ignored -> new HashMap<>())
                        .computeIfAbsent(term, ignored -> new HashSet<>()).add(Map.copyOf(signature));
            });
        }
        Map<String, Map<String, String>> aliases = new HashMap<>();
        observations.forEach((language, signatures) -> {
            var known = new HashMap<Map<String, String>, String>();
            var canonical = new HashMap<String, String>();
            signatures.keySet().stream().sorted().forEach(term -> {
                var variants = signatures.get(term);
                if (variants.size() == 1) {
                    String first = known.computeIfAbsent(variants.iterator().next(), ignored -> term);
                    canonical.put(term, first);
                }
            });
            aliases.put(language, canonical);
        });
        return aliases;
    }

    private static Set<String> substitute(Set<String> tokens, Map<String, String> aliases) {
        if (aliases.isEmpty()) return tokens;
        var canonical = new HashSet<String>();
        for (String token : tokens) canonical.add(aliases.getOrDefault(token, token));
        return canonical;
    }

    private double score(Copy a, Copy b, Map<String, Integer> frequencies) {
        double shared = 0;
        int overlap = 0, grounded = 0;
        for (var word : a.body()) {
            if (!b.body().contains(word)) continue;
            overlap++;
            shared += weight(word, frequencies);
            if (a.subtitle().contains(word) && b.subtitle().contains(word)) grounded++;
        }
        if (overlap < 2) return 0; // e.g. a keyring and a catchall both mention keys.
        double totalA = 0, totalB = 0;
        for (var word : a.body()) totalA += weight(word, frequencies);
        for (var word : b.body()) totalB += weight(word, frequencies);
        double similarity = shared / Math.sqrt(totalA * totalB);
        // Subtitles can confirm a meaning evidenced in both descriptions. Without that
        // confirmation, require a substantially stronger independent description match.
        if (grounded > 0 && similarity >= 0.19 || overlap >= 3 && similarity >= 0.42) {
            return similarity + Math.min(grounded, 2) * 0.12;
        }
        return 0;
    }

    private double weight(String word, Map<String, Integer> frequencies) {
        return 1 + Math.log((1.0 + documents.size()) / (1.0 + frequencies.get(word)));
    }

    private static Set<String> words(String text, String language) {
        String normalized = MARK.matcher(Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD)).replaceAll("");
        if ("en".equals(language)) {
            normalized = POCKET_CHANGE.matcher(LOW_SIDES.matcher(normalized).replaceAll("shallow")).replaceAll("coins");
        }
        var matcher = WORD.matcher(normalized);
        var result = new HashSet<String>();
        boolean giftCopy = GIFT_COPY.matcher(normalized).find();
        while (matcher.find()) {
            var term = matcher.group();
            if (term.length() < 3 || NOISE.contains(term) || giftCopy && GIFT_QUALIFIERS.matcher(term).matches()) continue;
            if ("en".equals(language)) {
                term = ENGLISH_ALIASES.getOrDefault(term, term);
                if (term.length() > 3 && term.endsWith("s") && !term.endsWith("ss")) term = term.substring(0, term.length() - 1);
            }
            if (!NOISE.contains(term)) result.add(term);
        }
        return result;
    }

    private static Map<String, String> aliases(String... groups) {
        var result = new HashMap<String, String>();
        for (String group : groups) {
            var words = group.split(" ");
            for (String word : words) result.put(word, words[0]);
        }
        return Map.copyOf(result);
    }

    private static Set<String> terms(String source) {
        return Set.copyOf(List.of(MARK.matcher(Normalizer.normalize(source, Normalizer.Form.NFKD))
                .replaceAll("").trim().split("\\s+")));
    }
}
