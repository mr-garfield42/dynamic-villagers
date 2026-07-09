package com.dynamicvillagers.villager.task;

import com.dynamicvillagers.villager.work.PlaceBlockOrder;
import com.dynamicvillagers.villager.work.WorkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

public class PlaceBlockTask implements Task {
    public static final String TYPE = "place_block";

    private final PlaceBlockOrder order;

    public PlaceBlockTask(BlockPos pos) {
        this.order = new PlaceBlockOrder(pos);
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
    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("pos", order.pos().asLong());
        return tag;
    }

    public static Task load(CompoundTag tag, HolderLookup.Provider provider) {
        return new PlaceBlockTask(BlockPos.of(tag.getLong("pos")));
    }
}
