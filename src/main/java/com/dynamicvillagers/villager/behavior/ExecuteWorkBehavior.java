package com.dynamicvillagers.villager.behavior;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.work.WorkOrder;
import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;

/**
 * Drives the villager's current WorkOrder: walk into reach, look at the target, then let the
 * order progress. The order survives interruptions (sleep, panic) and resumes on restart.
 */
public class ExecuteWorkBehavior extends Behavior<Villager> {
    private static final double REACH = 4.0; // slightly under player creative reach, from the eyes
    private static final float WALK_SPEED = 0.6F;
    private static final int MAX_DURATION = 1200;

    public ExecuteWorkBehavior() {
        super(ImmutableMap.of(), MAX_DURATION);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Villager villager) {
        return !villager.isSleeping() && VillagerEssence.get(villager).getCurrentWork() != null;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager villager, long gameTime) {
        return !villager.isSleeping() && VillagerEssence.get(villager).getCurrentWork() != null;
    }

    @Override
    protected void tick(ServerLevel level, Villager villager, long gameTime) {
        VillagerEssence essence = VillagerEssence.get(villager);
        WorkOrder work = essence.getCurrentWork();
        if (work == null) {
            return;
        }

        villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(work.pos()));

        Vec3 targetCenter = Vec3.atCenterOf(work.pos());
        if (villager.getEyePosition().distanceToSqr(targetCenter) > REACH * REACH) {
            if (gameTime % 20 == 0) {
                villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                        new WalkTarget(work.pos(), WALK_SPEED, 2));
            }
            return; // no progress until in reach — player rules
        }

        if (work.tick(level, villager)) {
            essence.setCurrentWork(null);
        }
    }

    @Override
    protected void start(ServerLevel level, Villager villager, long gameTime) {
        WorkOrder work = VillagerEssence.get(villager).getCurrentWork();
        if (work != null) {
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(work.pos(), WALK_SPEED, 2));
        }
    }

    @Override
    protected void stop(ServerLevel level, Villager villager, long gameTime) {
        WorkOrder work = VillagerEssence.get(villager).getCurrentWork();
        if (work != null) {
            work.abort(level, villager); // clears in-progress visuals; the order itself is kept
        }
    }
}
