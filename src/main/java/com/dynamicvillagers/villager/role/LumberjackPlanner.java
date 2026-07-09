package com.dynamicvillagers.villager.role;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.VillagerMemory;
import com.dynamicvillagers.villager.task.ChopTreeTask;
import com.dynamicvillagers.villager.task.DepositToContainerTask;
import com.dynamicvillagers.villager.task.PickUpItemsTask;
import com.dynamicvillagers.villager.task.PlaceBlockTask;
import com.dynamicvillagers.villager.task.TakeItemsTask;
import com.dynamicvillagers.villager.task.TaskQueue;
import com.dynamicvillagers.villager.work.ItemFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * One lumberjack work cycle: if carrying too much, store it; otherwise pick a known or newly
 * spotted tree, optionally fetch an axe (bare hands work at player speed if none is
 * available), fell it, collect the drops, and replant a carried sapling at the stump.
 */
public class LumberjackPlanner implements RolePlanner {
    public static final String TREE_SPOT = "tree";
    private static final List<String> KEEP_ON_DEPOSIT = List.of("axe", "sapling", "food");
    private static final int MIN_FREE_SLOTS = 4;
    private static final int HAUL_THRESHOLD = 16; // deposit once carrying this many non-kept items
    private static final int TOOL_FETCH_COOLDOWN = 1200;
    private static final int MAX_REMEMBERED_TREES = 8;
    private static final double PICKUP_RADIUS = 4.0;

    @Override
    public boolean plan(ServerLevel level, Villager villager, VillagerEssence essence) {
        VillagerMemory memory = essence.getMemory();
        TaskQueue queue = essence.getTaskQueue();

        Predicate<ItemStack> keep = ItemFilter.parseAny(KEEP_ON_DEPOSIT);
        boolean haulReady = essence.countEmptySlots(villager) < MIN_FREE_SLOTS
                || essence.countItems(villager, stack -> !keep.test(stack)) >= HAUL_THRESHOLD;
        if (haulReady && memory.nearestContainer(villager.blockPosition()) != null) {
            queue.enqueue(new DepositToContainerTask(KEEP_ON_DEPOSIT));
            return true;
        }
        if (essence.countEmptySlots(villager) < MIN_FREE_SLOTS) {
            return false; // full and nowhere to store — stop gathering until that changes
        }

        TreeScanner.Tree tree = findTree(level, villager, memory);
        if (tree == null) {
            // no trees around — light up the neighborhood instead (milestone 2.2 chore)
            return TorchChore.plan(level, villager, essence);
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
        memory.forgetSpot(TREE_SPOT, tree.base());
        return true;
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
