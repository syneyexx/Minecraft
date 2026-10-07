package com.livingmods.simulation.state;

import com.livingmods.common.id.KingdomId;

import java.util.Objects;

/** Canonical unordered kingdom pair for relation lookup. */
public final class DiplomacyPair implements Comparable<DiplomacyPair> {
    private final KingdomId a;
    private final KingdomId b;

    public DiplomacyPair(KingdomId first, KingdomId second) {
        if (first.compareTo(second) <= 0) {
            this.a = first;
            this.b = second;
        } else {
            this.a = second;
            this.b = first;
        }
    }

    public KingdomId first() { return a; }
    public KingdomId second() { return b; }

    @Override
    public int compareTo(DiplomacyPair o) {
        int c = a.compareTo(o.a);
        return c != 0 ? c : b.compareTo(o.b);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DiplomacyPair other)) return false;
        return a.equals(other.a) && b.equals(other.b);
    }

    @Override
    public int hashCode() {
        return Objects.hash(a, b);
    }
}
