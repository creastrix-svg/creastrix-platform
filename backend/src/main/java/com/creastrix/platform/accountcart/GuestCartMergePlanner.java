package com.creastrix.platform.accountcart;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Computes a line delta for one owner's current cart and one previously uncredited batch.
 * This is not an import command: admission, locking, exact-once receipts, canonicalisation
 * and asset retention must be established by the caller before any resulting write.
 * No stock or prices participate in the requested quantity.
 */
final class GuestCartMergePlanner {
    private GuestCartMergePlanner() {}

    sealed interface Design permits Plain, CanonicalDesign {}
    enum Plain implements Design { INSTANCE }

    /** Complete versioned canonical bytes from a trusted resolver, never a browser key or digest. */
    static final class CanonicalDesign implements Design {
        private final byte[] bytes;

        CanonicalDesign(byte[] bytes) {
            if (bytes.length == 0) throw new IllegalArgumentException("Empty personalised design");
            this.bytes = bytes.clone();
        }

        byte[] bytes() { return bytes.clone(); }

        @Override public boolean equals(Object other) {
            return other instanceof CanonicalDesign design && Arrays.equals(bytes, design.bytes);
        }

        @Override public int hashCode() { return Arrays.hashCode(bytes); }
    }

    /** Stable, server-resolved offer and variant identities; never titles, slugs or local line IDs. */
    record LineKey(String offerId, String variantId, Design design) {
        LineKey {
            requireId(offerId);
            requireId(variantId);
            Objects.requireNonNull(design);
        }
    }

    record ExistingLine(String id, Instant createdAt, LineKey key, long quantity) {
        ExistingLine {
            requireId(id);
            Objects.requireNonNull(createdAt);
            Objects.requireNonNull(key);
            requirePositive(quantity);
        }
    }

    record Units(LineKey key, long quantity) {
        Units {
            Objects.requireNonNull(key);
            requirePositive(quantity);
        }
    }

    record Replacement(String retainedId, long quantity, List<String> redundantIds) {
        Replacement { redundantIds = List.copyOf(redundantIds); }
    }

    /** Insertions need new account line IDs; untouched groups never appear in either list. */
    record Plan(List<Units> insertions, List<Replacement> replacements) {
        Plan {
            insertions = List.copyOf(insertions);
            replacements = List.copyOf(replacements);
        }
    }

    private static final Comparator<ExistingLine> EARLIEST =
            Comparator.comparing(ExistingLine::createdAt).thenComparing(ExistingLine::id);

    static Plan plan(List<ExistingLine> current, List<Units> additions) {
        var touched = new LinkedHashMap<LineKey, Group>();
        for (var addition : additions) {
            var group = touched.computeIfAbsent(addition.key(), ignored -> new Group());
            group.total = Math.addExact(group.total, addition.quantity());
        }
        var seenIds = new HashSet<String>();
        for (var line : current) {
            if (!seenIds.add(line.id())) throw new IllegalArgumentException("Duplicate account line ID");
            var group = touched.get(line.key());
            if (group != null) {
                group.total = Math.addExact(group.total, line.quantity());
                group.existing.add(line);
            }
        }
        var insertions = new ArrayList<Units>();
        var replacements = new ArrayList<Replacement>();
        touched.forEach((key, group) -> {
            if (group.existing.isEmpty()) {
                insertions.add(new Units(key, group.total));
            } else {
                group.existing.sort(EARLIEST);
                replacements.add(new Replacement(group.existing.getFirst().id(), group.total,
                        group.existing.stream().skip(1).map(ExistingLine::id).toList()));
            }
        });
        return new Plan(insertions, replacements);
    }

    private static final class Group {
        long total;
        final List<ExistingLine> existing = new ArrayList<>();
    }

    private static void requirePositive(long units) {
        if (units <= 0) throw new IllegalArgumentException("Quantity must be positive");
    }

    private static void requireId(String id) {
        if (id.isBlank()) throw new IllegalArgumentException("Missing stable ID");
    }
}
