package com.dynamicvillagers.villager.task;

import com.dynamicvillagers.registry.DVTags;
import com.dynamicvillagers.village.StorageLedger;
import com.dynamicvillagers.village.VillageAnchor;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.work.ContainerAnimator;
import com.dynamicvillagers.villager.work.ContainerUtil;
import com.dynamicvillagers.villager.work.ItemFilter;
import com.dynamicvillagers.villager.work.WorkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Withdraws items matching a filter until `count` are carried. Village knowledge first: if
 * the storage ledger knows a container holding unreserved matches, the villager reserves
 * them and walks straight there (Phase 3 — no more rummaging when the village knows where
 * things are). Otherwise it falls back to the Phase 2 search: visit personally remembered
 * containers nearest-first and look inside each — knowing a chest exists is not knowing its
 * contents (design rule #1). Every container actually opened tops up the ledger, so searches
 * get rarer over time. Fails when all known options are exhausted.
 */
public class TakeItemsTask implements Task {
    public static final String TYPE = "take_items";
    private static final int GIVE_UP_TICKS = 1200;

    private final String filter;
    private final int count;
    private final Predicate<ItemStack> predicate;
    private final Set<BlockPos> visited = new HashSet<>(); // in-memory only; a reload restarts the search
    @Nullable
    private BlockPos target;
    private int ticksRun;

    public TakeItemsTask(String filter, int count) {
        this.filter = filter;
        this.count = count;
        this.predicate = ItemFilter.parse(filter);
    }

    @Override
    public String typeId() {
        return TYPE;
    }

    @Override
    public Status tick(ServerLevel level, Villager villager) {
        VillagerEssence essence = VillagerEssence.get(villager);
        StorageLedger ledger = StorageLedger.get(level);
        UUID self = villager.getUUID();
        long now = level.getGameTime();
        if (essence.countItems(villager, predicate) >= count) {
            ledger.releaseAll(self);
            return Status.DONE;
        }
        if (++ticksRun > GIVE_UP_TICKS) {
            ledger.releaseAll(self);
            return Status.FAILED;
        }
        if (target == null) {
            int needed = count - essence.countItems(villager, predicate);
            // going where the goods are known beats searching nearby
            target = ledger.findSource(
                    VillageAnchor.resolve(level, villager), villager.blockPosition(),
                    self, predicate, now, visited);
            if (target != null) {
                ledger.reserve(target, self, predicate, needed, now);
            } else {
                target = essence.getMemory().knownContainers().stream()
                        .filter(pos -> !visited.contains(pos))
                        .filter(pos -> ledger.mayOpen(pos, self))
                        .min(Comparator.comparingDouble(pos -> pos.distSqr(villager.blockPosition())))
                        .orElse(null);
            }
            if (target == null) {
                ledger.releaseAll(self);
                return Status.FAILED; // searched everywhere it knows
            }
        }
        if (!WorkHelper.moveIntoReachAndLook(villager, target)) {
            return Status.IN_PROGRESS;
        }
        if (WorkHelper.findObstruction(level, villager, target) != null) {
            ledger.release(target, self); // can't see it, can't open it — walled off, try elsewhere
            visited.add(target);
            target = null;
            return Status.IN_PROGRESS;
        }
        if (!(level.getBlockEntity(target) instanceof Container container)
                || !level.getBlockState(target).is(DVTags.STORAGE_CONTAINERS)) {
            essence.getMemory().forgetContainer(target);
            ledger.forget(target); // gone (or not storage after all) — stop believing in it
            visited.add(target);
            target = null;
            return Status.IN_PROGRESS;
        }

        // a withdrawal must leave other villagers' claims behind
        int needed = count - essence.countItems(villager, predicate);
        int untouchable = ledger.reservedByOthers(target, self, predicate, now);
        int matching = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && predicate.test(stack)) {
                matching += stack.getCount();
            }
        }
        int toTake = Math.min(needed, Math.max(0, matching - untouchable));
        for (int i = 0; i < container.getContainerSize() && toTake > 0; i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && predicate.test(stack)) {
                ItemStack moving = stack.copyWithCount(Math.min(toTake, stack.getCount()));
                int offered = moving.getCount();
                moving = ContainerUtil.insert(villager.getInventory(), moving);
                moving = ContainerUtil.insert(essence.getExtraInventory(), moving);
                int moved = offered - moving.getCount();
                if (moved > 0) {
                    stack.shrink(moved);
                    container.setChanged();
                    toTake -= moved;
                }
            }
        }
        // opening the chest swings the lid whether or not anything matched — players'
        // lids do not stay shut just because they leave empty-handed
        ContainerAnimator.flash(level, target);
        ledger.release(target, self);
        ledger.recordSnapshot(target, container, now); // the visit is knowledge, stale or not
        visited.add(target);
        target = null;
        if (essence.countItems(villager, predicate) >= count) {
            ledger.releaseAll(self);
            return Status.DONE;
        }
        return Status.IN_PROGRESS;
    }

    @Override
    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putString("filter", filter);
        tag.putInt("count", count);
        return tag;
    }

    public static Task load(CompoundTag tag, HolderLookup.Provider provider) {
        return new TakeItemsTask(tag.getString("filter"), tag.getInt("count"));
    }
}
