package com.dynamicvillagers.villager.work;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class WorkHelper {
    public static final double REACH = 4.0; // from the eyes, slightly under player reach

    public static final float WALK_SPEED = 0.6F;

    /** Looks at the target and walks toward it when out of reach. @return true when in reach. */
    public static boolean moveIntoReachAndLook(Villager villager, BlockPos pos) {
        villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(pos));
        // Drive the head directly too: our behaviors run after vanilla's look sink each brain
        // tick, so this wins over idle look-at-player targets and pins the gaze on the block.
        villager.getLookControl().setLookAt(Vec3.atCenterOf(pos));
        if (villager.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) {
            if (villager.tickCount % 20 == 0) {
                villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(pos, WALK_SPEED, 2));
            }
            return false;
        }
        return true;
    }

    /**
     * The first block standing between the villager's eyes and the target, or null when the
     * target itself is hit. Players cannot mine, place, or open things through other blocks
     * (design rule #2) — work paths use this to clear or reroute instead of cheating through.
     */
    @Nullable
    public static BlockPos findObstruction(ServerLevel level, Villager villager, BlockPos target) {
        BlockHitResult hit = level.clip(new ClipContext(
                villager.getEyePosition(), Vec3.atCenterOf(target),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, villager));
        if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(target)) {
            return hit.getBlockPos();
        }
        return null;
    }

    private WorkHelper() {
    }
}
