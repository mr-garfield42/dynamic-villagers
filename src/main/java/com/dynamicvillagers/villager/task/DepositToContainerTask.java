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
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Empties the combined inventory (vanilla + extra slots) into storage, except items matching
 * the keep filters (default keeps food — villagers don't dump their lunch). Destination
 * choice is Phase 3: designated PUBLIC storage first, preferring a chest that already holds
 * a matching item kind (sorting emerges), spilling over to the next candidate when one fills
 * up; the Phase 2 nearest-personally-remembered container is the fallback where no public
 * storage is known. Other villagers' private chests are never touched.
 */
public class DepositToContainerTask implements Task {
    public static final String TYPE = "deposit_to_container";
    private static final int GIVE_UP_TICKS = 600;

    private final List<String> keepFilters;
    private final Predicate<ItemStack> keep;
    private final Set<BlockPos> skipped = new HashSet<>(); // full or walled-off; in-memory only
    @Nullable
    private BlockPos target;
    private int ticksRun;
    private boolean depositedAnything;

    public DepositToContainerTask() {
        this(List.of("food"));
    }

    public DepositToContainerTask(List<String> keepFilters) {
        this(keepFilters, null);
    }

    private DepositToContainerTask(List<String> keepFilters, @Nullable BlockPos target) {
        this.keepFilters = List.copyOf(keepFilters);
        this.keep = ItemFilter.parseAny(this.keepFilters);
        this.target = target;
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
        if (++ticksRun > GIVE_UP_TICKS) {
            return depositedAnything ? Status.DONE : Status.FAILED;
        }
        List<ItemStack> toStore = carriedNonKept(villager, essence);
        if (toStore.isEmpty()) {
            return Status.DONE;
        }
        if (target == null) {
            target = ledger.findDepositTarget(
                    VillageAnchor.resolve(level, villager), villager.blockPosition(),
                    self, toStore, skipped);
            if (target == null) {
                target = essence.getMemory().knownContainers().stream()
                        .filter(pos -> !skipped.contains(pos))
                        .filter(pos -> ledger.mayOpen(pos, self))
                        .min(Comparator.comparingDouble(pos -> pos.distSqr(villager.blockPosition())))
                        .orElse(null);
            }
            if (target == null) {
                // ran out of places to put things; whatever didn't fit stays carried
                return depositedAnything ? Status.DONE : Status.FAILED;
            }
        }
        if (!WorkHelper.moveIntoReachAndLook(villager, target)) {
            return Status.IN_PROGRESS;
        }
        if (WorkHelper.findObstruction(level, villager, target) != null) {
            skipped.add(target); // can't see it, can't open it — walled off, try elsewhere
            target = null;
            return Status.IN_PROGRESS;
        }
        if (!(level.getBlockEntity(target) instanceof Container container)
                || !level.getBlockState(target).is(DVTags.STORAGE_CONTAINERS)) {
            essence.getMemory().forgetContainer(target); // it's gone — stop believing in it
            ledger.forget(target);
            target = null;
            return Status.IN_PROGRESS;
        }

        ContainerAnimator.flash(level, target); // opened, even if everything ends up kept
        boolean movedAnything = depositFrom(villager.getInventory(), container)
                | depositFrom(essence.getExtraInventory(), container);
        ledger.recordSnapshot(target, container, level.getGameTime());
        if (movedAnything) {
            depositedAnything = true;
        }
        if (carriedNonKept(villager, essence).isEmpty()) {
            return Status.DONE;
        }
        skipped.add(target); // this one is full — spill over to the next candidate
        target = null;
        return Status.IN_PROGRESS;
    }

    private List<ItemStack> carriedNonKept(Villager villager, VillagerEssence essence) {
        List<ItemStack> found = new ArrayList<>();
        for (SimpleContainer source : new SimpleContainer[]{villager.getInventory(), essence.getExtraInventory()}) {
            for (int i = 0; i < source.getContainerSize(); i++) {
                ItemStack stack = source.getItem(i);
                if (!stack.isEmpty() && !keep.test(stack)) {
                    found.add(stack);
                }
            }
        }
        return found;
    }

    private boolean depositFrom(SimpleContainer source, Container destination) {
        boolean movedAnything = false;
        for (int i = 0; i < source.getContainerSize(); i++) {
            ItemStack stack = source.getItem(i);
            if (!stack.isEmpty() && !keep.test(stack)) {
                int before = stack.getCount();
                ItemStack remainder = ContainerUtil.insert(destination, stack);
                source.setItem(i, remainder);
                movedAnything |= remainder.getCount() < before;
            }
        }
        return movedAnything;
    }

    @Override
    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        if (target != null) {
            tag.putLong("target", target.asLong());
        }
        ListTag keeps = new ListTag();
        for (String filter : keepFilters) {
            keeps.add(StringTag.valueOf(filter));
        }
        tag.put("keep", keeps);
        return tag;
    }

    public static Task load(CompoundTag tag, HolderLookup.Provider provider) {
        List<String> keeps = new ArrayList<>();
        for (Tag entry : tag.getList("keep", Tag.TAG_STRING)) {
            keeps.add(entry.getAsString());
        }
        return new DepositToContainerTask(keeps,
                tag.contains("target") ? BlockPos.of(tag.getLong("target")) : null);
    }
}
