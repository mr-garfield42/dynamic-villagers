package com.dynamicvillagers.villager.role;

import com.dynamicvillagers.construction.BlockRequirements;
import com.dynamicvillagers.construction.Blueprint;
import com.dynamicvillagers.construction.Blueprints;
import com.dynamicvillagers.village.ConstructionLedger;
import com.dynamicvillagers.village.StorageLedger;
import com.dynamicvillagers.village.VillageAnchor;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.task.BreakBlockTask;
import com.dynamicvillagers.villager.task.DepositToContainerTask;
import com.dynamicvillagers.villager.task.PickUpItemsTask;
import com.dynamicvillagers.villager.task.PlaceStateTask;
import com.dynamicvillagers.villager.task.TakeItemsTask;
import com.dynamicvillagers.villager.task.TaskQueue;
import com.dynamicvillagers.villager.work.ItemFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    public static final int WORK_BATCH = 12;
    private static final int MIN_FREE_SLOTS = 4;
    private static final int FETCH_COOLDOWN = 1200;
    private static final int FETCH_CAP = 64; // one errand's worth of one material
    private static final double PICKUP_RADIUS = 5.0;
    private static final List<String> KEEP_BASE = List.of("food");

    @Override
    public boolean plan(ServerLevel level, Villager villager, VillagerEssence essence) {
        ConstructionLedger ledger = ConstructionLedger.get(level);
        ConstructionLedger.ConstructionSite site = activeSite(level, villager, essence, ledger);
        Blueprint blueprint = site != null ? Blueprints.load(level, site.templateId()) : null;

        List<String> keepFilters = keepFilters(blueprint);
        if (planDepositWhenStuffed(level, villager, essence, keepFilters)) {
            return true;
        }
        if (site == null || blueprint == null) {
            return RequestChore.plan(level, villager, essence, keepFilters)
                    || TorchChore.plan(level, villager, essence);
        }

        long now = level.getGameTime();
        UUID self = villager.getUUID();
        List<Blueprint.PlannedBlock> batch = new ArrayList<>();
        boolean scannedAll = true;
        boolean skipped = false;
        for (Blueprint.PlannedBlock plan : blueprint.placedBlocks(site.origin(), site.rotation())) {
            if (batch.size() >= WORK_BATCH) {
                scannedAll = false;
                break;
            }
            BlockState world = level.getBlockState(plan.pos());
            if (matches(world, plan.state())) {
                continue;
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
            batch.add(plan);
        }

        if (batch.isEmpty()) {
            if (scannedAll && !skipped) {
                ledger.setStatus(site, ConstructionLedger.Status.DONE);
                if (essence.getAssignedSiteId() == site.id()) {
                    essence.setAssignedSiteId(-1);
                }
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
            // clearing our own line of sight drops materials around the site — glean
            // those before walking to storage (they are usually the missing items)
            List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class,
                    siteBounds(blueprint, site),
                    drop -> missing.containsKey(drop.getItem().getItem()));
            if (!drops.isEmpty()) {
                essence.setNextToolFetchTime(now + FETCH_COOLDOWN);
                queue.enqueue(new PickUpItemsTask(drops.getFirst().blockPosition(), PICKUP_RADIUS));
                planned = true;
            } else if (!essence.getMemory().knownContainers().isEmpty()) {
                // one trip stocks the whole build: top up every material the blueprint
                // needs, not just this batch's — walking back per item kind wastes days
                essence.setNextToolFetchTime(now + FETCH_COOLDOWN);
                for (Map.Entry<Item, Integer> requirement : blueprint.requirements().entrySet()) {
                    int wanted = Math.min(FETCH_CAP, requirement.getValue());
                    if (essence.countItems(villager, stack -> stack.is(requirement.getKey())) < wanted) {
                        queue.enqueue(new TakeItemsTask(
                                itemFilterSpec(requirement.getKey()), wanted));
                        planned = true;
                    }
                }
            }
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

    /** The assigned site when it is real and open; otherwise the oldest open site in range. */
    @Nullable
    private static ConstructionLedger.ConstructionSite activeSite(ServerLevel level, Villager villager,
                                                                  VillagerEssence essence,
                                                                  ConstructionLedger ledger) {
        int assignedId = essence.getAssignedSiteId();
        if (assignedId != -1) {
            ConstructionLedger.ConstructionSite assigned = ledger.getSite(assignedId);
            if (assigned != null && assigned.status() == ConstructionLedger.Status.OPEN) {
                return assigned;
            }
            essence.setAssignedSiteId(-1); // cancelled or finished — the order is spent
        }
        BlockPos anchor = VillageAnchor.resolve(level, villager);
        List<ConstructionLedger.ConstructionSite> open = ledger.sitesNear(
                anchor, StorageLedger.NETWORK_RANGE, ConstructionLedger.Status.OPEN);
        return open.isEmpty() ? null : open.getFirst();
    }

    /** Food plus every material of the active site — deposits must not dump the walls. */
    private static List<String> keepFilters(@Nullable Blueprint blueprint) {
        if (blueprint == null) {
            return KEEP_BASE;
        }
        List<String> keep = new ArrayList<>(KEEP_BASE);
        for (Item item : blueprint.requirements().keySet()) {
            keep.add(itemFilterSpec(item));
        }
        return keep;
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
        return world == planned || (world.isAir() && planned.isAir());
    }

    private static String itemFilterSpec(Item item) {
        return "item:" + BuiltInRegistries.ITEM.getKey(item);
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
