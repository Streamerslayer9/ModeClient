package com.example.gulp;

/**
 * The perks a pred villager can learn, bought on the MCA villager's Perks screen. Every level a villager gains after level 1
 * gives them one perk point (so 9 points by level 10, exactly enough for every rank of every perk below). The ranks are saved
 * on the villager itself (VillagerHolding, key "gulp_comfort"), so each villager in each world has its own level, points and
 * perks. Add a new perk at the END of this list, give it an effect in VillagerHolding.tick and a line in the screen.
 * The ordinal is what goes over the network (action 21 and VillagerInfoPacket).
 */
public enum VillagerPerk {
    SOOTHING("Soothing Touch", "Heals you +0.5 HP every second per rank.", 3),
    EMBRACE("Long Embrace", "Holds you +12 seconds longer per rank.", 3),
    WARM_BELLY("Warm Belly", "Feeds you while holding you: more hunger per rank.", 3);

    public final String title;
    public final String description;
    public final int maxRank;

    VillagerPerk(String title, String description, int maxRank) {
        this.title = title;
        this.description = description;
        this.maxRank = maxRank;
    }

    public static VillagerPerk byIndex(int i) {
        VillagerPerk[] v = values();
        return i >= 0 && i < v.length ? v[i] : null;
    }

    /** Every rank of every perk added together (the points a level 10 villager has). */
    public static int totalRanks() {
        int total = 0;
        for (VillagerPerk p : values()) total += p.maxRank;
        return total;
    }

    /** Extra HP per second at this rank of Soothing Touch. */
    public static float soothingBonus(int rank) { return 0.5f * rank; }

    /** Extra holding time, in ticks, at this rank of Long Embrace. */
    public static int embraceBonusTicks(int rank) { return 12 * 20 * rank; }
}
