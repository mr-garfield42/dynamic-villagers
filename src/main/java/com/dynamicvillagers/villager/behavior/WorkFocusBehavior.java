package com.dynamicvillagers.villager.behavior;

import com.dynamicvillagers.villager.VillagerEssence;
import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;

public class WorkFocusBehavior extends Behavior<Villager> {
    public WorkFocusBehavior() {
        super(ImmutableMap.of(), 1200);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Villager villager) {
        return !villager.isBaby() && !VillagerEssence.get(villager).getTaskQueue().isEmpty();
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager villager, long gameTime) {
        return checkExtraStartConditions(level, villager);
    }

    @Override
    protected void start(ServerLevel level, Villager villager, long gameTime) {
        beginWork(villager);
    }

    @Override
    protected void tick(ServerLevel level, Villager villager, long gameTime) {
        beginWork(villager);
    }

    @Override
    protected void stop(ServerLevel level, Villager villager, long gameTime) {
    }

    public static void beginWork(Villager villager) {
        villager.getBrain().eraseMemory(MemoryModuleType.INTERACTION_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.BREED_TARGET);
        WalkTarget target = villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
        if (target != null && target.getTarget() instanceof EntityTracker tracker
                && tracker.getEntity() instanceof Villager) {
            villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        }
    }
}
