package com.dynamicvillagers.villager.behavior;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.task.Task;
import com.dynamicvillagers.villager.task.TaskQueue;
import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.npc.Villager;

/**
 * Ticks the head of the villager's task queue. Interruptions (sleep, panic, brain gating by
 * Villager Overhaul) stop the behavior but leave the queue intact, so work resumes after.
 */
public class ExecuteTaskBehavior extends Behavior<Villager> {
    private static final int MAX_DURATION = 1200;

    public ExecuteTaskBehavior() {
        super(ImmutableMap.of(), MAX_DURATION);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Villager villager) {
        return !villager.isSleeping() && !VillagerEssence.get(villager).getTaskQueue().isEmpty();
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager villager, long gameTime) {
        return checkExtraStartConditions(level, villager);
    }

    @Override
    protected void tick(ServerLevel level, Villager villager, long gameTime) {
        TaskQueue queue = VillagerEssence.get(villager).getTaskQueue();
        Task task = queue.current();
        if (task == null) {
            return;
        }
        Task.Status status = task.tick(level, villager);
        if (status != Task.Status.IN_PROGRESS) {
            queue.popCurrent();
        }
    }

    @Override
    protected void stop(ServerLevel level, Villager villager, long gameTime) {
        Task task = VillagerEssence.get(villager).getTaskQueue().current();
        if (task != null) {
            task.onInterrupt(level, villager);
        }
    }
}
