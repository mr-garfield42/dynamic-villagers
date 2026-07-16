package com.dynamicvillagers.villager.role;

import com.dynamicvillagers.village.StorageLedger;
import com.dynamicvillagers.village.VillageAnchor;
import com.dynamicvillagers.village.VillageManager;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

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
    private static final int HAUL_THRESHOLD = 16; // deposit once carrying this many non-kept items
    private static final List<String> KEEP_ON_DEPOSIT = List.of("pickaxe", "food", "item:minecraft:torch");
    private static final int MIN_FREE_SLOTS = 4;
    private static final int SCAN_RADIUS = 10;
    private static final int SCAN_HEIGHT = 6;
    private static final int MAX_SPOTS = 8;
    private static final int MAX_ORES_PER_CYCLE = 3;
    private static final int MAX_QUARRY_BLOCKS_PER_CYCLE = 6;
    private static final int FETCH_COOLDOWN = 1200;
    private static final int TORCH_FETCH_COUNT = 8;
    private static final int SITE_TORCH_RADIUS = 3;
    private static final double PICKUP_RADIUS = 5.0;

    @Override
    public boolean plan(ServerLevel level, Villager villager, VillagerEssence essence) {
        TaskQueue queue = essence.getTaskQueue();
        VillagerMemory memory = essence.getMemory();
        Predicate<ItemStack> keep = ItemFilter.parseAny(KEEP_ON_DEPOSIT);
        boolean haulReady = essence.countEmptySlots(villager) < MIN_FREE_SLOTS
                || essence.countItems(villager, stack -> !keep.test(stack)) >= HAUL_THRESHOLD;
        boolean knowsStorage = memory.nearestContainer(villager.blockPosition()) != null
                || StorageLedger.get(level).hasPublicDeposit(
                        VillageAnchor.resolve(level, villager), villager.getUUID());
        if (haulReady && knowsStorage) {
            if (!RequestChore.planCarriedDelivery(level, villager, essence, KEEP_ON_DEPOSIT)) {
                queue.enqueue(new DepositToContainerTask(KEEP_ON_DEPOSIT));
            }
            return true;
        }
        if (essence.countEmptySlots(villager) < MIN_FREE_SLOTS) {
            return false; // full and nowhere to store — stop mining until that changes
        }

        if (!essence.hasItem(villager, ItemFilter.parse("pickaxe"))) {
            if (WorkerTools.planWoodenTool(level, villager, essence,
                    Items.WOODEN_PICKAXE, "pickaxe")) {
                return true;
            }
            if (fetchWithCooldown(level, essence, "pickaxe", 1)) {
                return true; // mine next cycle, pickaxe in hand
            }
            return RequestChore.plan(level, villager, essence, KEEP_ON_DEPOSIT)
                    || TorchChore.plan(level, villager, essence);
        }

        if (essence.getMineSite() == null && essence.getQuarrySite() == null) {
            VillageManager.get(level).ensureStarterQuarry(level, villager);
        }

        if (!canHarvestIron(villager, essence)
                && WorkerTools.planCraftedItem(level, villager, essence, Items.STONE_PICKAXE, 1)) {
            return true;
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
                        ? TorchChore.findTorchSpotNear(level, ore, SITE_TORCH_RADIUS,
                                support -> level.getBlockState(support).is(Tags.Blocks.ORES)) : null;
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

        if (essence.getQuarrySite() != null) {
            Boolean quarry = planQuarryBatch(level, villager, essence, queue, hasTorches);
            if (quarry == null) {
                // the pit is dug out or fluid-blocked — a player would walk away and open a
                // new pit, not stand at the edge forever. Remember the dead spot so the next
                // self-claim picks a different one.
                memory.rememberSpot(VillageManager.REJECTED_QUARRY_SPOT,
                        essence.getQuarrySite().cornerA(), level.getGameTime());
                essence.setQuarrySite(null);
                if (VillageManager.get(level).ensureStarterQuarry(level, villager)) {
                    quarry = planQuarryBatch(level, villager, essence, queue, hasTorches);
                }
            }
            if (quarry != null) {
                if (!quarry) {
                    fearedDarkSite = true; // dark pit, no torches
                } else {
                    return true;
                }
            }
        }

        if (fearedDarkSite && !hasTorches
                && fetchWithCooldown(level, essence, TorchChore.TORCH_FILTER, TORCH_FETCH_COUNT)) {
            return true; // go get torches, then face the dark
        }
        return RequestChore.plan(level, villager, essence, KEEP_ON_DEPOSIT)
                || TorchChore.plan(level, villager, essence);
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
            // torches in the tunnel are ours — they are lighting, not rock to be cleared
            boolean lowerSolid = isDiggable(level.getBlockState(lower));
            boolean upperSolid = isDiggable(level.getBlockState(upper));
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
            if (workLight(level, standing) < TorchChore.MIN_SAFE_BLOCK_LIGHT) {
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

    /**
     * Enqueues the next batch of quarry work. Dig phase: topmost unfinished layer, skipping
     * the stair-line cells along the cornerA-side wall (one step down per column — vanilla
     * villagers cannot climb ladders, so the pit must always be exitable on foot). Build
     * phase: any stair cell that got lost along the way (obstruction clearing is allowed to
     * mine through it) is rebuilt with carried cobblestone, deepest step first, so a miner at
     * the pit bottom builds its own way out. Same contract as planTunnelSegment: true = work
     * enqueued, false = too dark without torches, null = nothing to do (done/fluid hazard).
     */
    private static Boolean planQuarryBatch(ServerLevel level, Villager villager,
                                           VillagerEssence essence, TaskQueue queue, boolean hasTorches) {
        VillagerEssence.QuarrySite site = essence.getQuarrySite();
        int minX = Math.min(site.cornerA().getX(), site.cornerB().getX());
        int maxX = Math.max(site.cornerA().getX(), site.cornerB().getX());
        int minZ = Math.min(site.cornerA().getZ(), site.cornerB().getZ());
        int maxZ = Math.max(site.cornerA().getZ(), site.cornerB().getZ());
        int topY = Math.max(site.cornerA().getY(), site.cornerB().getY());
        // one step down per stair column, so depth is capped by the stair wall length
        int bottomY = Math.max(Math.min(site.cornerA().getY(), site.cornerB().getY()),
                topY - (maxX - minX) - 1);
        int maxStep = Math.min(topY - bottomY, maxX - minX);

        // dig phase
        for (int y = topY; y >= bottomY; y--) {
            List<BlockPos> digs = new ArrayList<>();
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (isStairCell(x, y, z, minX, minZ, topY, maxStep)) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isDiggable(level.getBlockState(pos))) { // our torches are not rock
                        digs.add(pos);
                    }
                }
            }
            if (digs.isEmpty()) {
                continue; // layer finished — go one deeper
            }
            List<BlockPos> batch = digs.subList(0, Math.min(MAX_QUARRY_BLOCKS_PER_CYCLE, digs.size()));
            for (BlockPos pos : batch) {
                if (hasAdjacentFluid(level, pos)) {
                    return null; // fluid in the pit wall — stop before breaching it
                }
            }
            BlockPos standing = batch.getFirst().above();
            if (workLight(level, standing) < TorchChore.MIN_SAFE_BLOCK_LIGHT) {
                // a torch must not stand on a block THIS batch is about to dig ("placed then
                // broken a second later"); supports dug in later layers are fine — the torch
                // serves its layer and gets re-placed deeper, the way players re-torch
                BlockPos torchSpot = hasTorches
                        ? TorchChore.findTorchSpotNear(level, standing, SITE_TORCH_RADIUS, batch::contains)
                        : null;
                if (torchSpot == null) {
                    return false;
                }
                queue.enqueue(new PlaceBlockTask(torchSpot, TorchChore.TORCH_FILTER));
            }
            for (BlockPos pos : batch) {
                queue.enqueue(new BreakBlockTask(pos));
            }
            queue.enqueue(new PickUpItemsTask(batch.getFirst(), PICKUP_RADIUS));
            return true;
        }

        // build phase: repair the staircase, deepest step first
        for (int j = maxStep; j >= 0; j--) {
            BlockPos step = new BlockPos(minX + j, topY - j, minZ);
            BlockState state = level.getBlockState(step);
            if (state.isFaceSturdy(level, step, Direction.UP)) {
                continue; // step stands (original stone or rebuilt cobble)
            }
            if (!state.isAir() && !state.canBeReplaced()) {
                queue.enqueue(new BreakBlockTask(step)); // e.g. a torch squatting on the step
                return true;
            }
            if (!essence.hasItem(villager, ItemFilter.parse("item:minecraft:cobblestone"))) {
                // deposited the cobble already? get some back for the stairs
                return fetchWithCooldown(level, essence, "item:minecraft:cobblestone", 8) ? true : null;
            }
            queue.enqueue(new PlaceBlockTask(step, "item:minecraft:cobblestone"));
            return true;
        }
        return null; // quarry complete, staircase intact
    }

    private static boolean isStairCell(int x, int y, int z, int minX, int minZ, int topY, int maxStep) {
        int j = x - minX;
        return z == minZ && j >= 0 && j <= maxStep && topY - y == j;
    }

    /** Solid work material — not air, and not a torch we placed for our own light. */
    private static boolean isDiggable(BlockState state) {
        return !state.isAir() && !state.is(Blocks.TORCH) && !state.is(Blocks.WALL_TORCH);
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
                light = Math.max(light, workLight(level, neighbor));
            }
        }
        return anyAir && light < TorchChore.MIN_SAFE_BLOCK_LIGHT;
    }

    private static int workLight(ServerLevel level, BlockPos pos) {
        if (level.isDay() && level.canSeeSky(pos)) return 15;
        return level.getBrightness(LightLayer.BLOCK, pos);
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

    private static boolean canHarvestIron(Villager villager, VillagerEssence essence) {
        VillagerEssence.SlotRef tool = essence.findBestTool(villager, Blocks.IRON_ORE.defaultBlockState());
        return tool != null && tool.stack().isCorrectToolForDrops(Blocks.IRON_ORE.defaultBlockState());
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
