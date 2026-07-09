package com.dynamicvillagers.item;

import com.dynamicvillagers.villager.VillagerEssence;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;

/**
 * Click two corners to designate a quarry pit for the bound villager (the corners' y levels
 * set the top and bottom of the pit). Sneak-click to reset a half-placed first corner.
 * See SiteMarkerItem for the binding flow.
 */
public class QuarryMarkerItem extends SiteMarkerItem {
    private static final String CORNER_KEY = "dv_corner";
    private static final int MAX_SIDE = 32;

    public QuarryMarkerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !mayDesignate(player)) {
            return InteractionResult.PASS;
        }
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = context.getItemInHand();
        if (context.isSecondaryUseActive()) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(CORNER_KEY));
            player.displayClientMessage(Component.literal("Quarry corner reset"), true);
            return InteractionResult.CONSUME;
        }
        Villager villager = boundVillager(level, stack);
        if (villager == null) {
            player.displayClientMessage(
                    Component.literal("Right-click a villager first to bind this marker"), true);
            return InteractionResult.FAIL;
        }

        BlockPos clicked = context.getClickedPos();
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = data != null ? data.copyTag() : new CompoundTag();
        if (!tag.contains(CORNER_KEY)) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack,
                    t -> t.putLong(CORNER_KEY, clicked.asLong()));
            player.displayClientMessage(Component.literal(
                    "Quarry corner 1: %s — now click the opposite corner".formatted(clicked.toShortString())), true);
            return InteractionResult.CONSUME;
        }

        BlockPos cornerA = BlockPos.of(tag.getLong(CORNER_KEY));
        if (Math.abs(cornerA.getX() - clicked.getX()) > MAX_SIDE
                || Math.abs(cornerA.getZ() - clicked.getZ()) > MAX_SIDE) {
            player.displayClientMessage(
                    Component.literal("Quarry sides are limited to " + MAX_SIDE + " blocks"), true);
            return InteractionResult.FAIL;
        }
        VillagerEssence.get(villager).setQuarrySite(new VillagerEssence.QuarrySite(cornerA, clicked));
        ensureMinerRole(villager);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, t -> t.remove(CORNER_KEY));
        player.displayClientMessage(Component.literal(
                "Quarry set: %s to %s".formatted(cornerA.toShortString(), clicked.toShortString())), true);
        return InteractionResult.CONSUME;
    }
}
