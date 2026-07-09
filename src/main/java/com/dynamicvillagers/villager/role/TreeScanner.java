package com.dynamicvillagers.villager.role;

import com.dynamicvillagers.villager.PerceptionSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds choppable trees the way a player would: look across the terrain (heightmap columns),
 * spot a trunk, size it up. A log cluster only counts as a tree if it has natural
 * (non-persistent) leaves — log piles and cabins are safe — and is small enough to fell from
 * the ground: height ≤ {@link #MAX_TRUNK_HEIGHT} above the base, spread ≤
 * {@link #MAX_HORIZONTAL_SPREAD}. Taller trees wait for Phase 4 scaffolding.
 */
public final class TreeScanner {
    public static final int SCAN_RADIUS = 16;
    public static final int MAX_TREE_LOGS = 24;
    public static final int MAX_TRUNK_HEIGHT = 5;
    public static final int MAX_HORIZONTAL_SPREAD = 3;
    // BFS bounds relative to the start log, generous enough to reach the whole cluster
    private static final int SEARCH_SPREAD = 4;
    private static final int SEARCH_HEIGHT = 8;
    // how far below the heightmap surface to look for a trunk: finds trees under overhangs
    // (and under the barrier cage GameTests are enclosed in); LOS still gates visibility
    private static final int DESCENT_DEPTH = 10;

    /** A validated tree: base = lowest log (replant spot), logs sorted bottom-up. */
    public record Tree(BlockPos base, List<BlockPos> logs) {
    }

    /** Surface-scans for visible trees around the villager, nearest first. */
    public static List<Tree> findTrees(ServerLevel level, Villager villager, int radius, int limit) {
        BlockPos origin = villager.blockPosition();
        Set<BlockPos> seenBases = new HashSet<>();
        List<Tree> found = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (surfaceY <= level.getMinBuildHeight()) {
                    continue;
                }
                BlockPos top = findTopLog(level, x, surfaceY, z);
                if (top == null) {
                    continue;
                }
                Tree tree = collectTree(level, top);
                if (tree == null || !seenBases.add(tree.base())) {
                    continue;
                }
                if (PerceptionSystem.canSee(level, villager, tree.base())
                        || PerceptionSystem.canSee(level, villager, tree.logs().getLast())) {
                    found.add(tree);
                }
            }
        }
        found.sort(Comparator.comparingDouble(tree -> tree.base().distSqr(origin)));
        return found.size() > limit ? found.subList(0, limit) : found;
    }

    /** Topmost log in the column at or below the heightmap surface, within DESCENT_DEPTH. */
    @Nullable
    private static BlockPos findTopLog(ServerLevel level, int x, int surfaceY, int z) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, surfaceY, z);
        int floor = Math.max(level.getMinBuildHeight() + 1, surfaceY - DESCENT_DEPTH);
        while (cursor.getY() >= floor) {
            if (level.getBlockState(cursor).is(BlockTags.LOGS)) {
                return cursor.immutable();
            }
            cursor.setY(cursor.getY() - 1);
        }
        return null;
    }

    /**
     * Collects the connected log cluster containing {@code start} and validates it as a
     * choppable tree. @return null if it is not a tree (no natural leaves) or not fellable
     * from the ground (too tall/wide/big).
     */
    @Nullable
    public static Tree collectTree(ServerLevel level, BlockPos start) {
        if (!level.getBlockState(start).is(BlockTags.LOGS)) {
            return null;
        }
        Set<BlockPos> logs = new HashSet<>();
        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        BlockPos startImmutable = start.immutable();
        logs.add(startImmutable);
        frontier.add(startImmutable);
        while (!frontier.isEmpty()) {
            BlockPos current = frontier.poll();
            for (BlockPos neighbor : BlockPos.betweenClosed(current.offset(-1, -1, -1), current.offset(1, 1, 1))) {
                if (logs.contains(neighbor)
                        || Math.abs(neighbor.getX() - start.getX()) > SEARCH_SPREAD
                        || Math.abs(neighbor.getZ() - start.getZ()) > SEARCH_SPREAD
                        || Math.abs(neighbor.getY() - start.getY()) > SEARCH_HEIGHT
                        || !level.getBlockState(neighbor).is(BlockTags.LOGS)) {
                    continue;
                }
                if (logs.size() >= MAX_TREE_LOGS) {
                    return null; // too big to be a normal tree — leave it alone
                }
                BlockPos immutable = neighbor.immutable();
                logs.add(immutable);
                frontier.add(immutable);
            }
        }

        BlockPos base = logs.stream()
                .min(Comparator.<BlockPos>comparingInt(BlockPos::getY)
                        .thenComparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getZ))
                .orElseThrow();
        int maxY = logs.stream().mapToInt(BlockPos::getY).max().orElseThrow();
        if (maxY - base.getY() > MAX_TRUNK_HEIGHT) {
            return null; // can't reach the top from the ground
        }
        for (BlockPos log : logs) {
            if (Math.abs(log.getX() - base.getX()) > MAX_HORIZONTAL_SPREAD
                    || Math.abs(log.getZ() - base.getZ()) > MAX_HORIZONTAL_SPREAD) {
                return null; // too spread out — probably a structure, not a tree
            }
        }
        if (logs.stream().noneMatch(log -> hasNaturalLeavesAdjacent(level, log))) {
            return null; // no living canopy: a log pile or a building, not a tree
        }

        List<BlockPos> sorted = logs.stream()
                .sorted(Comparator.<BlockPos>comparingInt(BlockPos::getY)
                        .thenComparingDouble(log -> log.distSqr(base)))
                .toList();
        return new Tree(base, sorted);
    }

    private static boolean hasNaturalLeavesAdjacent(ServerLevel level, BlockPos log) {
        for (BlockPos neighbor : new BlockPos[]{log.above(), log.below(), log.north(), log.south(), log.east(), log.west()}) {
            BlockState state = level.getBlockState(neighbor);
            if (state.is(BlockTags.LEAVES)
                    && state.hasProperty(BlockStateProperties.PERSISTENT)
                    && !state.getValue(BlockStateProperties.PERSISTENT)) {
                return true;
            }
        }
        return false;
    }

    private TreeScanner() {
    }
}
