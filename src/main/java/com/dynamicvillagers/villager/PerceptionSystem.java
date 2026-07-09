package com.dynamicvillagers.villager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.List;
import java.util.Map;

/**
 * Periodically lets villagers learn about the world around them. A container is remembered
 * only if the villager is close AND has line of sight to it — no wallhacks. Remembered
 * containers that turn out to be gone are forgotten on the next pass by them.
 */
public final class PerceptionSystem {
    public static final int SCAN_INTERVAL_TICKS = 100;
    public static final int SCAN_RADIUS = 8;

    public static void onEntityTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof Villager villager
                && villager.level() instanceof ServerLevel level
                && villager.isAlive()
                && (villager.tickCount + villager.getId()) % SCAN_INTERVAL_TICKS == 0) {
            scan(level, villager);
        }
    }

    public static void scan(ServerLevel level, Villager villager) {
        VillagerMemory memory = VillagerEssence.get(villager).getMemory();
        BlockPos center = villager.blockPosition();
        long now = level.getGameTime();

        int minCx = (center.getX() - SCAN_RADIUS) >> 4;
        int maxCx = (center.getX() + SCAN_RADIUS) >> 4;
        int minCz = (center.getZ() - SCAN_RADIUS) >> 4;
        int maxCz = (center.getZ() + SCAN_RADIUS) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos pos = entry.getKey();
                    if (entry.getValue() instanceof Container
                            && pos.closerThan(center, SCAN_RADIUS)
                            && canSee(level, villager, pos)) {
                        memory.rememberContainer(pos, now);
                    }
                }
            }
        }

        for (BlockPos pos : List.copyOf(memory.knownContainers())) {
            if (pos.closerThan(center, SCAN_RADIUS)
                    && level.isLoaded(pos)
                    && !(level.getBlockEntity(pos) instanceof Container)) {
                memory.forgetContainer(pos);
            }
        }
    }

    /** Line-of-sight from the villager's eyes to a block, ignoring transparent visuals. */
    public static boolean canSee(ServerLevel level, Villager villager, BlockPos pos) {
        Vec3 eye = villager.getEyePosition();
        Vec3 target = Vec3.atCenterOf(pos);
        BlockHitResult hit = level.clip(
                new ClipContext(eye, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, villager));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    private PerceptionSystem() {
    }
}
