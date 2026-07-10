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
 * Physically hauls up to `count` items matching a filter to a destination container:
 * carried matches are walked over directly; missing ones are fetched from ledger-known or
 * personally remembered containers first (reservation-backed, never from the destination
 * itself). Items matching the keep filters are invisible to this task — a lumberjack
 * serving an axe request never hands over its own axe. When bound to a material request,
 * each armful credited on arrival; requests never teleport items (design rule).
 */
public class DeliverItemsTask implements Task {
    public static final String TYPE = "deliver_items";
    private static final int GIVE_UP_TICKS = 1200;

    private final String filter;
    private final List<String> keepFilters;
    private final int count;
    private final BlockPos destination;
    private final int requestId; // -1: plain hauling, no request to credit
    private final Predicate<ItemStack> matching;

    private final Set<BlockPos> visited = new HashSet<>(); // in-memory only; a reload restarts sourcing
    @Nullable
    private BlockPos source;
    private int delivered;
    private int ticksRun;

    public DeliverItemsTask(String filter, List<String> keepFilters, int count,
                            BlockPos destination, int requestId) {
        this(filter, keepFilters, count, destination, requestId, 0);
    }

    private DeliverItemsTask(String filter, List<String> keepFilters, int count,
                             BlockPos destination, int requestId, int delivered) {
        this.filter = filter;
        this.keepFilters = List.copyOf(keepFilters);
        this.count = count;
        this.destination = destination.immutable();
        this.requestId = requestId;
        this.matching = effectiveFilter(filter, this.keepFilters);
        this.delivered = delivered;
    }

    /** The request filter minus the villager's keep list — kept items are never given away. */
    public static Predicate<ItemStack> effectiveFilter(String filter, List<String> keepFilters) {
        Predicate<ItemStack> wanted = ItemFilter.parse(filter);
        Predicate<ItemStack> keep = ItemFilter.parseAny(keepFilters);
        return stack -> wanted.test(stack) && !keep.test(stack);
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
            return finish(ledger, self);
        }
        if (delivered >= count) {
            return finish(ledger, self);
        }
        if (essence.countItems(villager, matching) > 0) {
            return deliver(level, villager, essence, ledger, self);
        }
        return acquire(level, villager, essence, ledger, self);
    }

    private Status acquire(ServerLevel level, Villager villager, VillagerEssence essence,
                           StorageLedger ledger, UUID self) {
        long now = level.getGameTime();
        int remaining = count - delivered;
        if (source == null) {
            Set<BlockPos> exclude = new HashSet<>(visited);
            exclude.add(destination); // taking from the destination would be a delivery loop
            source = ledger.findSource(VillageAnchor.resolve(level, villager),
                    villager.blockPosition(), self, matching, now, exclude);
            if (source != null) {
                ledger.reserve(source, self, matching, remaining, now);
            } else {
                source = essence.getMemory().knownContainers().stream()
                        .filter(pos -> !exclude.contains(pos))
                        .filter(pos -> ledger.mayOpen(pos, self))
                        .min(Comparator.comparingDouble(pos -> pos.distSqr(villager.blockPosition())))
                        .orElse(null);
            }
            if (source == null) {
                return finish(ledger, self); // no known way to get the goods
            }
        }
        if (!WorkHelper.moveIntoReachAndLook(villager, source)) {
            return Status.IN_PROGRESS;
        }
        if (WorkHelper.findObstruction(level, villager, source) != null) {
            ledger.release(source, self);
            visited.add(source);
            source = null;
            return Status.IN_PROGRESS;
        }
        if (!(level.getBlockEntity(source) instanceof Container container)
                || !level.getBlockState(source).is(DVTags.STORAGE_CONTAINERS)) {
            essence.getMemory().forgetContainer(source);
            ledger.forget(source);
            visited.add(source);
            source = null;
            return Status.IN_PROGRESS;
        }

        int untouchable = ledger.reservedByOthers(source, self, matching, now);
        int available = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && matching.test(stack)) {
                available += stack.getCount();
            }
        }
        int toTake = Math.min(remaining, Math.max(0, available - untouchable));
        boolean tookAnything = false;
        for (int i = 0; i < container.getContainerSize() && toTake > 0; i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && matching.test(stack)) {
                ItemStack moving = stack.copyWithCount(Math.min(toTake, stack.getCount()));
                int offered = moving.getCount();
                moving = ContainerUtil.insert(villager.getInventory(), moving);
                moving = ContainerUtil.insert(essence.getExtraInventory(), moving);
                int moved = offered - moving.getCount();
                if (moved > 0) {
                    stack.shrink(moved);
                    container.setChanged();
                    toTake -= moved;
                    tookAnything = true;
                }
            }
        }
        if (tookAnything) {
            ContainerAnimator.flash(level, source);
        }
        ledger.release(source, self);
        ledger.recordSnapshot(source, container, now);
        visited.add(source);
        source = null;
        return Status.IN_PROGRESS;
    }

    private Status deliver(ServerLevel level, Villager villager, VillagerEssence essence,
                           StorageLedger ledger, UUID self) {
        if (!WorkHelper.moveIntoReachAndLook(villager, destination)) {
            return Status.IN_PROGRESS;
        }
        if (WorkHelper.findObstruction(level, villager, destination) != null) {
            return finish(ledger, self); // walled-off destination — nowhere to deliver
        }
        if (!(level.getBlockEntity(destination) instanceof Container container)
                || !level.getBlockState(destination).is(DVTags.STORAGE_CONTAINERS)) {
            essence.getMemory().forgetContainer(destination);
            ledger.forget(destination);
            return finish(ledger, self);
        }

        int remaining = count - delivered;
        int moved = 0;
        for (SimpleContainer inventory : new SimpleContainer[]{villager.getInventory(), essence.getExtraInventory()}) {
            for (int i = 0; i < inventory.getContainerSize() && remaining - moved > 0; i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && matching.test(stack)) {
                    ItemStack moving = stack.copyWithCount(Math.min(remaining - moved, stack.getCount()));
                    int offered = moving.getCount();
                    ItemStack leftover = ContainerUtil.insert(container, moving);
                    int inserted = offered - leftover.getCount();
                    if (inserted > 0) {
                        stack.shrink(inserted);
                        inventory.setChanged();
                        moved += inserted;
                    }
                }
            }
        }
        if (moved > 0) {
            delivered += moved;
            ContainerAnimator.flash(level, destination);
            ledger.recordSnapshot(destination, container, level.getGameTime());
            if (requestId >= 0) {
                ledger.fulfillRequest(requestId, moved);
            }
        }
        if (delivered >= count || moved == 0) {
            return finish(ledger, self); // done, or the destination is full
        }
        return Status.IN_PROGRESS;
    }

    private Status finish(StorageLedger ledger, UUID self) {
        ledger.releaseAll(self);
        return delivered > 0 ? Status.DONE : Status.FAILED;
    }

    @Override
    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putString("filter", filter);
        ListTag keeps = new ListTag();
        for (String keep : keepFilters) {
            keeps.add(StringTag.valueOf(keep));
        }
        tag.put("keep", keeps);
        tag.putInt("count", count);
        tag.putLong("destination", destination.asLong());
        tag.putInt("request_id", requestId);
        tag.putInt("delivered", delivered);
        return tag;
    }

    public static Task load(CompoundTag tag, HolderLookup.Provider provider) {
        List<String> keeps = new ArrayList<>();
        for (Tag entry : tag.getList("keep", Tag.TAG_STRING)) {
            keeps.add(entry.getAsString());
        }
        return new DeliverItemsTask(tag.getString("filter"), keeps, tag.getInt("count"),
                BlockPos.of(tag.getLong("destination")), tag.getInt("request_id"),
                tag.getInt("delivered"));
    }
}
