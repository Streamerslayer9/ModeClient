package com.example.gulp;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A pred villager's item storage. Its size grows with the villager's level (1 row at level 1 up to 4 rows at level 9),
 * it is opened from the Storage button on the villager's Perks screen (action 22) and it is saved on the villager itself
 * (key "gulp_storage"), so every villager in every world has their own. It is a plain vanilla chest-style menu, so
 * shift-click and everything else works as usual and no extra client code is needed.
 *
 * Rules, all checked on the server: adult pred villagers only, the villager must not be cold to you (hearts at or above
 * mcaLowAffectionHearts), and the switch mcaVillagerStorageEnabled must be on. When two players open the same villager at
 * once they share ONE container, so items can never be duplicated. When the villager dies their items drop.
 */
@Mod.EventBusSubscriber(modid = Gulp.ID)
public final class VillagerStorage {
    private VillagerStorage() { }

    private static final String KEY = "gulp_storage";
    static final int MAX_ROWS = 4;
    private static final String TITLE = "Villager storage";

    /** Rows of storage at this villager level: 1 at levels 1-2, 2 at 3-5, 3 at 6-8, 4 at 9-10. */
    static int rowsFor(int level) {
        return Math.max(1, Math.min(MAX_ROWS, 1 + level / 3));
    }

    /** villager uuid -> the container currently open for them (shared by everyone looking at that villager). */
    private static final Map<UUID, Box> OPEN = new HashMap<>();

    /** One villager's storage while it is open. Every change is written straight back to the villager. */
    private static final class Box extends SimpleContainer {
        private final Entity villager;
        private int openers;
        private boolean loading;

        Box(Entity villager, int slots) {
            super(slots);
            this.villager = villager;
        }

        @Override
        public void setChanged() {
            super.setChanged();
            if (!loading) save(villager, this);
        }

        @Override
        public boolean stillValid(Player player) {
            return villager.isAlive() && player.distanceToSqr(villager) <= 64.0;
        }

        @Override
        public void startOpen(Player player) {
            openers++;
        }

        @Override
        public void stopOpen(Player player) {
            openers--;
            if (openers <= 0) {
                OPEN.remove(villager.getUUID(), this); // a new level may have grown the storage by the next time
            }
        }
    }

    private static void save(Entity villager, Container box) {
        ListTag list = new ListTag();
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack st = box.getItem(i);
            if (st.isEmpty()) continue;
            CompoundTag t = new CompoundTag();
            t.putInt("Slot", i);
            st.save(t);
            list.add(t);
        }
        villager.getPersistentData().put(KEY, list);
    }

    private static void load(Entity villager, Box box) {
        box.loading = true;
        ListTag list = villager.getPersistentData().getList(KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            int slot = t.getInt("Slot");
            if (slot >= 0 && slot < box.getContainerSize()) box.setItem(slot, ItemStack.of(t));
        }
        box.loading = false;
    }

    /** Action 22: the player pressed Storage on a villager's Perks screen (arg = the villager's entity id). */
    static void open(ServerPlayer p, int entityId) {
        if (!GulpServerConfig.MCA_VILLAGER_STORAGE_ENABLED.get()) {
            StomachLogic.say(p, Component.literal("Villager storage is turned off on this server."));
            return;
        }
        Entity v = p.level().getEntity(entityId);
        if (v == null || p.distanceToSqr(v) > 32.0 * 32.0) return;
        if (!McaIntegration.isAdultVillager(v) || !McaIntegration.isPredVillager(v)) {
            StomachLogic.say(p, Component.literal("Only adult pred villagers have storage."));
            return;
        }
        if (p.distanceToSqr(v) > 64.0) {
            StomachLogic.say(p, Component.literal("Get closer to " + v.getName().getString() + " first."));
            return;
        }
        if (McaIntegration.heartsOf(v, p) < GulpServerConfig.MCA_LOW_AFFECTION_HEARTS.get()) {
            p.sendSystemMessage(Component.literal("<").append(v.getName()).append("> " + McaIntegration.traitOf(v).snubLine()));
            return;
        }
        int rows = rowsFor(VillagerHolding.levelOf(v));
        Box box = OPEN.get(v.getUUID());
        if (box == null) {
            box = new Box(v, rows * 9);
            load(v, box);
            OPEN.put(v.getUUID(), box);
        }
        final Box view = box;
        final int shownRows = view.getContainerSize() / 9;
        MenuType<ChestMenu> type = switch (shownRows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            default -> MenuType.GENERIC_9x4;
        };
        p.openMenu(new SimpleMenuProvider((id, inv, player) -> new ChestMenu(type, id, inv, view, shownRows),
                Component.literal(v.getName().getString() + " - " + TITLE)));
    }

    /** A villager with stored items died: the items drop where they stood. */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        Entity v = e.getEntity();
        if (v.level().isClientSide || !v.getPersistentData().contains(KEY)) return;
        ListTag list = v.getPersistentData().getList(KEY, Tag.TAG_COMPOUND);
        v.getPersistentData().remove(KEY);
        OPEN.remove(v.getUUID());
        for (int i = 0; i < list.size(); i++) {
            ItemStack st = ItemStack.of(list.getCompound(i));
            if (!st.isEmpty()) v.spawnAtLocation(st);
        }
    }
}
