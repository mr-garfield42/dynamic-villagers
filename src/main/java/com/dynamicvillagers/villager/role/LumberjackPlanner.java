package com.dynamicvillagers.villager.role;

import com.dynamicvillagers.registry.DVTags;
import com.dynamicvillagers.village.StorageLedger;
import com.dynamicvillagers.village.Village;
import com.dynamicvillagers.village.VillageAnchor;
import com.dynamicvillagers.village.VillageManager;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.VillagerMemory;
import com.dynamicvillagers.villager.task.ChopTreeTask;
import com.dynamicvillagers.villager.task.DepositToContainerTask;
import com.dynamicvillagers.villager.task.ExploreForTreesTask;
import com.dynamicvillagers.villager.task.PickUpItemsTask;
import com.dynamicvillagers.villager.task.PlaceBlockTask;
import com.dynamicvillagers.villager.task.TakeItemsTask;
import com.dynamicvillagers.villager.task.TaskQueue;
import com.dynamicvillagers.villager.work.ItemFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * One lumberjack work cycle: if carrying too much, store it; otherwise pick a known or newly
 * spotted tree, optionally fetch an axe (bare hands work at player speed if none is
 * available), fell it, collect the drops, and replant a carried sapling at the stump.
 */
public class LumberjackPlanner implements RolePlanner {
    public static final String TREE_SPOT = "tree";
    public static final String TREE_SEARCH_SPOT = "tree_search";
    private static final List<String> KEEP_ON_DEPOSIT = List.of("axe", "sapling", "food");
    private static final int MIN_FREE_SLOTS = 4;
    private static final int HAUL_THRESHOLD = 16; // deposit once carrying this many non-kept items
    private static final int TOOL_FETCH_COOLDOWN = 1200;
    private static final int MAX_REMEMBERED_TREES = 8;
    private static final double PICKUP_RADIUS = 4.0;
    private static final int EXTRA_SAPLINGS = 2; // planted around a felled tree, beyond the stump
    private static final int SEARCH_STEP = 32;
    private static final int MAX_SEARCH_DISTANCE = 256;

    @Override
    public boolean plan(ServerLevel level, Villager villager, VillagerEssence essence) {
        VillagerMemory memory = essence.getMemory();
        TaskQueue queue = essence.getTaskQueue();

        Predicate<ItemStack> keep = ItemFilter.parseAny(KEEP_ON_DEPOSIT);
        boolean haulReady = essence.countEmptySlots(villager) < MIN_FREE_SLOTS
                || essence.countItems(villager, stack -> !keep.test(stack)) >= HAUL_THRESHOLD;
        boolean knowsStorage = memory.nearestContainer(villager.blockPosition()) != null
                || StorageLedger.get(level).hasPublicDeposit(
                        VillageAnchor.resolve(level, villager), villager.getUUID());
        if (haulReady && knowsStorage) {
            if (!RequestChore.planCarriedDelivery(level, villager, essence, KEEP_ON_DEPOSIT)) {
                queue.enqueue(new DepositToContainerTask(KEEP_ON_DEPOSIT));
            }
            return true;
        }
        if (essence.countEmptySlots(villager) < MIN_FREE_SLOTS) {
            return false; // full and nowhere to store — stop gathering until that changes
        }

        if (WorkerTools.planWoodenTool(level, villager, essence, Items.WOODEN_AXE, "axe")) {
            return true;
        }
        if (WorkerTools.planStoneUpgrade(level, villager, essence, Items.WOODEN_AXE, Items.STONE_AXE)) {
            return true;
        }
        if (planPublicStorage(level, villager, essence)) {
            return true;
        }

        TreeScanner.Tree tree = findTree(level, villager, memory);
        if (tree == null) {
            if (RequestChore.plan(level, villager, essence, KEEP_ON_DEPOSIT)
                    || TorchChore.plan(level, villager, essence)) {
                return true;
            }
            queue.enqueue(new ExploreForTreesTask(nextSearchTarget(level, villager, memory)));
            return true;
        }

        long now = level.getGameTime();
        if (!essence.hasItem(villager, ItemFilter.parse("axe"))
                && !memory.knownContainers().isEmpty()
                && now >= essence.getNextToolFetchTime()) {
            queue.enqueue(new TakeItemsTask("axe", 1));
            essence.setNextToolFetchTime(now + TOOL_FETCH_COOLDOWN);
        }
        queue.enqueue(new ChopTreeTask(tree.logs()));
        queue.enqueue(new PickUpItemsTask(tree.base(), PICKUP_RADIUS));
        queue.enqueue(new PlaceBlockTask(tree.base(), "sapling"));
        // owner request (2026-07-10): thicken the woods — spare saplings go onto nearby
        // soil too, not just the stump. Planting no-ops gracefully when none are carried.
        for (BlockPos spot : extraSaplingSpots(level, tree.base())) {
            queue.enqueue(new PlaceBlockTask(spot, "sapling"));
        }
        return true;
    }

    private static boolean planPublicStorage(ServerLevel level, Villager villager, VillagerEssence essence) {
        Village village = VillageManager.get(level).villageFor(villager.getUUID());
        if (village == null || !isLeadLumberjack(level, villager, village)) return false;
        List<java.util.Map.Entry<BlockPos, StorageLedger.ContainerRecord>> publicStorage =
                StorageLedger.get(level).recordsNear(village.center(), village.radius()).stream()
                        .filter(entry -> entry.getValue().villageId() == village.id())
                        .filter(entry -> entry.getValue().designation() == StorageLedger.Designation.PUBLIC)
                        .toList();
        if (!publicStorage.isEmpty()
                && publicStorage.stream().anyMatch(entry -> entry.getValue().freeSlots() != 0)) return false;
        BlockPos spot = storageSpot(level, villager, village);
        if (spot == null) return false;
        if (essence.hasItem(villager, stack -> stack.is(Items.CHEST))) {
            essence.getTaskQueue().enqueue(new PlaceBlockTask(spot, "item:minecraft:chest"));
            return true;
        }
        return WorkerTools.planCraftedItem(level, villager, essence, Items.CHEST, 1);
    }

    private static boolean isLeadLumberjack(ServerLevel level, Villager villager, Village village) {
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Villager other && other.isAlive()
                    && VillagerEssence.get(other).getHomeVillageId() == village.id()
                    && VillagerEssence.get(other).getRole() == VillagerRole.LUMBERJACK
                    && other.getUUID().compareTo(villager.getUUID()) < 0) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static BlockPos storageSpot(ServerLevel level, Villager villager, Village village) {
        BlockPos center = village.center();
        for (int radius = 3; radius <= 7; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz : new int[]{-radius, radius}) {
                    BlockPos spot = surfaceSpot(level, center.offset(dx, 0, dz), center.getY());
                    if (spot != null && canReach(villager, spot)) return spot;
                }
            }
            for (int dz = -radius + 1; dz < radius; dz++) {
                for (int dx : new int[]{-radius, radius}) {
                    BlockPos spot = surfaceSpot(level, center.offset(dx, 0, dz), center.getY());
                    if (spot != null && canReach(villager, spot)) return spot;
                }
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos surfaceSpot(ServerLevel level, BlockPos column, int centerY) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
        for (int y = top; y >= top - 10; y--) {
            BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
            BlockState below = level.getBlockState(pos.below());
            if (Math.abs(y - centerY) <= 2
                    && !below.is(Blocks.BARRIER)
                    && (level.getBlockState(pos).isAir() || level.getBlockState(pos).canBeReplaced())
                    && noStorageNearby(level, pos)
                    && below.isFaceSturdy(level, pos.below(), Direction.UP)) {
                return pos;
            }
        }
        return null;
    }

    private static boolean noStorageNearby(ServerLevel level, BlockPos spot) {
        return BlockPos.betweenClosedStream(spot.offset(-1, -1, -1), spot.offset(1, 1, 1))
                .noneMatch(pos -> level.getBlockState(pos).is(DVTags.STORAGE_CONTAINERS));
    }

    private static boolean canReach(Villager villager, BlockPos spot) {
        var path = villager.getNavigation().createPath(spot, 1);
        return path != null && path.canReach();
    }

    private static BlockPos nextSearchTarget(ServerLevel level, Villager villager, VillagerMemory memory) {
        BlockPos anchor = VillageAnchor.resolve(level, villager);
        List<BlockPos> searched = memory.spotsByDistance(TREE_SEARCH_SPOT, anchor);
        BlockPos last = searched.isEmpty() ? null : searched.getLast();
        Direction direction = last == null
                ? new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}[
                Math.floorMod(villager.getUUID().hashCode(), 4)]
                : directionFrom(anchor, last);
        BlockPos from = last == null ? anchor : last;
        if (last != null && Math.sqrt(last.distSqr(anchor)) >= MAX_SEARCH_DISTANCE) {
            for (BlockPos old : searched) memory.forgetSpot(TREE_SEARCH_SPOT, old);
            direction = direction.getClockWise();
            from = anchor;
        }
        BlockPos flat = from.relative(direction, SEARCH_STEP);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, flat.getX(), flat.getZ());
        return new BlockPos(flat.getX(), y, flat.getZ());
    }

    private static Direction directionFrom(BlockPos anchor, BlockPos pos) {
        int dx = pos.getX() - anchor.getX();
        int dz = pos.getZ() - anchor.getZ();
        return Math.abs(dx) > Math.abs(dz)
                ? (dx >= 0 ? Direction.EAST : Direction.WEST)
                : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
    }

    /**
     * Up to two plantable spots around a felled tree: dirt-family soil with a free block
     * above, clear of the stump replant and spread apart so the copse has room to grow.
     * Lingering canopy leaves make a spot look occupied at execution time — PlaceBlockTask
     * treats that as "already taken" and moves on, which is fine.
     */
    private static List<BlockPos> extraSaplingSpots(ServerLevel level, BlockPos base) {
        List<BlockPos> spots = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(base.offset(-3, -1, -3), base.offset(3, 1, 3))) {
            if (spots.size() >= EXTRA_SAPLINGS) {
                break;
            }
            if (pos.distManhattan(base) < 2
                    || (!spots.isEmpty() && spots.getFirst().distManhattan(pos) < 3)) {
                continue; // crowded saplings shade each other out
            }
            if (!level.getBlockState(pos.below()).is(BlockTags.DIRT)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.canBeReplaced()) {
                spots.add(pos.immutable());
            }
        }
        return spots;
    }

    @Nullable
    private TreeScanner.Tree findTree(ServerLevel level, Villager villager, VillagerMemory memory) {
        for (BlockPos remembered : memory.spotsByDistance(TREE_SPOT, villager.blockPosition())) {
            TreeScanner.Tree tree = TreeScanner.collectTree(level, remembered);
            if (tree != null) {
                return tree;
            }
            memory.forgetSpot(TREE_SPOT, remembered); // gone or invalid — stop believing in it
        }
        List<TreeScanner.Tree> spotted =
                TreeScanner.findTrees(level, villager, TreeScanner.SCAN_RADIUS, MAX_REMEMBERED_TREES);
        long now = level.getGameTime();
        for (TreeScanner.Tree tree : spotted) {
            memory.rememberSpot(TREE_SPOT, tree.base(), now);
        }
        return spotted.isEmpty() ? null : spotted.getFirst();
    }
}
