package com.dynamicvillagers.item;

import com.dynamicvillagers.registry.DVTags;
import com.dynamicvillagers.village.StorageLedger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Designates village storage (Phase 3). Right-click a villager to bind the marker, then click
 * a chest/barrel to make it that villager's PRIVATE storage. Unbound, clicking a container
 * toggles it PUBLIC (village storage) and back. Sneak-click unbinds. Handled via the
 * RightClickBlock event (see DVItems) because the container's own GUI would otherwise eat
 * the click before useOn ever runs.
 */
public class StorageMarkerItem extends SiteMarkerItem {

    public StorageMarkerItem(Properties properties) {
        super(properties);
    }

    public static void designate(ServerLevel level, ServerPlayer player, ItemStack stack, BlockPos pos) {
        if (!mayDesignate(player)) {
            player.displayClientMessage(
                    Component.literal("Site markers require creative mode or permission level 2"), true);
            return;
        }
        if (player.isSecondaryUseActive()) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(VILLAGER_KEY));
            player.displayClientMessage(
                    Component.literal("Marker unbound — clicks now toggle public storage"), true);
            return;
        }
        if (!level.getBlockState(pos).is(DVTags.STORAGE_CONTAINERS)) {
            player.displayClientMessage(
                    Component.literal("Not a storage container (chest or barrel)"), true);
            return;
        }
        StorageLedger ledger = StorageLedger.get(level);
        Villager bound = boundVillager(level, stack);
        if (bound != null) {
            ledger.setDesignation(pos, StorageLedger.Designation.PRIVATE, bound.getUUID());
            player.displayClientMessage(Component.literal(
                    "%s is now %s's private storage".formatted(
                            pos.toShortString(), bound.getName().getString())), true);
            return;
        }
        StorageLedger.ContainerRecord record = ledger.getRecord(pos);
        boolean makePublic = record == null
                || record.designation() != StorageLedger.Designation.PUBLIC;
        ledger.setDesignation(pos,
                makePublic ? StorageLedger.Designation.PUBLIC : StorageLedger.Designation.UNCLAIMED, null);
        player.displayClientMessage(Component.literal(
                "%s is now %s".formatted(pos.toShortString(),
                        makePublic ? "public village storage" : "unclaimed")), true);
    }
}
