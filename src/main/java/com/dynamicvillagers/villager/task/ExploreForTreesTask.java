package com.dynamicvillagers.villager.task;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.role.LumberjackPlanner;
import com.dynamicvillagers.villager.role.TreeScanner;
import com.dynamicvillagers.villager.work.WorkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;

import java.util.List;

public class ExploreForTreesTask implements Task {
    public static final String TYPE = "explore_for_trees";
    private static final int GIVE_UP_TICKS = 800;
    private final BlockPos target;
    private int ticksRun;

    public ExploreForTreesTask(BlockPos target) {
        this.target = target;
    }

    @Override
    public String typeId() {
        return TYPE;
    }

    @Override
    public Status tick(ServerLevel level, Villager villager) {
        if (ticksRun++ % 10 == 0 && rememberVisibleTrees(level, villager)) {
            return Status.DONE;
        }
        if (villager.blockPosition().closerThan(target, 3.0) || ticksRun > GIVE_UP_TICKS) {
            if (rememberVisibleTrees(level, villager)) return Status.DONE;
            VillagerEssence.get(villager).getMemory().rememberSpot(
                    LumberjackPlanner.TREE_SEARCH_SPOT, target, level.getGameTime());
            return Status.DONE;
        }
        villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(target));
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(target, WorkHelper.WALK_SPEED, 2));
        return Status.IN_PROGRESS;
    }

    private static boolean rememberVisibleTrees(ServerLevel level, Villager villager) {
        List<TreeScanner.Tree> trees = TreeScanner.findTrees(level, villager, 6, 8);
        for (TreeScanner.Tree tree : trees) {
            VillagerEssence.get(villager).getMemory().rememberSpot(
                    LumberjackPlanner.TREE_SPOT, tree.base(), level.getGameTime());
        }
        return !trees.isEmpty();
    }

    @Override
    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("target", target.asLong());
        return tag;
    }

    public static Task load(CompoundTag tag, HolderLookup.Provider provider) {
        return new ExploreForTreesTask(BlockPos.of(tag.getLong("target")));
    }
}
