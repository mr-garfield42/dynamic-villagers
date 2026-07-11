package com.dynamicvillagers.villager.behavior;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.role.RolePlanner;
import com.dynamicvillagers.villager.role.RolePlanners;
import com.dynamicvillagers.villager.role.VillagerRole;
import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;

/**
 * The bridge between roles and the task queue: when a roled villager has nothing queued
 * (and it is daytime, and the brain isn't resting/panicking), ask the role's planner for the
 * next work cycle. Successful plans re-plan quickly; failed ones back off so resource scans
 * stay cheap. Runs in CORE like ExecuteTaskBehavior, so brain-gating mods pause it too.
 */
public class PlanWorkBehavior extends Behavior<Villager> {
    // Only applied after a plan that PRODUCED work — a short gap here keeps an active worker
    // from idling and wandering off between batches (the builder's main time sink). Idle
    // villagers with nothing to do back off on FAILED_PLAN_DELAY instead, so scan cost for a
    // crowd of idle gatherers is unaffected.
    private static final int REPLAN_DELAY = 20;
    private static final int FAILED_PLAN_DELAY = 400;

    public PlanWorkBehavior() {
        super(ImmutableMap.of());
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Villager villager) {
        VillagerEssence essence = VillagerEssence.get(villager);
        return essence.getRole() != VillagerRole.NONE
                && essence.getTaskQueue().isEmpty()
                && level.getGameTime() >= essence.getNextPlanTime()
                && !villager.isSleeping()
                && !villager.isBaby()
                && level.isDay()
                && isCalm(villager);
    }

    @Override
    protected void start(ServerLevel level, Villager villager, long gameTime) {
        VillagerEssence essence = VillagerEssence.get(villager);
        RolePlanner planner = RolePlanners.get(essence.getRole());
        boolean planned = planner != null && planner.plan(level, villager, essence);
        essence.setNextPlanTime(gameTime + (planned ? REPLAN_DELAY : FAILED_PLAN_DELAY));
    }

    private static boolean isCalm(Villager villager) {
        return villager.getBrain().getActiveNonCoreActivity()
                .map(activity -> activity != Activity.PANIC && activity != Activity.RAID
                        && activity != Activity.PRE_RAID && activity != Activity.HIDE
                        && activity != Activity.REST)
                .orElse(true);
    }
}
