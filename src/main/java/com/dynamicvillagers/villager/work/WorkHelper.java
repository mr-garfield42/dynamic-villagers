package com.dynamicvillagers.villager.work;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class WorkHelper {
    public static final double REACH = 4.0; // from the eyes, slightly under player reach

    // Workers move to their tasks with purpose (a touch above vanilla panic speed). Walking
    // into reach is the dominant cost of every block operation, so this compounds across a
    // whole build; kept below a sprint so it still reads as a villager, not a blur.
    public static final float WALK_SPEED = 0.7F;

    /** Looks at the target and walks toward it when out of reach. @return true when in reach. */
    public static boolean moveIntoReachAndLook(Villager villager, BlockPos pos) {
        return moveIntoReachAndLook(villager, pos, pos, 2);
    }

    public static boolean moveIntoReachAndLook(Villager villager, BlockPos pos,
                                               BlockPos walkTo, int closeEnough) {
        villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(pos));
        // Drive the head directly too: our behaviors run after vanilla's look sink each brain
        // tick, so this wins over idle look-at-player targets and pins the gaze on the block.
        villager.getLookControl().setLookAt(Vec3.atCenterOf(pos));
        if (villager.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) {
            // every tick, not periodically: the moment the walk target clears (reached or
            // path failed), an idle stroll would hijack it and the worker wanders off mid-job
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(walkTo, WALK_SPEED, closeEnough));
            return false;
        }
        return true;
    }

    @Nullable
    public static BlockPos findReachableWorkPosition(ServerLevel level, Villager villager,
                                                     BlockPos target, int attempt) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int radius = 1; radius <= 3; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int dy = -2; dy <= 1; dy++) {
                        BlockPos pos = target.offset(dx, dy, dz);
                        Vec3 eyes = Vec3.atBottomCenterOf(pos).add(0.0, villager.getEyeHeight(), 0.0);
                        if (eyes.distanceToSqr(Vec3.atCenterOf(target)) > REACH * REACH
                                || !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                                || !level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                                || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP)
                                || !level.getEntitiesOfClass(Villager.class, new AABB(pos), other -> other != villager).isEmpty()) {
                            continue;
                        }
                        candidates.add(pos.immutable());
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(villager.blockPosition())));
        for (int i = 0; i < candidates.size(); i++) {
            BlockPos candidate = candidates.get(Math.floorMod(i + attempt, candidates.size()));
            Path path = villager.getNavigation().createPath(candidate, 0);
            if (path != null && path.canReach()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Holds the villager pressed against a solid work block so a long job (mining a hard
     * block) isn't interrupted by wandering. Vanilla RandomStroll only fires when WALK_TARGET
     * is absent; MoveToTargetSink erases it the moment it is "reached". A walk target on the
     * solid block itself with close-enough 0 is never reached (the villager can't stand on the
     * block), so it stays present — suppressing stroll — while the villager stays adjacent.
     * Only valid for a solid target; placement (an air cell) must not use this.
     */
    public static void holdAtSolid(Villager villager, BlockPos solid) {
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(solid, WALK_SPEED, 0));
    }

    /**
     * Walks a couple of blocks perpendicular to the sight line — the polite alternative to
     * mining your own fresh wall when it blocks the view of the next target. Alternates
     * sides every second (via {@code seed}) so a dead end on one side doesn't trap the loop.
     */
    public static void sidestep(Villager villager, BlockPos target, int seed) {
        Vec3 toTarget = Vec3.atCenterOf(target).subtract(villager.position());
        Vec3 side = new Vec3(-toTarget.z, 0.0, toTarget.x);
        if (side.lengthSqr() < 1.0E-4) {
            side = new Vec3(1.0, 0.0, 0.0);
        }
        side = side.normalize().scale(2.0);
        if ((seed / 20) % 2 == 1) {
            side = side.reverse();
        }
        BlockPos spot = BlockPos.containing(villager.position().add(side));
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(spot, WALK_SPEED, 0));
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
