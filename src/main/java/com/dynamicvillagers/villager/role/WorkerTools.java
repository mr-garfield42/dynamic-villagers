package com.dynamicvillagers.villager.role;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.task.PlaceBlockTask;
import com.dynamicvillagers.villager.work.ItemFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public final class WorkerTools {
    public static boolean planWoodenTool(ServerLevel level, Villager villager,
                                         VillagerEssence essence, Item tool, String filter) {
        if (essence.hasItem(villager, ItemFilter.parse(filter))) return false;
        int sticks = tool == Items.WOODEN_SWORD ? 1 : 2;
        if (essence.countItems(villager, stack -> stack.is(Items.STICK)) < sticks
                && planCraftedItem(level, villager, essence, Items.STICK, sticks)) {
            return true;
        }
        return planCraftedItem(level, villager, essence, tool, 1);
    }

    public static boolean planStoneUpgrade(ServerLevel level, Villager villager,
                                           VillagerEssence essence, Item wooden, Item stone) {
        return essence.hasItem(villager, stack -> stack.is(wooden))
                && planCraftedItem(level, villager, essence, stone, 1);
    }

    public static boolean planCraftedItem(ServerLevel level, Villager villager,
                                          VillagerEssence essence, Item item, int count) {
        if (essence.countItems(villager, stack -> stack.is(item)) >= count) return false;
        if (nearbyTable(level, villager) == null) {
            BlockPos spot = tableSpot(level, villager);
            if (spot == null) return false;
            Crafting.Provision table = Crafting.ensureItem(level, villager, essence,
                    Items.CRAFTING_TABLE, 1, 4);
            if (table == Crafting.Provision.UNAVAILABLE) return false;
            essence.getTaskQueue().enqueue(new PlaceBlockTask(spot,
                    "item:" + BuiltInRegistries.ITEM.getKey(Items.CRAFTING_TABLE)));
            return true;
        }
        return Crafting.ensureItem(level, villager, essence, item, count, 4)
                == Crafting.Provision.ENQUEUED;
    }

    private static BlockPos nearbyTable(ServerLevel level, Villager villager) {
        BlockPos origin = villager.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-8, -3, -8), origin.offset(8, 3, 8))) {
            if (level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) return pos.immutable();
        }
        return null;
    }

    private static BlockPos tableSpot(ServerLevel level, Villager villager) {
        BlockPos origin = villager.blockPosition();
        for (int radius = 1; radius <= 3; radius++) {
            for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -1, -radius),
                    origin.offset(radius, 1, radius))) {
                if (Math.max(Math.abs(pos.getX() - origin.getX()), Math.abs(pos.getZ() - origin.getZ())) != radius) {
                    continue;
                }
                if ((level.getBlockState(pos).isAir() || level.getBlockState(pos).canBeReplaced())
                        && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) {
                    return pos.immutable();
                }
            }
        }
        return null;
    }

    private WorkerTools() {
    }
}
