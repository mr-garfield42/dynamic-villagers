package com.dynamicvillagers.villager.role;

import com.dynamicvillagers.villager.VillagerEssence;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

/**
 * Decides the next work cycle for a role and enqueues ordinary tasks for it (the two-layer
 * rule: planners decide WHAT, the task queue decides HOW). Called by PlanWorkBehavior only
 * when the villager's queue is empty, so planners never fight running work.
 */
public interface RolePlanner {

    /** @return true if tasks were enqueued; false means "nothing to do" (planner backs off). */
    boolean plan(ServerLevel level, Villager villager, VillagerEssence essence);
}
