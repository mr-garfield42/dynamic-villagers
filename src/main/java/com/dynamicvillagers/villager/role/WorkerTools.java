package com.dynamicvillagers.villager.role;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.task.PlaceBlockTask;
import com.dynamicvillagers.village.Village;
import com.dynamicvillagers.village.VillageManager;
import com.dynamicvillagers.villager.work.ItemFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;

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
        if (Crafting.findTable(level, villager) == null) {
            BlockPos spot = tableSpot(level, villager, null);
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

    @Nullable
    public static BlockPos tableSpot(ServerLevel level, Villager villager, @Nullable BlockPos fallback) {
        Village village = VillageManager.get(level).villageFor(villager.getUUID());
        if (village != null) {
            BlockPos shared = openSpot(level, stationAnchor(village, villager.blockPosition()), 4);
            if (shared != null) return shared;
        }
        if (fallback != null) {
            BlockPos nearby = openSpot(level, fallback, 3);
            if (nearby != null) return nearby;
        }
        return openSpot(level, villager.blockPosition(), 3);
    }

    static BlockPos stationAnchor(Village village, BlockPos worker) {
        BlockPos center = village.center();
        int spacing = Crafting.SHARED_TABLE_RANGE;
        int dx = Math.floorDiv(worker.getX() - center.getX() + spacing / 2, spacing) * spacing;
        int dz = Math.floorDiv(worker.getZ() - center.getZ() + spacing / 2, spacing) * spacing;
        return new BlockPos(center.getX() + dx, center.getY(), center.getZ() + dz);
    }

    @Nullable
    private static BlockPos openSpot(ServerLevel level, BlockPos origin, int maxRadius) {
        int[] yOffsets = {0, 1, -1, 2, 3};
        for (int radius = 0; radius <= maxRadius; radius++) {
            for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, 0, -radius),
                    origin.offset(radius, 0, radius))) {
                if (Math.max(Math.abs(pos.getX() - origin.getX()), Math.abs(pos.getZ() - origin.getZ())) != radius) {
                    continue;
                }
                for (int dy : yOffsets) {
                    BlockPos candidate = pos.offset(0, dy, 0);
                    if (canPlaceAt(level, candidate)) return candidate.immutable();
                }
            }
        }
        return null;
    }

    private static boolean canPlaceAt(ServerLevel level, BlockPos pos) {
        return (level.getBlockState(pos).isAir() || level.getBlockState(pos).canBeReplaced())
                && !level.getBlockState(pos.below()).is(Blocks.BARRIER)
                && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }

    private WorkerTools() {
    }
}
