package com.dynamicvillagers.villager.task;

import com.dynamicvillagers.villager.work.BreakBlockOrder;
import com.dynamicvillagers.villager.work.WorkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public class BreakBlockTask implements Task {
    public static final String TYPE = "break_block";
    private static final int GIVE_UP_TICKS = 1200; // an unreachable target must not stall the queue
    private static final int APPROACH_RETRY_TICKS = 40;
    private static final int STUCK_TICKS = 60;
    private static final int MAX_APPROACH_ATTEMPTS = 4;

    private final BreakBlockOrder order;
    private int ticksRun;
    private int retryTicks;
    private int attempts;
    private int stuckTicks;
    @Nullable
    private BlockPos approach;
    @Nullable
    private Vec3 lastPosition;

    public BreakBlockTask(BlockPos pos) {
        this.order = new BreakBlockOrder(pos);
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
        if (villager.getEyePosition().distanceToSqr(Vec3.atCenterOf(order.pos()))
                > WorkHelper.REACH * WorkHelper.REACH) {
            updateApproach(level, villager);
            WorkHelper.moveIntoReachAndLook(villager, order.pos(),
                    approach != null ? approach : order.pos(), approach != null ? 0 : 2);
            if (approach == null && attempts >= MAX_APPROACH_ATTEMPTS) {
                order.abort(level, villager);
                return Status.FAILED;
            }
            return Status.IN_PROGRESS;
        }
        return order.tick(level, villager) ? Status.DONE : Status.IN_PROGRESS;
    }

    private void updateApproach(ServerLevel level, Villager villager) {
        if (lastPosition != null && villager.position().distanceToSqr(lastPosition) < 0.01) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastPosition = villager.position();
        if (stuckTicks >= STUCK_TICKS) {
            approach = null;
            retryTicks = 0;
            stuckTicks = 0;
        }
        if (approach == null && retryTicks-- <= 0) {
            approach = WorkHelper.findReachableWorkPosition(level, villager, order.pos(), attempts++);
            retryTicks = APPROACH_RETRY_TICKS;
        }
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
