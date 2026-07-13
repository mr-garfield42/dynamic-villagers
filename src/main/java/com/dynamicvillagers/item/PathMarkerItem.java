package com.dynamicvillagers.item;

import com.dynamicvillagers.village.ConstructionLedger;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.role.VillagerRole;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Click ground blocks in turn to lay out a path's waypoints, then sneak-click to finalize:
 * the path is posted and the bound villager is set to build it. The accumulating waypoints
 * live in the stack's custom data. See SiteMarkerItem for the villager-binding flow.
 */
public class PathMarkerItem extends SiteMarkerItem {
    private static final String WAYPOINTS_KEY = "dv_path";
    private static final int MAX_WAYPOINTS = 32;

    public PathMarkerItem(Properties properties) {
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
        List<Long> waypoints = readWaypoints(stack);

        if (context.isSecondaryUseActive()) {
            return finalize(level, player, stack, waypoints);
        }
        Villager villager = boundVillager(level, stack);
        if (villager == null) {
            player.displayClientMessage(
                    Component.literal("Right-click a villager first to bind this marker"), true);
            return InteractionResult.FAIL;
        }
        if (waypoints.size() >= MAX_WAYPOINTS) {
            player.displayClientMessage(Component.literal(
                    "Path is at the " + MAX_WAYPOINTS + "-waypoint limit — sneak-click to finish"), true);
            return InteractionResult.FAIL;
        }
        BlockPos clicked = context.getClickedPos();
        waypoints.add(clicked.asLong());
        writeWaypoints(stack, waypoints);
        player.displayClientMessage(Component.literal(
                "Path waypoint %d: %s — sneak-click to finish".formatted(
                        waypoints.size(), clicked.toShortString())), true);
        return InteractionResult.CONSUME;
    }

    private InteractionResult finalize(ServerLevel level, Player player, ItemStack stack, List<Long> waypoints) {
        if (waypoints.size() < 2) {
            player.displayClientMessage(
                    Component.literal("A path needs at least two waypoints"), true);
            return InteractionResult.FAIL;
        }
        Villager villager = boundVillager(level, stack);
        if (villager == null) {
            player.displayClientMessage(
                    Component.literal("The bound villager is gone — rebind and try again"), true);
            return InteractionResult.FAIL;
        }
        List<BlockPos> points = new ArrayList<>();
        for (long packed : waypoints) {
            points.add(BlockPos.of(packed));
        }
        ConstructionLedger.PathSite path = ConstructionLedger.get(level)
                .addPath(points, level.getGameTime());
        VillagerEssence essence = VillagerEssence.get(villager);
        if (essence.getRole() != VillagerRole.BUILDER) {
            essence.setRole(VillagerRole.BUILDER);
            essence.setManagerManagedRole(false);
        }
        essence.setAssignedSiteId(-1); // a path replaces any building assignment
        essence.setAssignedPathId(path.id());
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(WAYPOINTS_KEY));
        player.displayClientMessage(Component.literal(
                "Path #%d (%d waypoints) — %s will build it".formatted(
                        path.id(), points.size(), villager.getName().getString())), true);
        return InteractionResult.CONSUME;
    }

    private static List<Long> readWaypoints(ItemStack stack) {
        List<Long> out = new ArrayList<>();
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            for (long packed : data.copyTag().getLongArray(WAYPOINTS_KEY)) {
                out.add(packed);
            }
        }
        return out;
    }

    private static void writeWaypoints(ItemStack stack, List<Long> waypoints) {
        long[] packed = new long[waypoints.size()];
        for (int i = 0; i < packed.length; i++) {
            packed[i] = waypoints.get(i);
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putLongArray(WAYPOINTS_KEY, packed));
    }
}
