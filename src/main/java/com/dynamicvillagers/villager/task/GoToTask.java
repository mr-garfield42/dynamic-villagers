package com.dynamicvillagers.villager.task;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;

public class GoToTask implements Task {
    public static final String TYPE = "go_to";
    private static final int GIVE_UP_TICKS = 600;

    private final BlockPos pos;
    private final int closeEnough;
    private int ticksRun;

    public GoToTask(BlockPos pos, int closeEnough) {
        this.pos = pos;
        this.closeEnough = closeEnough;
    }

    @Override
    public String typeId() {
        return TYPE;
    }

    @Override
    public Status tick(ServerLevel level, Villager villager) {
        if (villager.blockPosition().closerThan(pos, closeEnough)) {
            return Status.DONE;
        }
        if (++ticksRun > GIVE_UP_TICKS) {
            return Status.FAILED;
        }
        // every tick, so idle strolls can't hijack a cleared walk target mid-journey
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(pos, 0.6F, closeEnough));
        return Status.IN_PROGRESS;
    }

    @Override
    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("pos", pos.asLong());
        tag.putInt("close_enough", closeEnough);
        return tag;
    }

    public static Task load(CompoundTag tag, HolderLookup.Provider provider) {
        return new GoToTask(BlockPos.of(tag.getLong("pos")), tag.getInt("close_enough"));
    }
}
