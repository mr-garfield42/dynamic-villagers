package com.dynamicvillagers.villager.role;

import com.dynamicvillagers.villager.PerceptionSystem;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.VillagerMemory;
import com.dynamicvillagers.villager.task.BreakBlockTask;
import com.dynamicvillagers.villager.task.DepositToContainerTask;
import com.dynamicvillagers.villager.task.PickUpItemsTask;
import com.dynamicvillagers.villager.task.PlaceBlockTask;
import com.dynamicvillagers.villager.task.TakeItemsTask;
import com.dynamicvillagers.villager.task.TaskQueue;
import com.dynamicvillagers.villager.work.ItemFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Milestones 2.4 + 2.5. The miner harvests ore it can see and digs designated 1×2 strip-mine
 * tunnels — with a pickaxe good enough to actually get the drop (no wasting diamond ore on a
 * stone pick), never through a block with fluid behind it, and never in the dark: a site dim
 * enough for mobs to spawn (block light &lt; 8) is only worked if the miner carries torches
 * to light it first. A torchless miner is scared of dark sites and goes to fetch torches
 * instead. Tunnels therefore need a torch supply to go deep, which is exactly the cadence
 * players mine at.
 */
public class MinerPlanner implements RolePlanner {
    public static final String ORE_SPOT = "ore";
    public static final int MAX_TUNNEL_LENGTH = 64;
    private static final List<String> KEEP_ON_DEPOSIT = List.of("pickaxe", "food", "item:minecraft:torch");
    private static final int MIN_FREE_SLOTS = 4;
    private static final int SCAN_RADIUS = 10;
    private static final int SCAN_HEIGHT = 6;
    private static final int MAX_SPOTS = 8;
    private static final int MAX_ORES_PER_CYCLE = 3;
    private static final int FETCH_COOLDOWN = 1200;
    private static final int TORCH_FETCH_COUNT = 8;
    private static final int SITE_TORCH_RADIUS = 3;
    private static final double PICKUP_RADIUS = 5.0;

    @Override
    public boolean plan(ServerLevel level, Villager villager, VillagerEssence essence) {
        TaskQueue queue = essence.getTaskQueue();
        VillagerMemory memory = essence.getMemory();
        if (essence.countEmptySlots(villager) < MIN_FREE_SLOTS) {
            if (memory.nearestContainer(villager.blockPosition()) == null) {
                return false;
            }
            queue.enqueue(new DepositToContainerTask(KEEP_ON_DEPOSIT));
            return true;
        }

        if (!essence.hasItem(villager, ItemFilter.parse("pickaxe"))) {
            if (fetchWithCooldown(level, essence, "pickaxe", 1)) {
                return true; // mine next cycle, pickaxe in hand
            }
            return TorchChore.plan(level, villager, essence);
        }

        boolean hasTorches = essence.hasItem(villager, ItemFilter.parse(TorchChore.TORCH_FILTER));
        boolean fearedDarkSite = false;

        List<BlockPos> ores = findOres(level, villager, essence, memory);
        List<BlockPos> mined = new ArrayList<>();
        for (BlockPos ore : ores) {
            if (mined.size() >= MAX_ORES_PER_CYCLE) {
                break;
            }
            if (isDarkSite(level, ore)) {
                BlockPos torchSpot = hasTorches
                        ? TorchChore.findTorchSpotNear(level, ore, SITE_TORCH_RADIUS) : null;
                if (torchSpot == null) {
                    fearedDarkSite = true; // mobs spawn there and we can't light it — too risky
                    continue;
                }
                queue.enqueue(new PlaceBlockTask(torchSpot, TorchChore.TORCH_FILTER));
            }
            queue.enqueue(new BreakBlockTask(ore));
            memory.forgetSpot(ORE_SPOT, ore);
            mined.add(ore);
        }
        if (!mined.isEmpty()) {
            queue.enqueue(new PickUpItemsTask(mined.getFirst(), PICKUP_RADIUS));
            return true;
        }

        if (essence.getMineSite() != null) {
            Boolean tunnel = planTunnelSegment(level, essence, queue, hasTorches);
            if (tunnel != null) {
                if (!tunnel) {
                    fearedDarkSite = true; // dark tunnel face, no torches
                } else {
                    return true;
                }
            }
        }

        if (fearedDarkSite && !hasTorches
                && fetchWithCooldown(level, essence, TorchChore.TORCH_FILTER, TORCH_FETCH_COUNT)) {
            return true; // go get torches, then face the dark
        }
        return TorchChore.plan(level, villager, essence);
    }

    /**
     * Enqueues the next 1×2 tunnel segment. @return true = work enqueued; false = blocked by
     * darkness (caller may fetch torches); null = no segment to dig (done, or fluid hazard).
     */
    private static Boolean planTunnelSegment(ServerLevel level, VillagerEssence essence,
                                             TaskQueue queue, boolean hasTorches) {
        VillagerEssence.MineSite site = essence.getMineSite();
        for (int i = 0; i < MAX_TUNNEL_LENGTH; i++) {
            BlockPos lower = site.start().relative(site.direction(), i);
            BlockPos upper = lower.above();
            boolean lowerSolid = !level.getBlockState(lower).isAir();
            boolean upperSolid = !level.getBlockState(upper).isAir();
            if (!lowerSolid && !upperSolid) {
                continue; // already dug — walk deeper
            }
            // never break a block with fluid behind/around it: seal stays sealed
            if (hasAdjacentFluid(level, upper) || hasAdjacentFluid(level, lower)) {
                return null;
            }
            // the face is where the miner will stand — dark tunnels need light first
            BlockPos standing = i == 0 ? site.start().relative(site.direction().getOpposite())
                    : site.start().relative(site.direction(), i - 1);
            if (level.getBrightness(LightLayer.BLOCK, standing) < TorchChore.MIN_SAFE_BLOCK_LIGHT) {
                BlockPos torchSpot = hasTorches
                        ? TorchChore.findTorchSpotNear(level, standing, SITE_TORCH_RADIUS) : null;
                if (torchSpot == null) {
                    return false;
                }
                queue.enqueue(new PlaceBlockTask(torchSpot, TorchChore.TORCH_FILTER));
            }
            if (upperSolid) {
                queue.enqueue(new BreakBlockTask(upper)); // top first, like a player at the face
            }
            if (lowerSolid) {
                queue.enqueue(new BreakBlockTask(lower));
            }
            queue.enqueue(new PickUpItemsTask(lower, 3.0));
            return true;
        }
        return null; // tunnel complete
    }

    private static boolean hasAdjacentFluid(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (!level.getFluidState(pos.relative(direction)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Mobs could spawn next to this ore: some adjacent air below the safe light threshold. */
    private static boolean isDarkSite(ServerLevel level, BlockPos ore) {
        boolean anyAir = false;
        int light = 0;
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = ore.relative(direction);
            if (level.getBlockState(neighbor).isAir()) {
                anyAir = true;
                light = Math.max(light, level.getBrightness(LightLayer.BLOCK, neighbor));
            }
        }
        return anyAir && light < TorchChore.MIN_SAFE_BLOCK_LIGHT;
    }

    private static boolean fetchWithCooldown(ServerLevel level, VillagerEssence essence,
                                             String filter, int count) {
        long now = level.getGameTime();
        if (essence.getMemory().knownContainers().isEmpty()
                || now < essence.getNextToolFetchTime()) {
            return false;
        }
        essence.setNextToolFetchTime(now + FETCH_COOLDOWN);
        essence.getTaskQueue().enqueue(new TakeItemsTask(filter, count));
        return true;
    }

    private List<BlockPos> findOres(ServerLevel level, Villager villager,
                                    VillagerEssence essence, VillagerMemory memory) {
        List<BlockPos> valid = new ArrayList<>();
        for (BlockPos remembered : memory.spotsByDistance(ORE_SPOT, villager.blockPosition())) {
            if (isHarvestableOre(level, villager, essence, remembered)) {
                valid.add(remembered);
            } else {
                memory.forgetSpot(ORE_SPOT, remembered);
            }
        }
        if (!valid.isEmpty()) {
            return valid;
        }

        BlockPos origin = villager.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-SCAN_RADIUS, -SCAN_HEIGHT, -SCAN_RADIUS),
                origin.offset(SCAN_RADIUS, SCAN_HEIGHT, SCAN_RADIUS))) {
            if (level.getBlockState(pos).is(Tags.Blocks.ORES) && isExposed(level, pos)) {
                candidates.add(pos.immutable());
            }
        }
        candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
        long now = level.getGameTime();
        for (BlockPos pos : candidates) {
            if (valid.size() >= MAX_SPOTS) {
                break;
            }
            if (isHarvestableOre(level, villager, essence, pos)
                    && PerceptionSystem.canSee(level, villager, pos)) {
                memory.rememberSpot(ORE_SPOT, pos, now);
                valid.add(pos);
            }
        }
        return valid;
    }

    /** Still an ore, and the carried pickaxe is good enough that the drop isn't wasted. */
    private static boolean isHarvestableOre(ServerLevel level, Villager villager,
                                            VillagerEssence essence, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(Tags.Blocks.ORES)) {
            return false;
        }
        VillagerEssence.SlotRef tool = essence.findBestTool(villager, state);
        return tool != null && tool.stack().isCorrectToolForDrops(state);
    }

    private static boolean isExposed(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (level.getBlockState(pos.relative(direction)).isAir()) {
                return true;
            }
        }
        return false;
    }
}
