package com.dynamicvillagers.villager.task;

import com.dynamicvillagers.villager.work.BreakBlockOrder;
import com.dynamicvillagers.villager.work.WorkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;

/**
 * Fells a tree bottom-up, one log at a time, through BreakBlockOrder (real reach, best axe,
 * player mining speed, durability). Logs that are already gone are skipped, so the task is
 * safe to resume after interruption or reload.
 */
public class ChopTreeTask implements Task {
    public static final String TYPE = "chop_tree";
    private static final int GIVE_UP_TICKS = 2400;
    private static final int STUCK_TICKS_BEFORE_DESCEND = 60;

    private final ArrayDeque<BlockPos> logs;
    @Nullable
    private BreakBlockOrder order;
    @Nullable
    private BreakBlockOrder descendOrder;
    private int ticksRun;
    private int stuckTicks;

    public ChopTreeTask(List<BlockPos> logs) {
        this.logs = logs.stream()
                .sorted(Comparator.comparingInt(BlockPos::getY))
                .collect(ArrayDeque::new, ArrayDeque::add, ArrayDeque::addAll);
    }

    @Override
    public String typeId() {
        return TYPE;
    }

    @Override
    public Status tick(ServerLevel level, Villager villager) {
        if (++ticksRun > GIVE_UP_TICKS) {
            abortOrder(level, villager);
            return Status.FAILED;
        }
        while (!logs.isEmpty() && !level.getBlockState(logs.peek()).is(BlockTags.LOGS)) {
            logs.poll(); // already gone (chopped, decayed, or never was a log)
            order = null;
        }
        if (logs.isEmpty()) {
            abortOrder(level, villager);
            return Status.DONE;
        }
        BlockPos target = logs.peek();
        if (!WorkHelper.moveIntoReachAndLook(villager, target)) {
            descendIfStuckOnCanopy(level, villager, target);
            return Status.IN_PROGRESS;
        }
        stuckTicks = 0;
        if (descendOrder != null) {
            descendOrder.abort(level, villager);
            descendOrder = null;
        }
        if (order == null || !order.pos().equals(target)) {
            order = new BreakBlockOrder(target);
        }
        if (order.tick(level, villager)) {
            logs.poll();
            order = null;
        }
        return logs.isEmpty() ? Status.DONE : Status.IN_PROGRESS;
    }

    /**
     * A villager that ends up on top of the canopy cannot path down (leaves are blocked
     * nodes) and would stand there forever. After a stuck spell it does what a player does:
     * digs the block under its own feet — but only leaves or logs, never terrain, so a
     * villager standing on a hill keeps walking instead of excavating it.
     */
    private void descendIfStuckOnCanopy(ServerLevel level, Villager villager, BlockPos target) {
        if (++stuckTicks < STUCK_TICKS_BEFORE_DESCEND || villager.getY() <= target.getY() + 1) {
            return;
        }
        BlockPos below = villager.blockPosition().below();
        BlockState support = level.getBlockState(below);
        if (!support.is(BlockTags.LEAVES) && !support.is(BlockTags.LOGS)) {
            return;
        }
        if (descendOrder == null || !descendOrder.pos().equals(below)) {
            descendOrder = new BreakBlockOrder(below);
        }
        if (descendOrder.tick(level, villager)) {
            descendOrder = null;
        }
    }

    @Override
    public void onInterrupt(ServerLevel level, Villager villager) {
        abortOrder(level, villager);
    }

    private void abortOrder(ServerLevel level, Villager villager) {
        if (order != null) {
            order.abort(level, villager);
            order = null;
        }
        if (descendOrder != null) {
            descendOrder.abort(level, villager);
            descendOrder = null;
        }
    }

    @Override
    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putLongArray("logs", logs.stream().mapToLong(BlockPos::asLong).toArray());
        return tag;
    }

    public static Task load(CompoundTag tag, HolderLookup.Provider provider) {
        List<BlockPos> logs = java.util.Arrays.stream(tag.getLongArray("logs"))
                .mapToObj(BlockPos::of)
                .toList();
        return new ChopTreeTask(logs);
    }
}
