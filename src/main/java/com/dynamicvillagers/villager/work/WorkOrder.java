package com.dynamicvillagers.villager.work;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

/**
 * A single unit of physical block work. All villager block interaction goes through a
 * WorkOrder ticked by ExecuteWorkBehavior — never through direct world edits — so the
 * "same physical rules as players" invariant (reach, look, tool speed, durability) holds
 * in exactly one place.
 */
public interface WorkOrder {
    BlockPos pos();

    /** Ticked only while the villager is in reach and looking at the target. @return true when finished. */
    boolean tick(ServerLevel level, Villager villager);

    default void abort(ServerLevel level, Villager villager) {
    }
}
