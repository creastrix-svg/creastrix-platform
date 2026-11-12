package com.creastrix.platform.localcatalog;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import com.creastrix.platform.localcatalog.LocalCatalogReader.Entry;
import static org.assertj.core.api.Assertions.assertThat;

class CatalogTextSimilarityTest {
    private static Entry item(String id, String title, String body) {
        return new Entry(id, "offer", id, "/" + id, Map.of("en", title), Map.of("en", "Walnut"),
                Map.of("en", body), null, null, List.of("living"), null, null, null, null, null, null);
    }

    @Test
    void recognizesIndependentPurposeEvidenceInParaphrasedDescriptionsNotNamesOrTypeKeys() {
        var first = item("a", "A", "Stows loose change and pocket essentials at the entryway");
        var second = item("b", "B", "Organises coins and daily belongings beside the doorway");
        var incidental = item("c", "C", "A brush for scrubbing discarded coins");
        var ranker = new CatalogTextSimilarity(List.of(first, second, incidental));
        assertThat(ranker.score(first, second)).isPositive().isEqualTo(ranker.score(second, first));
        assertThat(ranker.score(first, incidental)).isZero();
        var replacement = item("b", "B", "An adjustable reading lamp");
        assertThat(new CatalogTextSimilarity(List.of(first, replacement)).score(first, replacement)).isZero();
    }

    @Test
    void sameAppearanceMarketingAndEvenIdenticalSubtitlesDoNotReplaceSellerEvidence() {
        var first = item("a", "Catchall", "Handmade premium walnut. A beautiful gift.");
        var repeated = item("b", "Catchall", "A beautiful gift. Premium handmade walnut.");
        var empty = item("c", "Catchall", "");
        var ranker = new CatalogTextSimilarity(List.of(first, repeated, empty));
        assertThat(ranker.score(first, repeated)).isZero();
        assertThat(ranker.score(first, empty)).isZero();
    }

    @Test
    void unrelatedDescriptionsDoNotBecomeSimilarJustBecauseSevenTranslationsRepeatTheSameIncidentalWord() {
        var first = localized("a", Map.of("en","Valet tray","de","Ablage","ru","Лоток"),
                Map.of("en","Keys reside in a tiny recess","de","Ein exklusives handgefertigtes Geschenk",
                        "ru","Прекрасный подарок ручной работы","fr","Un cadeau artisanal de qualité",
                        "es","Un hermoso regalo hecho a mano","it","Un bel regalo fatto a mano","pl","Idealny prezent wykonany ręcznie"));
        var other = localized("b", Map.of("en","Keyring","de","Schlüsselring","ru","Брелок"),
                Map.of("en","Keys attach to a circular band","de","Ein exklusives handgefertigtes Geschenk",
                        "ru","Прекрасный подарок ручной работы","fr","Un cadeau artisanal de qualité",
                        "es","Un hermoso regalo hecho a mano","it","Un bel regalo fatto a mano","pl","Idealny prezent wykonany ręcznie"));
        assertThat(new CatalogTextSimilarity(List.of(first, other)).score(first, other)).isZero();
    }

    @Test
    void twoGroundedTranslationPivotsTeachAnUnknownSynonymForUntranslatedNewProducts() {
        var a = item("new-item-one", "Atelier A", "A settee for seating visitors in the foyer");
        var b = item("new-item-two", "Atelier B", "A sofa for seating guests in the foyer");
        assertThat(new CatalogTextSimilarity(List.of(a, b)).score(a, b)).isZero();
        var sofa = translatedTerm("s1", "sofa");
        var settee = translatedTerm("s2", "settee");
        assertThat(new CatalogTextSimilarity(List.of(a, b, sofa, settee)).score(a, b)).isPositive();
    }

    private static Entry translatedTerm(String id, String term) {
        return localized(id, Map.of("en",term, "fr","canapé", "es","sofá"),
                Map.of("en","Corner " + term, "fr","Canapé capitonné", "es","Sofá tapizado"));
    }

    private static Entry localized(String id, Map<String, String> titles, Map<String, String> descriptions) {
        return new Entry(id, "offer", id, "/" + id, titles, Map.of(), descriptions,
                null, null, List.of(), null, null, null, null, null, null);
    }

    @Test
    void germanOnlyDescriptionsNeedConcreteCluesNotJustBoilerplate() {
        var a = localized("one", Map.of("de","Kaffeebecher"), Map.of("de","Kaffeebecher für Espresso im Büro"));
        var b = localized("two", Map.of("de","Kaffeebecher"), Map.of("de","Kaffeebecher ideal für Espresso im Garten"));
        var generic = localized("three", Map.of("de","Kaffeebecher"), Map.of("de","Perfektes Geschenk, hochwertig und handgefertigt"));
        var other = localized("four", Map.of("de","Kaffeebecher"), Map.of("de","Perfektes Geschenk, hochwertig und handgefertigt"));
        var ranker = new CatalogTextSimilarity(List.of(a, b, generic, other));
        assertThat(ranker.score(a, b)).isPositive();
        assertThat(ranker.score(generic, other)).isZero();
    }

    @Test
    void isolatedInflectedGermanAndRussianMarketingAreNotPurchaseIntent() {
        for (var pair : Map.of("de","Ein wunderschönes handgefertigtes Geschenk für jeden Anlass",
                "ru","Идеальный подарок на любой праздник").entrySet()) {
            var marketing = Map.of(pair.getKey(), pair.getValue());
            var a = localized("a", Map.of(pair.getKey(),"Atelier A"), marketing);
            var b = localized("b", Map.of(pair.getKey(),"Atelier B"), marketing);
            assertThat(new CatalogTextSimilarity(List.of(a, b)).score(a, b)).as(pair.getKey()).isZero();
        }
    }

    @Test
    void distinguishesTheTrayAndPlantPotCasesFromSuperficiallyRelatedAccessories() {
        var drift = item("a", "Valet tray", "A shallow recess gives keys and small essentials a place to land. The walnut grain runs across the tray and into the curved rim.");
        var dot = item("b", "Letter tray", "Low sides keep the profile of the bright red tray close to the desk.");
        var orbit = item("c", "Hoop keyring", "A bright blue enamel hoop stands out against a cluster of keys.");
        var loop = item("d", "3D-printed plant pot", "Loop is a rounded plant pot with fine horizontal print lines.");
        var tall = item("e", "Tall plant pot", "Tall plant pot");
        var stand = item("f", "Plant stand", "Plant stand");
        var ranker = new CatalogTextSimilarity(List.of(drift, dot, orbit, loop, tall, stand));
        assertThat(ranker.score(drift, dot)).isPositive();
        assertThat(ranker.score(drift, orbit)).isZero();
        assertThat(ranker.score(loop, tall)).isPositive();
        assertThat(ranker.score(loop, stand)).isZero();
    }
}
