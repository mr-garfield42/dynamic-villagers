package com.dynamicvillagers.villager.role;

import com.dynamicvillagers.village.StorageLedger;
import com.dynamicvillagers.village.VillageAnchor;
import com.dynamicvillagers.villager.PerceptionSystem;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.task.DepositToContainerTask;
import com.dynamicvillagers.villager.task.BreakBlockTask;
import com.dynamicvillagers.villager.task.GoToTask;
import com.dynamicvillagers.villager.task.PickUpItemsTask;
import com.dynamicvillagers.villager.task.PlaceBlockTask;
import com.dynamicvillagers.villager.task.TakeItemsTask;
import com.dynamicvillagers.villager.task.TaskQueue;
import com.dynamicvillagers.villager.task.TillSoilTask;
import com.dynamicvillagers.villager.work.ItemFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Farm upkeep and expansion. The vanilla farmer profession keeps harvesting and replanting
 * on its own — this planner adds what vanilla never does: plant carried seeds on any empty
 * farmland, till hydrated dirt at the farm's edge (hoe required), haul the surplus to
 * storage. Expansion only happens adjacent to existing farmland, so farms grow instead of
 * appearing in the wild.
 */
public class FarmerPlanner implements RolePlanner {
    private static final List<String> KEEP_ON_DEPOSIT = List.of("hoe", "seeds", "food");
    private static final int MIN_FREE_SLOTS = 4;
    private static final int HAUL_THRESHOLD = 16; // deposit once carrying this many non-kept items
    private static final int SCAN_RADIUS = 12;
    private static final int SCAN_HEIGHT = 3;
    private static final int MAX_SPOTS = 8;
    private static final int MAX_TASKS_PER_CYCLE = 4;
    private static final int FETCH_COOLDOWN = 1200;
    private static final int SEED_FETCH_COUNT = 16;
    private static final int FARMLAND_WATER_RANGE = 4; // vanilla hydration range

    @Override
    public boolean plan(ServerLevel level, Villager villager, VillagerEssence essence) {
        TaskQueue queue = essence.getTaskQueue();
        Predicate<ItemStack> keep = ItemFilter.parseAny(KEEP_ON_DEPOSIT);
        boolean haulReady = essence.countEmptySlots(villager) < MIN_FREE_SLOTS
                || essence.countItems(villager, stack -> !keep.test(stack)) >= HAUL_THRESHOLD;
        boolean knowsStorage = essence.getMemory().nearestContainer(villager.blockPosition()) != null
                || StorageLedger.get(level).hasPublicDeposit(
                        VillageAnchor.resolve(level, villager), villager.getUUID());
        if (haulReady && knowsStorage) {
            if (!RequestChore.planCarriedDelivery(level, villager, essence, KEEP_ON_DEPOSIT)) {
                queue.enqueue(new DepositToContainerTask(KEEP_ON_DEPOSIT));
            }
            return true;
        }
        if (essence.countEmptySlots(villager) < MIN_FREE_SLOTS) {
            return false; // full and nowhere to store
        }

        BlockPos workAnchor = workAnchor(level, villager);
        if (!workAnchor.closerThan(villager.blockPosition(), SCAN_RADIUS - 2)) {
            queue.enqueue(new GoToTask(workAnchor, 2));
            return true;
        }

        List<BlockPos> harvestSpots = new ArrayList<>();
        List<BlockPos> plantSpots = new ArrayList<>();
        List<BlockPos> tillSpots = new ArrayList<>();
        scan(level, villager, workAnchor, harvestSpots, plantSpots, tillSpots);

        if (!harvestSpots.isEmpty()) {
            List<BlockPos> batch = harvestSpots.subList(0, Math.min(MAX_TASKS_PER_CYCLE, harvestSpots.size()));
            for (BlockPos crop : batch) {
                queue.enqueue(new BreakBlockTask(crop));
                queue.enqueue(new PickUpItemsTask(crop, 4.0));
                queue.enqueue(new PlaceBlockTask(crop, seedFilter(level.getBlockState(crop))));
            }
            return true;
        }
        if (WorkerTools.planStoneUpgrade(level, villager, essence, Items.WOODEN_HOE, Items.STONE_HOE)) {
            return true;
        }

        long now = level.getGameTime();
        boolean planned = false;
        if (!plantSpots.isEmpty()) {
            boolean hasSeeds = essence.hasItem(villager, ItemFilter.parse("seeds"));
            if (!hasSeeds && !essence.getMemory().knownContainers().isEmpty()
                    && now >= essence.getNextToolFetchTime()) {
                essence.setNextToolFetchTime(now + FETCH_COOLDOWN);
                queue.enqueue(new TakeItemsTask("seeds", SEED_FETCH_COUNT));
                hasSeeds = true; // optimistic: the placements below no-op if the fetch fails
            }
            if (hasSeeds) {
                for (BlockPos spot : plantSpots.subList(0, Math.min(MAX_TASKS_PER_CYCLE, plantSpots.size()))) {
                    queue.enqueue(new PlaceBlockTask(spot, "seeds"));
                }
                planned = true;
            }
        }
        if (!tillSpots.isEmpty() && essence.hasItem(villager, ItemFilter.parse("hoe"))) {
            for (BlockPos spot : tillSpots.subList(0, Math.min(MAX_TASKS_PER_CYCLE, tillSpots.size()))) {
                queue.enqueue(new TillSoilTask(spot));
            }
            planned = true;
        } else if (!tillSpots.isEmpty()) {
            if (WorkerTools.planWoodenTool(level, villager, essence, Items.WOODEN_HOE, "hoe")) {
                planned = true;
            } else if (!essence.getMemory().knownContainers().isEmpty()
                    && now >= essence.getNextToolFetchTime()) {
                essence.setNextToolFetchTime(now + FETCH_COOLDOWN);
                queue.enqueue(new TakeItemsTask("hoe", 1));
                planned = true; // tilling happens next cycle, hoe in hand
            }
        }

        return planned
                || RequestChore.plan(level, villager, essence, KEEP_ON_DEPOSIT)
                || TorchChore.plan(level, villager, essence);
    }

    private static BlockPos workAnchor(ServerLevel level, Villager villager) {
        return villager.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .or(() -> villager.getBrain().getMemory(MemoryModuleType.POTENTIAL_JOB_SITE))
                .filter(pos -> pos.dimension().equals(level.dimension()))
                .map(GlobalPos::pos)
                .orElse(villager.blockPosition());
    }

    private void scan(ServerLevel level, Villager villager, BlockPos origin, List<BlockPos> harvestSpots,
                      List<BlockPos> plantSpots, List<BlockPos> tillSpots) {
        List<BlockPos> harvestCandidates = new ArrayList<>();
        List<BlockPos> plantCandidates = new ArrayList<>();
        List<BlockPos> tillCandidates = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-SCAN_RADIUS, -SCAN_HEIGHT, -SCAN_RADIUS),
                origin.offset(SCAN_RADIUS, SCAN_HEIGHT, SCAN_RADIUS))) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                harvestCandidates.add(pos.immutable());
            } else if (state.is(Blocks.FARMLAND) && level.getBlockState(pos.above()).isAir()) {
                plantCandidates.add(pos.above().immutable());
            } else if ((state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK))
                    && level.getBlockState(pos.above()).isAir()
                    && hasAdjacentFarmland(level, pos)
                    && hasWaterNearby(level, pos)) {
                tillCandidates.add(pos.immutable());
            }
        }
        keepVisibleNearest(level, villager, origin, harvestCandidates, harvestSpots);
        keepVisibleNearest(level, villager, origin, plantCandidates, plantSpots);
        keepVisibleNearest(level, villager, origin, tillCandidates, tillSpots);
    }

    private static String seedFilter(BlockState crop) {
        if (crop.is(Blocks.CARROTS)) return "item:minecraft:carrot";
        if (crop.is(Blocks.POTATOES)) return "item:minecraft:potato";
        if (crop.is(Blocks.BEETROOTS)) return "item:minecraft:beetroot_seeds";
        return "item:minecraft:wheat_seeds";
    }

    private static void keepVisibleNearest(ServerLevel level, Villager villager, BlockPos origin,
                                           List<BlockPos> candidates, List<BlockPos> out) {
        candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
        for (BlockPos pos : candidates) {
            if (out.size() >= MAX_SPOTS) {
                return;
            }
            if (PerceptionSystem.canSee(level, villager, pos)) {
                out.add(pos);
            }
        }
    }

    private static boolean hasAdjacentFarmland(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(pos.relative(direction)).is(Blocks.FARMLAND)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasWaterNearby(ServerLevel level, BlockPos pos) {
        for (BlockPos nearby : BlockPos.betweenClosed(
                pos.offset(-FARMLAND_WATER_RANGE, 0, -FARMLAND_WATER_RANGE),
                pos.offset(FARMLAND_WATER_RANGE, 1, FARMLAND_WATER_RANGE))) {
            if (level.getFluidState(nearby).is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }
}
