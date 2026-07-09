package com.dynamicvillagers.villager.work;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;

public final class WorkHelper {
    public static final double REACH = 4.0; // from the eyes, slightly under player reach
    public static final float WALK_SPEED = 0.6F;

    /** Looks at the target and walks toward it when out of reach. @return true when in reach. */
    public static boolean moveIntoReachAndLook(Villager villager, BlockPos pos) {
        villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(pos));
        if (villager.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) {
            if (villager.tickCount % 20 == 0) {
                villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(pos, WALK_SPEED, 2));
            }
            return false;
        }
        return true;
    }

    private WorkHelper() {
    }
}
