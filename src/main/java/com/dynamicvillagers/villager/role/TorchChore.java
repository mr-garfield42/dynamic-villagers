package com.dynamicvillagers.villager.role;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.task.PlaceBlockTask;
import com.dynamicvillagers.villager.task.TakeItemsTask;
import com.dynamicvillagers.villager.work.ItemFilter;
import com.dynamicvillagers.villager.work.WorkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Standing chore for any roled villager with nothing better to do: light up dark spots
 * nearby so hostile mobs can't spawn around the settlement (owner request, 2026-07-09).
 * A spot qualifies when it is air over sturdy ground, block light below 8 (mobs need 0 to
 * spawn, but players light to 8+ for margin — and it keeps torch coverage looking deliberate),
 * and the villager can actually see it from where it stands (no torching caves through
 * walls). Torches come from the carried inventory, fetched from remembered containers.
 */
public final class TorchChore {
    public static final String TORCH_FILTER = "item:minecraft:torch";
    public static final int SCAN_RADIUS = 10;
    public static final int MIN_SAFE_BLOCK_LIGHT = 8;
    private static final int DESCENT_DEPTH = 10;
    private static final int MAX_PLACEMENTS_PER_CYCLE = 3;
    private static final int MAX_SPOTS = 8;
    private static final int FETCH_COOLDOWN = 1200;
    private static final int FETCH_COUNT = 8;

    /** @return true if tasks were enqueued. Call from planners when there is no primary work. */
    public static boolean plan(ServerLevel level, Villager villager, VillagerEssence essence) {
        List<BlockPos> spots = findDarkSpots(level, villager);
        if (spots.isEmpty()) {
            return false;
        }
        if (!essence.hasItem(villager, ItemFilter.parse(TORCH_FILTER))) {
            long now = level.getGameTime();
            if (essence.getMemory().knownContainers().isEmpty()
                    || now < essence.getNextTorchFetchTime()) {
                return false; // no torches and no way to get any right now
            }
            essence.setNextTorchFetchTime(now + FETCH_COOLDOWN);
            essence.getTaskQueue().enqueue(new TakeItemsTask(TORCH_FILTER, FETCH_COUNT));
        }
        for (BlockPos spot : spots.subList(0, Math.min(MAX_PLACEMENTS_PER_CYCLE, spots.size()))) {
            essence.getTaskQueue().enqueue(new PlaceBlockTask(spot, TORCH_FILTER));
        }
        return true;
    }

    /** Nearest spot within radius of center where a torch can stand, in the dark, or null. */
    @Nullable
    public static BlockPos findTorchSpotNear(ServerLevel level, BlockPos center, int radius) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            BlockPos below = pos.below();
            if (level.getBlockState(pos).isAir()
                    && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                    && level.getBrightness(LightLayer.BLOCK, pos) < MIN_SAFE_BLOCK_LIGHT) {
                double dist = pos.distSqr(center);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = pos.immutable();
                }
            }
        }
        return best;
    }

    private static List<BlockPos> findDarkSpots(ServerLevel level, Villager villager) {
        List<BlockPos> spots = new ArrayList<>();
        BlockPos origin = villager.blockPosition();
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS && spots.size() < MAX_SPOTS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS && spots.size() < MAX_SPOTS; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                // walk down from the surface so canopies and overhangs don't hide the ground;
                // visibility (checked per candidate) keeps this from x-raying into caves
                int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                for (int y = surfaceY; y > surfaceY - DESCENT_DEPTH; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockPos below = pos.below();
                    if (level.getBlockState(pos).isAir()
                            && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                            && level.getBrightness(LightLayer.BLOCK, pos) < MIN_SAFE_BLOCK_LIGHT
                            && WorkHelper.findObstruction(level, villager, pos) == null) {
                        spots.add(pos);
                        break; // one spot per column is plenty — a torch lights a wide area
                    }
                }
            }
        }
        spots.sort(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
        return spots;
    }

    private TorchChore() {
    }
}
