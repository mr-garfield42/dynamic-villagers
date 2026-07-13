package com.dynamicvillagers.villager.task;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.role.LumberjackPlanner;
import com.dynamicvillagers.villager.role.TreeScanner;
import com.dynamicvillagers.villager.role.VillagerRole;
import com.dynamicvillagers.villager.work.WorkHelper;
import com.dynamicvillagers.village.Village;
import com.dynamicvillagers.village.VillageManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.Entity;
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
        if (trees.isEmpty()) return false;
        Village village = VillageManager.get(level).villageFor(villager.getUUID());
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof Villager other)) continue;
            VillagerEssence essence = VillagerEssence.get(other);
            if (essence.getRole() != VillagerRole.LUMBERJACK
                    || (village != null && essence.getHomeVillageId() != village.id())
                    || (village == null && other != villager)) continue;
            for (TreeScanner.Tree tree : trees) {
                essence.getMemory().rememberSpot(LumberjackPlanner.TREE_SPOT, tree.base(), level.getGameTime());
            }
            essence.setNextPlanTime(0);
            if (other != villager && essence.getTaskQueue().current() instanceof ExploreForTreesTask) {
                essence.getTaskQueue().popCurrent();
                other.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                other.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
                other.getNavigation().stop();
            }
        }
        return true;
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
