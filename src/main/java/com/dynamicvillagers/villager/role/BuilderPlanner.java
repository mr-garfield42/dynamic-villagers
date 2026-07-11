package com.dynamicvillagers.villager.role;

import com.dynamicvillagers.construction.BlockMatch;
import com.dynamicvillagers.construction.BlockRequirements;
import com.dynamicvillagers.construction.Blueprint;
import com.dynamicvillagers.construction.Blueprints;
import com.dynamicvillagers.construction.SiteValidator;
import com.dynamicvillagers.registry.DVTags;
import com.dynamicvillagers.village.ConstructionLedger;
import com.dynamicvillagers.village.StorageLedger;
import com.dynamicvillagers.village.VillageAnchor;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.task.BreakBlockTask;
import com.dynamicvillagers.villager.task.DepositToContainerTask;
import com.dynamicvillagers.villager.task.PickUpItemsTask;
import com.dynamicvillagers.villager.task.PlaceBlockTask;
import com.dynamicvillagers.villager.task.PlaceStateTask;
import com.dynamicvillagers.villager.task.TakeItemsTask;
import com.dynamicvillagers.villager.task.TaskQueue;
import com.dynamicvillagers.villager.work.ItemFilter;
import com.dynamicvillagers.villager.work.WorkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Milestone 4.1: the Builder realizes construction sites. Each cycle it diffs the site's
 * blueprint against the actual world (no stored per-block progress — the mismatches ARE the
 * remaining work, which makes reloads, interruptions, and later repair all the same case),
 * claims a bounded batch of them, and either builds with carried materials or goes to fetch
 * what the batch needs from the storage network. Entries whose support is missing or that
 * another builder has claimed are skipped and picked up by a later pass.
 */
public class BuilderPlanner implements RolePlanner {
    // A large batch is the main throughput lever: each plan cycle ends in a replan gap the
    // villager spends idling/wandering, so more work queued per cycle means far fewer gaps
    // and much less walking back to the site. Sorted bottom-up, a big batch is placed from a
    // few standing spots with little movement between blocks.
    public static final int WORK_BATCH = 40;
    public static final String SCAFFOLD_FILTER = "scaffold"; // dirt or cobblestone
    private static final String CHEST_FILTER = "item:minecraft:chest";
    private static final int MIN_FREE_SLOTS = 4;
    private static final int FETCH_COOLDOWN = 600; // between restock attempts while short
    private static final int FETCH_CAP = 64; // one errand's worth of one material
    private static final double PICKUP_RADIUS = 5.0;
    private static final int REACH_SCAN = 3; // horizontal radius searched for a standing spot
    private static final int MAX_WALKABLE_CELLS = 1024; // flood-fill bound for the reachability oracle
    private static final int MAX_SCAFFOLD_HEIGHT = 8;
    private static final double EYE_HEIGHT = 1.62;
    private static final List<String> KEEP_BASE = List.of("food", "pickaxe"); // tools are never deposited

    private static final List<String> PATH_KEEP = List.of("food", "shovel", PathChore.DIRT_FILTER);

    @Override
    public boolean plan(ServerLevel level, Villager villager, VillagerEssence essence) {
        ConstructionLedger ledger = ConstructionLedger.get(level);

        // a builder is assigned either a path or a building; paths take the branch first
        if (essence.getAssignedPathId() != -1) {
            if (planDepositWhenStuffed(level, villager, essence, PATH_KEEP)) {
                return true;
            }
            return PathChore.plan(level, villager, essence, ledger);
        }

        ConstructionLedger.ConstructionSite site = activeSite(essence, ledger);
        Blueprint blueprint = site != null ? Blueprints.load(level, site.templateId()) : null;

        List<String> keepFilters = keepFilters(blueprint);
        if (planDepositWhenStuffed(level, villager, essence, keepFilters)) {
            return true;
        }
        if (site == null || blueprint == null) {
            return RequestChore.plan(level, villager, essence, keepFilters)
                    || TorchChore.plan(level, villager, essence);
        }

        // 4.7 repair: a builder keeps watching the site it finished. A budgeted integrity
        // scan flips a damaged building back to OPEN so the normal diff rebuilds it — repair
        // is the same machinery as construction, not a separate system. An intact building
        // is idle-watched (no chores; the assignment is a standing post until reassigned).
        if (site.status() == ConstructionLedger.Status.DONE) {
            if (hasDamage(level, site, blueprint)) {
                ledger.setStatus(site, ConstructionLedger.Status.OPEN);
            } else {
                return false;
            }
        }

        long now = level.getGameTime();
        UUID self = villager.getUUID();

        // 4.2: the ground comes first — pillar hanging footprint columns down to sturdy
        // ground with fill material before (and while) the walls rise
        List<BlockPos> foundation = foundationWork(level, site, blueprint, self, now);
        if (!foundation.isEmpty() && planFoundation(villager, essence, ledger, site, foundation, now)) {
            return true;
        }

        Set<BlockPos> walkable = walkableRegion(level, villager);
        List<Blueprint.PlannedBlock> batch = new ArrayList<>();
        boolean scannedAll = true;
        boolean skipped = false;
        BlockPos firstUnreachable = null;
        for (Blueprint.PlannedBlock plan : blueprint.placedBlocks(site.origin(), site.rotation())) {
            if (batch.size() >= WORK_BATCH) {
                scannedAll = false;
                break;
            }
            BlockState world = level.getBlockState(plan.pos());
            if (matches(world, plan.state())) {
                continue;
            }
            if (!plan.state().isAir() && BlockRequirements.isDependentPart(plan.state())) {
                continue; // realized atomically by its primary half; never its own work
            }
            if (!world.isAir() && world.getDestroySpeed(level, plan.pos()) < 0) {
                skipped = true; // unbreakable occupant — a villager cannot fix this
                continue;
            }
            if (!plan.state().isAir()) {
                if (!BlockRequirements.isBuildable(plan.state())) {
                    skipped = true; // no item form — template authoring problem
                    continue;
                }
                if (!plan.state().canSurvive(level, plan.pos())) {
                    skipped = true; // support not built yet — later pass retries
                    continue;
                }
            }
            if (!site.mayWork(plan.pos(), self, now)) {
                skipped = true; // another builder's claim
                continue;
            }
            if (!isReachable(level, villager, plan.pos(), walkable)) {
                skipped = true; // no standing spot in reach — burn no give-up timers on it
                if (firstUnreachable == null) {
                    firstUnreachable = plan.pos();
                }
                continue;
            }
            batch.add(plan);
        }

        if (batch.isEmpty()) {
            pruneScaffold(level, ledger, site);
            if (scannedAll && !skipped && foundation.isEmpty()) {
                if (!site.scaffold().isEmpty()) {
                    return planScaffoldTeardown(essence, site); // no mess left behind
                }
                ledger.setStatus(site, ConstructionLedger.Status.DONE);
                cancelSiteRequests(level, ledger, site); // the board owes this site nothing now
                // the builder stays assigned and watches for damage (4.7); reassign it with
                // the marker or /dv build assign to move it to a new site
                return false;
            }
            if (scannedAll && firstUnreachable != null) {
                // everything left is out of reach — build a dirt staircase up to it
                return planScaffold(level, villager, essence, ledger, site, blueprint,
                        firstUnreachable, now);
            }
            // blocked (others' claims, missing supports): wait it out — an open site is a
            // commitment, not a suggestion; chores would spend the site's materials
            return false;
        }

        // Partition the batch by carried materials: entries whose items are in hand get
        // built NOW; only the short ones wait for restocking. One unobtainable material
        // (an empty chest, a bed of the wrong color) must never stall the whole site —
        // that reads as "the builder opened a chest and forgot his job".
        Map<Item, Integer> budget = new LinkedHashMap<>();
        List<Blueprint.PlannedBlock> ready = new ArrayList<>();
        Map<Item, Integer> missing = new LinkedHashMap<>();
        for (Blueprint.PlannedBlock plan : batch) {
            BlockRequirements.Requirement requirement = BlockRequirements.resolve(plan.state());
            if (requirement == null) {
                ready.add(plan); // clear work and free dependent halves need no materials
                continue;
            }
            int have = budget.computeIfAbsent(requirement.item(),
                    item -> essence.countItems(villager, stack -> stack.is(item)));
            if (have >= requirement.count()) {
                budget.put(requirement.item(), have - requirement.count());
                ready.add(plan);
            } else {
                missing.merge(requirement.item(), requirement.count(), Integer::sum);
            }
        }

        TaskQueue queue = essence.getTaskQueue();
        boolean planned = false;
        if (!missing.isEmpty() && now >= essence.getNextToolFetchTime()) {
            essence.setNextToolFetchTime(now + FETCH_COOLDOWN); // one restock attempt per window
            // clearing our own line of sight drops materials around the site — glean
            // those before walking to storage (they are usually the missing items)
            List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class,
                    siteBounds(blueprint, site),
                    drop -> missing.containsKey(drop.getItem().getItem()));
            if (!drops.isEmpty()) {
                queue.enqueue(new PickUpItemsTask(drops.getFirst().blockPosition(), PICKUP_RADIUS));
                planned = true;
            } else {
                if (ensureStaging(level, villager, essence, ledger, site, blueprint, now)) {
                    planned = true;
                }
                if (!essence.getMemory().knownContainers().isEmpty()) {
                    // one trip stocks the whole build: top up every material the blueprint
                    // needs, not just this batch's — walking back per item kind wastes days
                    for (Map.Entry<Item, Integer> requirement : blueprint.requirements().entrySet()) {
                        int wanted = Math.min(FETCH_CAP, requirement.getValue());
                        if (essence.countItems(villager, stack -> stack.is(requirement.getKey())) < wanted) {
                            queue.enqueue(new TakeItemsTask(
                                    itemFilterSpec(requirement.getKey()), wanted));
                            planned = true;
                        }
                    }
                }
                // 4.3: materials the network doesn't hold become requests on the board —
                // gatherers redirect matching produce and haulers deliver, all Phase 3 code
                postRequests(level, villager, essence, ledger, site, blueprint, missing, now);
            }
        }
        // owner directive: before mining a block that won't drop bare-handed, go find the
        // right tool in a chest — only fall back to hands (last resort) when none can be had
        if (!ready.isEmpty() && ensureBreakTool(level, villager, essence, ready, now)) {
            return true;
        }
        if (!ready.isEmpty()) {
            List<BlockPos> claimed = ready.stream().map(Blueprint.PlannedBlock::pos).toList();
            ledger.claim(site, claimed, self, now);
            boolean anyBreak = false;
            for (Blueprint.PlannedBlock plan : ready) {
                BlockState world = level.getBlockState(plan.pos());
                if (plan.state().isAir() || (!world.isAir() && !world.canBeReplaced())) {
                    anyBreak = true;
                }
                if (plan.state().isAir()) {
                    queue.enqueue(new BreakBlockTask(plan.pos()));
                } else {
                    queue.enqueue(new PlaceStateTask(plan.pos(), plan.state()));
                }
            }
            if (anyBreak) {
                queue.enqueue(new PickUpItemsTask(claimed.getFirst(), PICKUP_RADIUS));
            }
            planned = true;
        }
        return planned; // false = blocked entirely — wait out the cooldown, don't chore
    }

    /**
     * The assigned site, OPEN or DONE (a finished site is still watched for repair). Owner
     * decision (2026-07-10): builders never adopt open sites on their own; auto-assignment is
     * the Phase 5 village manager's job. Only a cancelled (removed) site clears the order.
     */
    @Nullable
    private static ConstructionLedger.ConstructionSite activeSite(VillagerEssence essence,
                                                                  ConstructionLedger ledger) {
        int assignedId = essence.getAssignedSiteId();
        if (assignedId == -1) {
            return null;
        }
        ConstructionLedger.ConstructionSite assigned = ledger.getSite(assignedId);
        if (assigned != null) {
            return assigned;
        }
        essence.setAssignedSiteId(-1); // the site was cancelled — the order is spent
        return null;
    }

    /** True on the first blueprint block the world no longer matches — the repair trigger. */
    private static boolean hasDamage(ServerLevel level, ConstructionLedger.ConstructionSite site,
                                     Blueprint blueprint) {
        for (Blueprint.PlannedBlock plan : blueprint.placedBlocks(site.origin(), site.rotation())) {
            if (BlockMatch.matches(level.getBlockState(plan.pos()), plan.state())) {
                continue;
            }
            if (!plan.state().isAir() && BlockRequirements.isDependentPart(plan.state())) {
                continue; // its primary half covers it
            }
            if (!plan.state().isAir() && !BlockRequirements.isBuildable(plan.state())) {
                continue; // nothing a villager could ever place — not "damage"
            }
            return true;
        }
        return false;
    }

    /** Food plus every material of the active site — deposits must not dump the walls. */
    private static List<String> keepFilters(@Nullable Blueprint blueprint) {
        if (blueprint == null) {
            return KEEP_BASE;
        }
        List<String> keep = new ArrayList<>(KEEP_BASE);
        keep.add(SCAFFOLD_FILTER); // foundation/scaffold fill is working material too
        for (Item item : blueprint.requirements().keySet()) {
            keep.add(itemFilterSpec(item));
        }
        return keep;
    }

    /**
     * 4.3: keeps the site's staging container real — the deliver-to point for its material
     * requests. The builder places a carried chest on the ring just outside the footprint,
     * or fetches one from storage; with neither, requests fall back to public storage.
     */
    private static boolean ensureStaging(ServerLevel level, Villager villager, VillagerEssence essence,
                                         ConstructionLedger ledger,
                                         ConstructionLedger.ConstructionSite site,
                                         Blueprint blueprint, long now) {
        BlockPos staging = site.staging();
        if (staging != null) {
            if (level.getBlockState(staging).is(DVTags.STORAGE_CONTAINERS)) {
                return false; // staging stands — nothing to do
            }
            ledger.setStaging(site, null); // broken or never placed — forget it
        }
        Predicate<ItemStack> chest = ItemFilter.parse(CHEST_FILTER);
        if (essence.hasItem(villager, chest)) {
            BlockPos spot = stagingSpot(level, site, blueprint);
            if (spot == null) {
                return false;
            }
            ledger.setStaging(site, spot);
            essence.getTaskQueue().enqueue(new PlaceBlockTask(spot, CHEST_FILTER));
            return true;
        }
        if (StorageLedger.get(level).findSource(site.origin(), villager.blockPosition(),
                villager.getUUID(), chest, now, Set.of()) != null) {
            essence.getTaskQueue().enqueue(new TakeItemsTask(CHEST_FILTER, 1));
            return true;
        }
        return false; // no chest to be had — requests will deliver to public storage instead
    }

    /** First free spot on the ring just outside the footprint, at origin height. */
    @Nullable
    private static BlockPos stagingSpot(ServerLevel level, ConstructionLedger.ConstructionSite site,
                                        Blueprint blueprint) {
        Vec3i size = blueprint.size(site.rotation());
        BlockPos origin = site.origin();
        int y = origin.getY();
        for (int x = -1; x <= size.getX(); x++) {
            for (int z = -1; z <= size.getZ(); z++) {
                if (x != -1 && x != size.getX() && z != -1 && z != size.getZ()) {
                    continue; // interior — ring cells only
                }
                BlockPos pos = origin.offset(x, 0, z).atY(y);
                BlockPos below = pos.below();
                BlockState state = level.getBlockState(pos);
                if ((state.isAir() || state.canBeReplaced())
                        && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                        && !site.scaffold().contains(pos)) {
                    return pos.immutable();
                }
            }
        }
        return null;
    }

    /** Posts one open request per missing item kind the storage network cannot supply. */
    private static void postRequests(ServerLevel level, Villager villager, VillagerEssence essence,
                                     ConstructionLedger ledger,
                                     ConstructionLedger.ConstructionSite site,
                                     Blueprint blueprint, Map<Item, Integer> missing, long now) {
        StorageLedger storage = StorageLedger.get(level);
        BlockPos deliverTo = site.staging() != null
                && level.getBlockState(site.staging()).is(DVTags.STORAGE_CONTAINERS)
                ? site.staging() : nearestPublicStorage(storage, site.origin());
        if (deliverTo == null) {
            return; // nowhere for a hauler to put things down — try again once staging exists
        }
        for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
            Item item = entry.getKey();
            String spec = itemFilterSpec(item);
            Integer existing = site.requests().get(spec);
            if (existing != null && storage.getRequest(existing) != null) {
                continue; // already on the board
            }
            if (storage.findSource(site.origin(), villager.blockPosition(), villager.getUUID(),
                    stack -> stack.is(item), now, Set.of()) != null) {
                continue; // the network has it — fetching handles this kind
            }
            int carried = essence.countItems(villager, stack -> stack.is(item));
            int count = Math.min(FETCH_CAP, Math.max(entry.getValue(),
                    blueprint.requirements().getOrDefault(item, 0) - carried));
            StorageLedger.MaterialRequest request = storage.addRequest(spec, count, deliverTo, now);
            ledger.setSiteRequest(site, spec, request.id());
        }
    }

    /**
     * If the batch will break a block that won't drop with the villager's current tool and a
     * pickaxe is known to sit in the storage network, fetch it first (owner directive: seek
     * the tool, don't spawn it). @return true if a tool fetch was enqueued. When no pickaxe
     * can be found the builder proceeds — scaffolding avoids most mining, and bare hands are
     * the last resort, exactly as a player would.
     */
    private static boolean ensureBreakTool(ServerLevel level, Villager villager, VillagerEssence essence,
                                           List<Blueprint.PlannedBlock> ready, long now) {
        if (now < essence.getNextToolFetchTime()) {
            return false;
        }
        boolean needsTool = false;
        for (Blueprint.PlannedBlock plan : ready) {
            BlockState world = level.getBlockState(plan.pos());
            boolean willBreak = !world.isAir() && !world.canBeReplaced()
                    && (plan.state().isAir() || !BlockMatch.matches(world, plan.state()));
            if (!willBreak || !world.requiresCorrectToolForDrops()
                    || world.getDestroySpeed(level, plan.pos()) < 0) {
                continue; // no drop rule, or unbreakable — a pickaxe wouldn't help
            }
            VillagerEssence.SlotRef tool = essence.findBestTool(villager, world);
            if (tool == null || !tool.stack().isCorrectToolForDrops(world)) {
                needsTool = true;
                break;
            }
        }
        if (!needsTool || essence.getMemory().knownContainers().isEmpty()) {
            return false; // nothing to break by tool, or no chest to look in — hands/scaffold
        }
        // go look in a remembered chest (the personal-memory path, like the miner's tool
        // fetch — the shared ledger only knows a container's contents after a villager has
        // opened it). A wasted trip when no pickaxe turns up is the "look first" behavior.
        essence.setNextToolFetchTime(now + FETCH_COOLDOWN);
        essence.getTaskQueue().enqueue(new TakeItemsTask("pickaxe", 1));
        return true;
    }

    /** Withdraws every request this site posted — also used by /dv build cancel. */
    public static void cancelSiteRequests(ServerLevel level, ConstructionLedger ledger,
                                          ConstructionLedger.ConstructionSite site) {
        StorageLedger storage = StorageLedger.get(level);
        for (int requestId : site.requests().values()) {
            storage.cancelRequest(requestId);
        }
        ledger.clearSiteRequests(site);
    }

    @Nullable
    private static BlockPos nearestPublicStorage(StorageLedger storage, BlockPos anchor) {
        return storage.recordsNear(anchor, StorageLedger.NETWORK_RANGE).stream()
                .filter(entry -> entry.getValue().designation() == StorageLedger.Designation.PUBLIC)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    /** Bottom-layer columns still hanging in the air, bounded by depth and batch size. */
    private static List<BlockPos> foundationWork(ServerLevel level,
                                                 ConstructionLedger.ConstructionSite site,
                                                 Blueprint blueprint, UUID self, long now) {
        List<BlockPos> needed = new ArrayList<>();
        for (Blueprint.PlannedBlock plan : blueprint.placedBlocks(site.origin(), site.rotation())) {
            if (needed.size() >= WORK_BATCH) {
                break;
            }
            if (plan.pos().getY() != site.origin().getY() || plan.state().isAir()) {
                continue; // only the bottom layer's solid cells need bearing
            }
            for (int depth = 1; depth <= SiteValidator.MAX_FOUNDATION_DEPTH
                    && needed.size() < WORK_BATCH; depth++) {
                BlockPos below = plan.pos().below(depth);
                BlockState state = level.getBlockState(below);
                if (state.isFaceSturdy(level, below, Direction.UP)) {
                    break; // grounded
                }
                if ((state.isAir() || state.canBeReplaced()) && site.mayWork(below, self, now)) {
                    needed.add(below.immutable());
                }
            }
        }
        return needed;
    }

    /** @return true when fill placements (or a fill-material fetch) were enqueued. */
    private static boolean planFoundation(Villager villager, VillagerEssence essence,
                                          ConstructionLedger ledger,
                                          ConstructionLedger.ConstructionSite site,
                                          List<BlockPos> foundation, long now) {
        int carried = essence.countItems(villager, ItemFilter.parse(SCAFFOLD_FILTER));
        if (carried == 0) {
            if (now >= essence.getNextToolFetchTime()
                    && !essence.getMemory().knownContainers().isEmpty()) {
                essence.setNextToolFetchTime(now + FETCH_COOLDOWN);
                essence.getTaskQueue().enqueue(new TakeItemsTask(SCAFFOLD_FILTER, FETCH_CAP));
                return true;
            }
            return false; // no fill material right now — walls may rise, this retries every cycle
        }
        List<BlockPos> batch = new ArrayList<>(foundation.subList(0, Math.min(carried, foundation.size())));
        batch.sort(Comparator.comparingInt(BlockPos::getY)); // deepest first, like a mason would
        ledger.claim(site, batch, villager.getUUID(), now);
        for (BlockPos pos : batch) {
            essence.getTaskQueue().enqueue(new PlaceBlockTask(pos, SCAFFOLD_FILTER));
        }
        return true;
    }

    /** Clearing work fills pockets with junk; drop it off before the hands are full. */
    private static boolean planDepositWhenStuffed(ServerLevel level, Villager villager,
                                                  VillagerEssence essence, List<String> keepFilters) {
        if (essence.countEmptySlots(villager) >= MIN_FREE_SLOTS) {
            return false;
        }
        Predicate<ItemStack> keep = ItemFilter.parseAny(keepFilters);
        if (essence.countItems(villager, stack -> !keep.test(stack)) == 0) {
            return false; // everything carried is working material — build on
        }
        boolean knowsStorage = essence.getMemory().nearestContainer(villager.blockPosition()) != null
                || StorageLedger.get(level).hasPublicDeposit(
                        VillageAnchor.resolve(level, villager), villager.getUUID());
        if (!knowsStorage) {
            return false;
        }
        essence.getTaskQueue().enqueue(new DepositToContainerTask(keepFilters));
        return true;
    }

    private static boolean matches(BlockState world, BlockState planned) {
        return BlockMatch.matches(world, planned);
    }

    private static String itemFilterSpec(Item item) {
        return "item:" + BuiltInRegistries.ITEM.getKey(item);
    }

    /**
     * A standing position exists from which the builder can reach the target AND that spot
     * is walkable-connected to where the builder stands now (in {@code walkable}). The
     * connectivity half matters: a foothold atop a wall the villager can't climb is within
     * arm's reach geometrically but stranded — accepting it made the placement task walk at
     * an unreachable spot and burn its give-up timer. Excluding it instead lets the planner
     * build a scaffold staircase, after which the recomputed {@code walkable} set includes
     * the scaffold steps and the roof foothold becomes reachable.
     */
    private static boolean isReachable(ServerLevel level, Villager villager, BlockPos target,
                                       Set<BlockPos> walkable) {
        double reachSq = WorkHelper.REACH * WorkHelper.REACH;
        if (villager.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) <= reachSq) {
            return true;
        }
        for (int dy = 1; dy >= -5; dy--) {
            for (int dx = -REACH_SCAN; dx <= REACH_SCAN; dx++) {
                for (int dz = -REACH_SCAN; dz <= REACH_SCAN; dz++) {
                    BlockPos feet = target.offset(dx, dy, dz);
                    if (feet.equals(target) || !walkable.contains(feet)) {
                        continue;
                    }
                    double ex = feet.getX() + 0.5 - (target.getX() + 0.5);
                    double ey = feet.getY() + EYE_HEIGHT - (target.getY() + 0.5);
                    double ez = feet.getZ() + 0.5 - (target.getZ() + 0.5);
                    if (ex * ex + ey * ey + ez * ez <= reachSq) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The set of standable cells the builder can walk to from where it stands, by a bounded
     * flood fill stepping one block up or down (villager step height). This is our own cheap,
     * deterministic reachability oracle — one fill per plan cycle, reused for every block —
     * rather than per-block engine pathfinding (which proved unreliable mid-plan).
     */
    private static Set<BlockPos> walkableRegion(ServerLevel level, Villager villager) {
        Set<BlockPos> seen = new HashSet<>();
        java.util.Deque<BlockPos> frontier = new java.util.ArrayDeque<>();
        // Seed from every standable cell around the villager, not one guessed spot: wherever
        // it actually stands (floor, scaffold step, rooftop) is a real foothold, and the
        // single-cell guess failed when its block resolved into solid or a partial block.
        BlockPos base = villager.blockPosition();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -3; dy <= 1; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos cell = base.offset(dx, dy, dz);
                    if (isStandable(level, cell) && seen.add(cell)) {
                        frontier.add(cell);
                    }
                }
            }
        }
        while (!frontier.isEmpty() && seen.size() < MAX_WALKABLE_CELLS) {
            BlockPos cur = frontier.poll();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                for (int dy = 1; dy >= -1; dy--) { // step up 1, level, or down 1
                    BlockPos next = cur.relative(dir).atY(cur.getY() + dy);
                    if (seen.contains(next) || !isStandable(level, next)) {
                        continue;
                    }
                    if (dy == 1 && !level.getBlockState(cur.above(2)).getCollisionShape(level, cur.above(2)).isEmpty()) {
                        continue; // no headroom to step up here
                    }
                    seen.add(next);
                    frontier.add(next);
                }
            }
        }
        return seen;
    }

    private static boolean isStandable(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
    }

    /**
     * Plans a 1-wide dirt/cobble staircase up to standing height beside an unreachable
     * target: the top step puts the builder's feet one below the target, each lower step
     * descends one block outward until it grounds. Steps are recorded on the site so the
     * teardown pass removes every one of them; cells the blueprint owns are never used.
     */
    private static boolean planScaffold(ServerLevel level, Villager villager, VillagerEssence essence,
                                        ConstructionLedger ledger, ConstructionLedger.ConstructionSite site,
                                        Blueprint blueprint, BlockPos target, long now) {
        Set<BlockPos> reserved = new HashSet<>();
        for (Blueprint.PlannedBlock plan : blueprint.placedBlocks(site.origin(), site.rotation())) {
            if (!plan.state().isAir()) {
                reserved.add(plan.pos());
            }
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            List<BlockPos> steps = new ArrayList<>();
            BlockPos cursor = target.relative(dir).below(2); // stand here = feet at target.y - 1
            boolean grounded = false;
            for (int i = 0; i <= MAX_SCAFFOLD_HEIGHT && !grounded; i++) {
                BlockState state = level.getBlockState(cursor);
                if ((!state.isAir() && !state.canBeReplaced())
                        || reserved.contains(cursor) || site.scaffold().contains(cursor)) {
                    steps.clear();
                    break; // this direction collides with the world or the plan — try another
                }
                steps.add(cursor);
                BlockPos below = cursor.below();
                grounded = level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
                cursor = cursor.relative(dir).below();
            }
            if (steps.isEmpty() || !grounded) {
                continue;
            }
            Collections.reverse(steps); // build bottom-up, climbing as it goes
            int carried = essence.countItems(villager, ItemFilter.parse(SCAFFOLD_FILTER));
            if (carried < steps.size()) {
                if (now >= essence.getNextToolFetchTime()
                        && !essence.getMemory().knownContainers().isEmpty()) {
                    essence.setNextToolFetchTime(now + FETCH_COOLDOWN);
                    essence.getTaskQueue().enqueue(new TakeItemsTask(SCAFFOLD_FILTER, steps.size()));
                    return true;
                }
                return false;
            }
            for (BlockPos step : steps) {
                ledger.addScaffold(site, step);
                essence.getTaskQueue().enqueue(new PlaceBlockTask(step, SCAFFOLD_FILTER));
            }
            return true;
        }
        return false; // no side offers a staircase line — give up this cycle
    }

    /** The structure matches its blueprint; now the scaffold comes down, top step first. */
    private static boolean planScaffoldTeardown(VillagerEssence essence,
                                                ConstructionLedger.ConstructionSite site) {
        List<BlockPos> steps = new ArrayList<>(site.scaffold());
        steps.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed());
        for (BlockPos step : steps) {
            essence.getTaskQueue().enqueue(new BreakBlockTask(step));
        }
        essence.getTaskQueue().enqueue(new PickUpItemsTask(steps.getFirst(), PICKUP_RADIUS));
        return true;
    }

    /** Forgets scaffold entries the world no longer holds (broken by us or anyone else). */
    private static void pruneScaffold(ServerLevel level, ConstructionLedger ledger,
                                      ConstructionLedger.ConstructionSite site) {
        for (BlockPos pos : List.copyOf(site.scaffold())) {
            if (level.getBlockState(pos).isAir()) {
                ledger.removeScaffold(site, pos);
            }
        }
    }

    /** The rotated blueprint box, slightly inflated — where our own clearing drops land. */
    private static AABB siteBounds(Blueprint blueprint, ConstructionLedger.ConstructionSite site) {
        Vec3i size = blueprint.size(site.rotation());
        BlockPos origin = site.origin();
        return new AABB(origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + size.getX(), origin.getY() + size.getY(), origin.getZ() + size.getZ())
                .inflate(2);
    }
}
