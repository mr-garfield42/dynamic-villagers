package com.dynamicvillagers.villager.role;

import com.dynamicvillagers.village.ConstructionLedger;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.task.BreakBlockTask;
import com.dynamicvillagers.villager.task.PlaceBlockTask;
import com.dynamicvillagers.villager.task.ShovelPathTask;
import com.dynamicvillagers.villager.task.TakeItemsTask;
import com.dynamicvillagers.villager.task.TaskQueue;
import com.dynamicvillagers.villager.work.ItemFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Milestone 4.6: builds a designated path. Unlike a building there is no blueprint — the work
 * is derived from the waypoint polyline and the terrain it crosses. For each surface cell
 * along the line the builder clears the two blocks of head-room above it (so the path is
 * walkable), fills a 1-deep gap with dirt, and flattens the dirt-family surface into a dirt
 * path with a carried shovel. Non-dirt solid ground (stone, sand) is left as-is — already
 * walkable; gravel/plank surfacing is a later extension. A path over grass/dirt, the common
 * village case, comes out as a proper connected dirt path following gentle slopes.
 */
public final class PathChore {
    public static final String DIRT_FILTER = "item:minecraft:dirt";
    private static final int WORK_BATCH = 16;
    private static final int SURFACE_SEARCH = 3; // vertical tolerance when snapping to terrain
    private static final int FETCH_COOLDOWN = 600;
    private static final int DIRT_FETCH = 32;

    public static boolean plan(ServerLevel level, Villager villager, VillagerEssence essence,
                               ConstructionLedger ledger) {
        ConstructionLedger.PathSite path = ledger.getPath(essence.getAssignedPathId());
        if (path == null || path.status() != ConstructionLedger.Status.OPEN) {
            essence.setAssignedPathId(-1); // finished or cancelled — the order is spent
            return false;
        }
        long now = level.getGameTime();

        if (!essence.hasItem(villager, ItemFilter.parse("shovel"))) {
            return fetch(level, essence, "shovel", 1, now); // no shovel, no path — go get one
        }

        List<BlockPos> cells = surfaceCells(level, path);
        TaskQueue queue = essence.getTaskQueue();
        int queued = 0;
        boolean needDirt = false;
        for (BlockPos cell : cells) {
            if (queued >= WORK_BATCH) {
                break;
            }
            queued += planClearance(level, cell, queue);
            if (queued >= WORK_BATCH) {
                break;
            }
            BlockState surface = level.getBlockState(cell);
            if (surface.is(Blocks.DIRT_PATH)) {
                continue; // already a path
            }
            if (surface.is(BlockTags.DIRT)) {
                queue.enqueue(new ShovelPathTask(cell));
                queued++;
            } else if (surface.isAir() || surface.canBeReplaced()) {
                if (essence.hasItem(villager, ItemFilter.parse(DIRT_FILTER))) {
                    queue.enqueue(new PlaceBlockTask(cell, DIRT_FILTER)); // fill; flatten next cycle
                    queued++;
                } else {
                    needDirt = true;
                }
            }
            // any other solid ground (stone, sand, ...) is left walkable as-is
        }

        if (queued > 0) {
            return true;
        }
        if (needDirt) {
            return fetch(level, essence, DIRT_FILTER, DIRT_FETCH, now);
        }
        ledger.setPathStatus(path, ConstructionLedger.Status.DONE);
        essence.setAssignedPathId(-1);
        return false;
    }

    /** Break the two head-room blocks above the path surface if something obstructs walking. */
    private static int planClearance(ServerLevel level, BlockPos cell, TaskQueue queue) {
        int queued = 0;
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos above = cell.above(dy);
            BlockState state = level.getBlockState(above);
            if (!state.getCollisionShape(level, above).isEmpty()
                    && state.getDestroySpeed(level, above) >= 0) {
                queue.enqueue(new BreakBlockTask(above));
                queued++;
            }
        }
        return queued;
    }

    /**
     * The walkable surface cell for each step along the polyline, snapped to the terrain top
     * within a small vertical tolerance of the interpolated height. Deduplicated, in order.
     */
    private static List<BlockPos> surfaceCells(ServerLevel level, ConstructionLedger.PathSite path) {
        Set<BlockPos> out = new LinkedHashSet<>();
        List<BlockPos> waypoints = path.waypoints();
        for (int i = 0; i + 1 < waypoints.size(); i++) {
            BlockPos a = waypoints.get(i);
            BlockPos b = waypoints.get(i + 1);
            int dx = b.getX() - a.getX();
            int dz = b.getZ() - a.getZ();
            int steps = Math.max(Math.abs(dx), Math.abs(dz));
            if (steps == 0) {
                out.add(surface(level, a.getX(), a.getZ(), a.getY()));
                continue;
            }
            for (int s = 0; s <= steps; s++) {
                double t = (double) s / steps;
                int x = a.getX() + (int) Math.round(dx * t);
                int z = a.getZ() + (int) Math.round(dz * t);
                int yGuess = a.getY() + (int) Math.round((b.getY() - a.getY()) * t);
                out.add(surface(level, x, z, yGuess));
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * The path-surface cell at this column. Searches from the top of the tolerance window
     * DOWN to the interpolated grade only — so terrain that rose above the grade is followed
     * up, but a pothole at the grade is filled (returned at grade) rather than dropped into,
     * which would leave the path dipping through the hole instead of bridging it.
     */
    private static BlockPos surface(ServerLevel level, int x, int z, int yGuess) {
        for (int y = yGuess + SURFACE_SEARCH; y > yGuess; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                    && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()) {
                return pos; // terrain rose above the grade — the path climbs to meet it
            }
        }
        return new BlockPos(x, yGuess, z); // at grade: convert if solid, fill if a gap
    }

    private static boolean fetch(ServerLevel level, VillagerEssence essence, String filter, int count, long now) {
        if (essence.getMemory().knownContainers().isEmpty() || now < essence.getNextToolFetchTime()) {
            return false;
        }
        essence.setNextToolFetchTime(now + FETCH_COOLDOWN);
        essence.getTaskQueue().enqueue(new TakeItemsTask(filter, count));
        return true;
    }

    private PathChore() {
    }
}
