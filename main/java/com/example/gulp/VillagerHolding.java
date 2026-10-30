package com.example.gulp;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A pred villager holding a player to comfort them. Only ever soft and only ever by the player's say-so:
 * the villager offers to a hurt player whose role can be swallowed (Prey or Both), and the player has to press Accept.
 *
 * It works like PlayerHolding does for a player swallower: the held player is put in spectator mode looking through
 * the villager's eyes (so they can't interact with anything), and their old game mode is remembered, including in
 * their saved data so a crash can never leave anyone stuck in spectator mode. The player gets 1 HP back every second
 * and is let out when they are healed, after a minute, or the moment they press Release (G). They are also let out at
 * once if the villager is hurt, dies or goes away, or when the player logs out or the server stops.
 * Struggle (R) rubs the walls: it raises the villager's hearts a little (not more than once every 20 seconds).
 *
 * Comforting levels the villager up (saved on the villager itself; talking, gifts and rides give XP too, see VillagerXp):
 * every finished comfort gives them XP, and each level (1 to 10) heals faster (+0.25 HP/s), holds a little longer (+6 s) and, from level 5, also feeds the player.
 */
public final class VillagerHolding {
    private VillagerHolding() { }

    private static final int RUB_COOLDOWN = 20 * 20;
    static final int MAX_LEVEL = 10;
    private static final String KEY = "gulp_comfort"; // saved on the villager: Level and Xp

    // ------------------------------------------------------------------ the villager's comfort level

    /** The villager's comfort level, 1 to 10 (everyone starts at 1). */
    static int levelOf(Entity villager) {
        int level = villager.getPersistentData().getCompound(KEY).getInt("Level");
        return Math.max(1, Math.min(MAX_LEVEL, level));
    }

    static int xpOf(Entity villager) {
        return villager.getPersistentData().getCompound(KEY).getInt("Xp");
    }

    static int xpNeeded(int level) { return 30 + 20 * level; }

    /** HP healed every second at this level. */
    static float healPerSecond(int level) { return 1.0f + 0.25f * (level - 1); }

    /** The same with the villager's trait: confident preds heal faster. */
    static float healPerSecond(int level, VillagerTrait trait) { return healPerSecond(level) * trait.healMult; }

    /** The same with the villager's trait: shy preds hold you longer. */
    static int maxTicks(int level, VillagerTrait trait) { return Math.round(maxTicks(level) * trait.holdMult); }

    // ------------------------------------------------------------------ the villager's perks (saved on the villager itself)

    /** The rank this villager has in a perk (0 = not learned). Saved with the villager, so it is per villager and per world. */
    static int rankOf(Entity villager, VillagerPerk perk) {
        int rank = villager.getPersistentData().getCompound(KEY).getInt("Perk_" + perk.name());
        return Math.max(0, Math.min(perk.maxRank, rank));
    }

    /** Perk points not spent yet: one per level above 1, minus the ranks already bought. */
    static int pointsFree(Entity villager) {
        int spent = 0;
        for (VillagerPerk perk : VillagerPerk.values()) spent += rankOf(villager, perk);
        return Math.max(0, levelOf(villager) - 1 - spent);
    }

    /** HP per second with level, trait and the Soothing Touch rank. */
    static float healPerSecond(int level, VillagerTrait trait, int soothing) {
        return healPerSecond(level, trait) + VillagerPerk.soothingBonus(soothing);
    }

    /** Holding time with level, trait and the Long Embrace rank. */
    static int maxTicks(int level, VillagerTrait trait, int embrace) {
        return maxTicks(level, trait) + VillagerPerk.embraceBonusTicks(embrace);
    }

    /** The player spent a point on one of this villager's perks (action 21). Everything is checked here, on the server. */
    static void buyPerk(ServerPlayer p, int entityId, int perkIndex) {
        Entity v = p.level().getEntity(entityId);
        VillagerPerk perk = VillagerPerk.byIndex(perkIndex);
        if (perk == null || v == null || p.distanceToSqr(v) > 32.0 * 32.0) return;
        if (!McaIntegration.isAdultVillager(v) || !McaIntegration.isPredVillager(v)) {
            p.sendSystemMessage(Component.literal("Only adult pred villagers have perks."));
            return;
        }
        int rank = rankOf(v, perk);
        if (rank >= perk.maxRank) return;
        if (pointsFree(v) <= 0) {
            p.sendSystemMessage(Component.literal("No perk points left. Villagers get one for every level they gain."));
            return;
        }
        CompoundTag t = v.getPersistentData().getCompound(KEY).copy();
        t.putInt("Perk_" + perk.name(), rank + 1);
        v.getPersistentData().put(KEY, t);
        p.sendSystemMessage(Component.empty().append(v.getName()).append(" learned " + perk.title + " (rank " + (rank + 1) + ")."));
        McaIntegration.sendVillagerInfo(p, entityId);
    }

    /** How long one comfort can last at this level, in ticks: a minute at level 1, 6 seconds longer for every level after. */
    static int maxTicks(int level) { return (60 + 6 * (level - 1)) * 20; }

    /** A finished comfort: the villager earns XP for the HP they healed. Comforting is never capped. */
    private static void awardXp(Entity villager, ServerPlayer p, float healed) {
        addXp(villager, p, 10 + Math.round(healed), false);
    }

    /**
     * Gives a villager XP and levels them up when it is enough (the player is told). Every villager can earn it, but only
     * adults: children never gain XP. Talking, gifts and rides are "capped" sources: together they give a villager at most
     * mcaVillagerXpDailyCap XP per Minecraft day, so they can't be farmed. Comforting is not capped (it needs a hurt player).
     * @return the XP actually given (0 when capped, maxed out or not allowed)
     */
    static int addXp(Entity villager, ServerPlayer p, int amount, boolean capped) {
        if (amount <= 0 || McaIntegration.isProtectedChild(villager)) return 0;
        int level = levelOf(villager);
        if (level >= MAX_LEVEL) return 0;
        CompoundTag t = villager.getPersistentData().getCompound(KEY).copy();
        if (capped) {
            long day = villager.level().getDayTime() / 24000L;
            int used = t.getLong("Day") == day ? t.getInt("DayXp") : 0;
            amount = Math.min(amount, GulpServerConfig.MCA_VILLAGER_XP_DAILY_CAP.get() - used);
            if (amount <= 0) return 0;
            t.putLong("Day", day);
            t.putInt("DayXp", used + amount);
        }
        int xp = xpOf(villager) + amount;
        boolean up = false;
        while (level < MAX_LEVEL && xp >= xpNeeded(level)) {
            xp -= xpNeeded(level);
            level++;
            up = true;
        }
        t.putInt("Level", level);
        t.putInt("Xp", level >= MAX_LEVEL ? 0 : xp);
        villager.getPersistentData().put(KEY, t);
        if (up) {
            p.sendSystemMessage(Component.empty().append(villager.getName())
                    .append(" levelled up (level " + level + ")."));
        }
        return amount;
    }

    // ------------------------------------------------------------------ holding

    private static final class Hold {
        final UUID villager;
        final int previousMode;
        int ticks;
        int rubCd;
        float healed; // total HP given back, for the villager's XP

        Hold(UUID villager, int previousMode) {
            this.villager = villager;
            this.previousMode = previousMode;
        }
    }

    /** player -> the villager holding them. Only lives while the server runs. */
    private static final Map<UUID, Hold> HELD = new HashMap<>();

    public static boolean isHeld(ServerPlayer p) { return HELD.containsKey(p.getUUID()); }

    /** Starts holding the player. The caller has already checked the player agreed and can be held. */
    public static boolean hold(ServerPlayer p, LivingEntity villager) {
        if (PlayerHolding.isHeld(p) || !p.isAlive()) return false;
        int previous = p.gameMode.getGameModeForPlayer().getId();
        HELD.put(p.getUUID(), new Hold(villager.getUUID(), previous));
        p.getPersistentData().putInt(PlayerHolding.KEY_PREV_MODE, previous); // survives a crash
        p.setGameMode(GameType.SPECTATOR);
        p.setCamera(villager);
        p.sendSystemMessage(Component.empty().append(villager.getName()).append(
                " is holding you in their tummy to help you heal. Press your Release key (default G) to get out whenever you like,"
                + " or your Struggle key (default R) to rub the walls."));
        Net.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new HeldPacket(1, villager.getName().getString(), 0f, 0f));
        p.sendSystemMessage(Component.literal("<").append(villager.getName()).append("> " + McaIntegration.traitOf(villager).holdLine()));
        return true;
    }

    /** Every tick for every player (called from PlayerHolding.tickHeld). Heals, and lets the player out when it is time or something is wrong. */
    static void tick(ServerPlayer p) {
        Hold h = HELD.get(p.getUUID());
        if (h == null) return;
        h.ticks++;
        if (h.rubCd > 0) h.rubCd--;
        Entity e = p.serverLevel().getEntity(h.villager);
        if (!(e instanceof LivingEntity v) || !v.isAlive() || v.hurtTime > 0) {
            release(p, "The villager had to let you out.", false);
            return;
        }
        if (p.getCamera() != v) p.setCamera(v); // pressing sneak would make a spectator stop looking through their eyes
        int level = levelOf(v);
        VillagerTrait trait = McaIntegration.traitOf(v);
        if (h.ticks % 20 == 0) {
            float before = p.getHealth();
            p.heal(healPerSecond(level, trait, rankOf(v, VillagerPerk.SOOTHING)));
            h.healed += Math.max(0f, p.getHealth() - before);
            if (level >= 5) p.getFoodData().eat(1, 0.2f); // a well-practised villager feeds you too
        }
        int warm = rankOf(v, VillagerPerk.WARM_BELLY);
        if (warm > 0 && h.ticks % 40 == 0) p.getFoodData().eat(warm, 0.1f * warm); // Warm Belly: every 2 seconds
        if (p.getHealth() >= p.getMaxHealth()) {
            release(p, "All better. You were let out.", true);
        } else if (h.ticks >= maxTicks(level, trait, rankOf(v, VillagerPerk.EMBRACE))) {
            release(p, "You were let out.", true);
        }
    }

    /** The Release key. Always works: this is a gentle rest, never a trap. */
    static void tryRelease(ServerPlayer p) {
        release(p, "You climbed out.", false);
    }

    /** The Struggle key: rub the walls. The villager is pleased (a few hearts), at most once every 20 seconds. */
    static void rub(ServerPlayer p) {
        Hold h = HELD.get(p.getUUID());
        if (h == null) return;
        if (h.rubCd > 0) {
            StomachLogic.say(p, Component.literal("Give it a moment before you rub again."));
            return;
        }
        h.rubCd = RUB_COOLDOWN;
        Entity e = p.serverLevel().getEntity(h.villager);
        if (e != null) McaIntegration.addHearts(e, p, 2);
        StomachLogic.say(p, Component.literal("You rub the walls gently. They seem pleased."));
    }

    /**
     * Lets the player out and gives them their game mode back, putting them down in front of the villager.
     * @param thanked true when it ended on its own (healed or time up): the villager is pleased with that
     */
    static void release(ServerPlayer p, String message, boolean thanked) {
        Hold h = HELD.remove(p.getUUID());
        if (h == null) return;
        Entity villager = p.serverLevel().getEntity(h.villager);
        GameType mode = GameType.byId(h.previousMode);
        if (mode == GameType.SPECTATOR) mode = GameType.SURVIVAL; // never leave anyone stuck as a spectator
        p.setCamera(p);
        p.setGameMode(mode);
        p.getPersistentData().remove(PlayerHolding.KEY_PREV_MODE);
        if (villager != null && villager.level() instanceof ServerLevel w) {
            Vec3 fwd = villager.getViewVector(1.0f).scale(1.5);
            double x = villager.getX() + fwd.x;
            double y = villager.getY();
            double z = villager.getZ() + fwd.z;
            AABB box = p.getBoundingBox().move(x - p.getX(), y - p.getY(), z - p.getZ());
            if (!w.noCollision(box)) { // no room in front of them: use the spot they're standing on
                x = villager.getX();
                y = villager.getY();
                z = villager.getZ();
            }
            p.teleportTo(w, x, y, z, villager.getYRot() + 180f, 0f);
            if (thanked) {
                McaIntegration.addHearts(villager, p, 2);
                awardXp(villager, p, h.healed);
            }
        }
        Net.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new HeldPacket(0, "", 0f, 0f));
        p.sendSystemMessage(Component.literal(message));
        if (villager != null && message.equals("You climbed out.")) {
            VillagerTrait trait = McaIntegration.traitOf(villager);
            p.sendSystemMessage(Component.literal("<").append(villager.getName()).append("> " + trait.earlyExitLine()));
        } else if (villager != null && thanked) {
            VillagerTrait trait = McaIntegration.traitOf(villager);
            p.sendSystemMessage(Component.literal("<").append(villager.getName()).append("> " + trait.finishLine()));
        }
    }

    /** The server is stopping: let everyone out first. */
    static void releaseAll(MinecraftServer server) {
        for (UUID id : new ArrayList<>(HELD.keySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) release(p, "You were let out.", false);
        }
        HELD.clear();
    }
}
