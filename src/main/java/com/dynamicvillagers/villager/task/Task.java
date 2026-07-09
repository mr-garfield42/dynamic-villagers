package com.dynamicvillagers.villager.task;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

/**
 * A unit of ordered work. Village-level systems (later phases) and debug commands enqueue
 * tasks; ExecuteTaskBehavior ticks the head of the queue. Reactive needs (eating) are Brain
 * behaviors, not tasks. Tasks serialize with the villager and survive chunk unload/reload.
 */
public interface Task {
    String typeId();

    Status tick(ServerLevel level, Villager villager);

    /** Called when the brain suspends work (sleep, panic, ...). The task stays queued. */
    default void onInterrupt(ServerLevel level, Villager villager) {
    }

    CompoundTag save(HolderLookup.Provider provider);

    enum Status {
        IN_PROGRESS,
        DONE,
        FAILED
    }
}
