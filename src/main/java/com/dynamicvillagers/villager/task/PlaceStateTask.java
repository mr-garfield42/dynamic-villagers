package com.dynamicvillagers.villager.task;

import com.dynamicvillagers.villager.work.PlaceStateOrder;
import com.dynamicvillagers.villager.work.WorkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Construction placement: make one position hold one exact block state (PlaceBlockTask's
 * item-filter placement is for role chores like torches; blueprints need facing, axis,
 * half, ... to come out right). The material item is derived from the state.
 */
public class PlaceStateTask implements Task {
    public static final String TYPE = "place_state";
    // placement is instant once in reach, so this only bounds a walk that can't arrive;
    // fail fast so the planner re-diffs and scaffolds instead of standing there for a minute
    private static final int GIVE_UP_TICKS = 300;

    private final BlockState state;
    private final PlaceStateOrder order;
    private int ticksRun;

    public PlaceStateTask(BlockPos pos, BlockState state) {
        this.state = state;
        this.order = new PlaceStateOrder(pos, state);
    }

    @Override
    public String typeId() {
        return TYPE;
    }

    @Override
    public Status tick(ServerLevel level, Villager villager) {
        if (++ticksRun > GIVE_UP_TICKS) {
            order.abort(level, villager);
            return Status.FAILED;
        }
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
        tag.put("state", NbtUtils.writeBlockState(state));
        return tag;
    }

    public static Task load(CompoundTag tag, HolderLookup.Provider provider) {
        BlockState state = NbtUtils.readBlockState(
                provider.lookupOrThrow(Registries.BLOCK), tag.getCompound("state"));
        return new PlaceStateTask(BlockPos.of(tag.getLong("pos")), state);
    }
}
