package com.dynamicvillagers.villager.role;

import com.dynamicvillagers.village.VillageManager;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.task.BreakBlockTask;
import com.dynamicvillagers.villager.task.PlaceBlockTask;
import com.dynamicvillagers.villager.task.TaskQueue;
import com.dynamicvillagers.villager.work.ItemFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Self-rescue for a miner standing in its own pit with no way to walk out (a quarry whose
 * staircase was lost, a flooded-and-abandoned dig, ...): carve a rising 1×2 stair into the
 * lowest pit wall — pure digging, the way a trapped player escapes, so no materials are
 * required; a carried scaffold block (dirt/cobblestone) bridges a missing step if one turns
 * up. Detection is a real walkability flood fill over blocks (step up 1, drop up to 3), so
 * it works under roofs and never mistakes a tunnel mouth for a wall. The own-pit gate keeps
 * a miner from ever carving through village walls or anything it did not dig itself.
 */
final class EscapeChore {
    private static final int SCAN_RADIUS = 8;      // reaching this far on foot counts as free
    private static final int MAX_VISITS = 400;     // a region this large is open ground, not a pit
    private static final int MAX_CARVE = 24;       // longest stair flight worth planning
    private static final int STEPS_PER_CYCLE = 2;  // carve progressively, replanning as we climb
    private static final int MAX_WALL = 10;        // wall-height probe cap when picking a direction

    static boolean plan(ServerLevel level, Villager villager, VillagerEssence essence) {
        BlockPos feet = villager.blockPosition();
        if (!inOwnPit(essence, feet)) {
            return false; // only rescue from our own diggings — never carve through a village
        }
        if (!trapped(level, feet)) {
            return false;
        }
        Direction direction = lowestWall(level, feet);
        return direction != null && carve(level, villager, essence, feet, direction);
    }

    /** Inside the current quarry's footprint, or a just-abandoned pit remembered as rejected. */
    private static boolean inOwnPit(VillagerEssence essence, BlockPos feet) {
        VillagerEssence.QuarrySite site = essence.getQuarrySite();
        if (site != null
                && feet.getX() >= Math.min(site.cornerA().getX(), site.cornerB().getX())
                && feet.getX() <= Math.max(site.cornerA().getX(), site.cornerB().getX())
                && feet.getZ() >= Math.min(site.cornerA().getZ(), site.cornerB().getZ())
                && feet.getZ() <= Math.max(site.cornerA().getZ(), site.cornerB().getZ())
                && feet.getY() <= Math.max(site.cornerA().getY(), site.cornerB().getY())) {
            return true;
        }
        for (BlockPos top : essence.getMemory().knownSpots(VillageManager.REJECTED_QUARRY_SPOT)) {
            if (feet.getY() < top.getY()
                    && Math.abs(feet.getX() - top.getX()) <= 4
                    && Math.abs(feet.getZ() - top.getZ()) <= 4) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walkability flood fill from the miner's feet (step up at most 1, drop at most 3): if
     * no standing spot {@link #SCAN_RADIUS} columns away is reachable and the region stays
     * small, the miner is fenced in on every side.
     */
    private static boolean trapped(ServerLevel level, BlockPos feet) {
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> open = new ArrayDeque<>();
        open.add(feet);
        visited.add(feet);
        while (!open.isEmpty()) {
            BlockPos current = open.poll();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = stepTarget(level, current.relative(direction));
                if (next == null || !visited.add(next)) {
                    continue;
                }
                if (Math.max(Math.abs(next.getX() - feet.getX()),
                        Math.abs(next.getZ() - feet.getZ())) >= SCAN_RADIUS
                        || visited.size() > MAX_VISITS) {
                    return false; // open ground reached — not trapped
                }
                open.add(next);
            }
        }
        return true;
    }

    /** Where feet land when walking one cell into {@code ahead}: flat, one step up, or a short drop. */
    private static BlockPos stepTarget(ServerLevel level, BlockPos ahead) {
        if (standable(level, ahead)) {
            return ahead;
        }
        if (solid(level, ahead) && standable(level, ahead.above())) {
            return ahead.above();
        }
        if (passable(level.getBlockState(ahead)) && passable(level.getBlockState(ahead.above()))) {
            for (int drop = 1; drop <= 3; drop++) {
                BlockPos landing = ahead.below(drop);
                if (standable(level, landing)) {
                    return landing;
                }
                if (!passable(level.getBlockState(landing))) {
                    break;
                }
            }
        }
        return null;
    }

    /** The direction whose first enclosing wall is lowest — the cheapest carve out. */
    private static Direction lowestWall(ServerLevel level, BlockPos feet) {
        Direction best = null;
        int bestHeight = Integer.MAX_VALUE;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int i = 1; i <= MAX_CARVE; i++) {
                BlockPos flat = feet.relative(direction, i);
                if (standable(level, flat)) {
                    continue; // open pit floor — keep looking for the wall
                }
                int height = 0;
                while (height < MAX_WALL
                        && !(passable(level.getBlockState(flat.above(height)))
                                && passable(level.getBlockState(flat.above(height + 1))))) {
                    height++;
                }
                if (height > 0 && height < bestHeight) {
                    bestHeight = height;
                    best = direction;
                }
                break;
            }
        }
        return best;
    }

    /**
     * Plans the next stretch of a rising 1×2 stair toward {@code direction}: walk open floor
     * for free, then for each wall column keep its block at foot level as the step (placing
     * a scaffold block if the step is missing) and clear the two blocks above it.
     */
    private static boolean carve(ServerLevel level, Villager villager, VillagerEssence essence,
                                 BlockPos feet, Direction direction) {
        TaskQueue queue = essence.getTaskQueue();
        int walkFeet = feet.getY();
        int steps = 0;
        for (int i = 1; i <= MAX_CARVE && steps < STEPS_PER_CYCLE; i++) {
            BlockPos column = feet.relative(direction, i);
            BlockPos flat = new BlockPos(column.getX(), walkFeet, column.getZ());
            if (standable(level, flat)) {
                continue; // open floor — just walk it
            }
            BlockPos stepBlock = flat;          // the solid we climb onto
            BlockPos lower = flat.above();      // the new feet position
            BlockPos upper = lower.above();     // headroom
            if (MinerPlanner.hasAdjacentFluid(level, lower) || MinerPlanner.hasAdjacentFluid(level, upper)) {
                break; // never carve into water or lava
            }
            if (level.getBlockState(lower).getDestroySpeed(level, lower) < 0
                    || level.getBlockState(upper).getDestroySpeed(level, upper) < 0) {
                break; // bedrock wall — this direction is hopeless
            }
            boolean work = false;
            if (!solid(level, stepBlock)) {
                if (!essence.hasItem(villager, ItemFilter.parse(BuilderPlanner.SCAFFOLD_FILTER))) {
                    break; // a missing step and nothing to bridge it with
                }
                queue.enqueue(new PlaceBlockTask(stepBlock, BuilderPlanner.SCAFFOLD_FILTER));
                work = true;
            }
            if (MinerPlanner.isDiggable(level.getBlockState(upper))) {
                queue.enqueue(new BreakBlockTask(upper)); // top first, like a player at a face
                work = true;
            }
            if (MinerPlanner.isDiggable(level.getBlockState(lower))) {
                queue.enqueue(new BreakBlockTask(lower));
                work = true;
            }
            if (work) {
                steps++;
            }
            walkFeet++;
        }
        return steps > 0;
    }

    private static boolean standable(ServerLevel level, BlockPos feet) {
        return passable(level.getBlockState(feet))
                && passable(level.getBlockState(feet.above()))
                && solid(level, feet.below());
    }

    private static boolean solid(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP);
    }

    /** Air, or one of our own torches — nothing a villager cannot walk through. */
    private static boolean passable(BlockState state) {
        return state.isAir() || state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH);
    }

    private EscapeChore() {
    }
}
