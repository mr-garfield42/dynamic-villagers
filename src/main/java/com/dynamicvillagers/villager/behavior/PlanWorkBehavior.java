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
 * (and it is daytime, and the villager isn't sleeping/panicking), ask the role's planner for the
 * next work cycle. Successful plans re-plan quickly; failed ones back off so resource scans
 * stay cheap. The villager tick also calls {@link #tryPlan} so a stale vanilla activity
 * cannot prevent an awake worker from receiving work.
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
        return canPlan(level, villager, false);
    }

    @Override
    protected void start(ServerLevel level, Villager villager, long gameTime) {
        tryPlan(level, villager, false);
    }

    public static boolean tryPlan(ServerLevel level, Villager villager, boolean force) {
        if (!canPlan(level, villager, force)) {
            return false;
        }
        VillagerEssence essence = VillagerEssence.get(villager);
        RolePlanner planner = RolePlanners.get(essence.getRole());
        boolean planned = planner != null && planner.plan(level, villager, essence);
        if (planned) {
            WorkFocusBehavior.beginWork(villager);
        }
        essence.setNextPlanTime(level.getGameTime() + (planned ? REPLAN_DELAY : FAILED_PLAN_DELAY));
        return planned;
    }

    private static boolean canPlan(ServerLevel level, Villager villager, boolean force) {
        VillagerEssence essence = VillagerEssence.get(villager);
        return essence.getRole() != VillagerRole.NONE
                && essence.getTaskQueue().isEmpty()
                && (force || level.getGameTime() >= essence.getNextPlanTime())
                && !villager.isSleeping()
                && !villager.isBaby()
                && level.isDay()
                && isCalm(villager);
    }

    private static boolean isCalm(Villager villager) {
        return villager.getBrain().getActiveNonCoreActivity()
                .map(activity -> activity != Activity.PANIC && activity != Activity.RAID
                        && activity != Activity.PRE_RAID && activity != Activity.HIDE)
                .orElse(true);
    }
}
