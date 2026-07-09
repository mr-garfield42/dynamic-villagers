package com.dynamicvillagers.villager.task;

import com.dynamicvillagers.villager.work.BreakBlockOrder;
import com.dynamicvillagers.villager.work.WorkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

public class BreakBlockTask implements Task {
    public static final String TYPE = "break_block";

    private final BreakBlockOrder order;

    public BreakBlockTask(BlockPos pos) {
        this.order = new BreakBlockOrder(pos);
    }

    @Override
    public String typeId() {
        return TYPE;
    }

    @Override
    public Status tick(ServerLevel level, Villager villager) {
        if (!WorkHelper.moveIntoReachAndLook(villager, order.pos())) {
            return Status.IN_PROGRESS;
        }
        return order.tick(level, villager) ? Status.DONE : Status.IN_PROGRESS;
    }

    @Override
    public void onInterrupt(ServerLevel level, Villager villager) {
        order.abort(level, villager);
    }

    @Override
    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("pos", order.pos().asLong());
        return tag;
    }

    public static Task load(CompoundTag tag, HolderLookup.Provider provider) {
        return new BreakBlockTask(BlockPos.of(tag.getLong("pos")));
    }
}
