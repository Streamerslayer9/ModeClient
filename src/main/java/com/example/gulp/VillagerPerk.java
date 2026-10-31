package com.example.gulp;

/**
 * The perks a pred villager can learn, bought on the MCA villager's Perks screen. Every level a villager gains after level 1
 * gives them one perk point (9 points by level 10). There are more ranks than points (14), so a villager has to choose, and
 * only a few different perks fit at once: see slotsFor (perk slots). Passive perks work all the time while the villager holds
 * you; active perks fire when something happens (Second Wind when a rest ends well). The ranks are saved
 * on the villager itself (VillagerHolding, key "gulp_comfort"), so each villager in each world has its own level, points and
 * perks. Add a new perk at the END of this list, give it an effect in VillagerHolding.tick and a line in the screen.
 * The ordinal is what goes over the network (action 21 and VillagerInfoPacket).
 */
public enum VillagerPerk {
    SOOTHING("Soothing Touch", "Heals you +0.5 HP every second per rank.", 3, false),
    EMBRACE("Long Embrace", "Holds you +12 seconds longer per rank.", 3, false),
    WARM_BELLY("Warm Belly", "Feeds you while holding you: more hunger per rank.", 3, false),
    ROOMY_BELLY("Roomy Belly", "Holds one more player at once per rank.", 2, false),
    SECOND_WIND("Second Wind", "When a rest ends well you get Regeneration for 5 seconds per rank.", 3, true);

    public final String title;
    public final String description;
    public final int maxRank;
    /** True for a perk that fires on an event instead of working all the time. */
    public final boolean active;

    VillagerPerk(String title, String description, int maxRank, boolean active) {
        this.title = title;
        this.description = description;
        this.maxRank = maxRank;
        this.active = active;
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

    /** How many different perks a villager of this level can have ranks in: 1 at levels 1-3, 2 at 4-7, 3 from level 8. */
    public static int slotsFor(int level) { return Math.min(3, 1 + Math.max(1, level) / 4); }

    /** How many players a villager can hold at once: 1, 2 from level 5, plus one per rank of Roomy Belly (never more than 6). */
    public static int capacityFor(int level, int roomyRank) { return Math.min(6, 1 + Math.max(1, level) / 5 + roomyRank); }

    /** How long the Regeneration from Second Wind lasts at this rank, in ticks. */
    public static int secondWindTicks(int rank) { return 5 * 20 * rank; }

    /** Extra holding time, in ticks, at this rank of Long Embrace. */
    public static int embraceBonusTicks(int rank) { return 12 * 20 * rank; }
}
