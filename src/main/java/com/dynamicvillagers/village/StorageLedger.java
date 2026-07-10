package com.dynamicvillagers.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The village's collective knowledge of storage: what each known container held the last time
 * a villager actually opened it, who a container belongs to, and which items are already
 * claimed by someone's errand. Contents enter the ledger ONLY through a real visit — never
 * from world scanning — so the ledger can be stale (a player emptying a chest behind the
 * village's back is not observed) and is corrected on the next visit. This is the CLAUDE.md
 * carve-out from design rule #1: shared knowledge exists purely so villagers stop wasting
 * time searching through chests.
 *
 * One ledger per level; queries are distance-gated around a villager's anchor (bell/bed/self)
 * so separate villages never see each other's records. Real village identity is Phase 5.
 */
public class StorageLedger extends SavedData {
    public static final String DATA_NAME = "dynamicvillagers_storage";
    public static final int NETWORK_RANGE = 64;
    /** Backstop for reservations orphaned by cleared task queues; tasks release explicitly. */
    public static final long RESERVATION_TTL = 6000;

    public enum Designation {
        UNCLAIMED, // default: anyone may use it (exactly the Phase 2 behavior)
        PUBLIC,    // village storage: preferred deposit target
        PRIVATE;   // one villager's chest: nobody else opens it, in either direction

        @Nullable
        public static Designation byName(String name) {
            for (Designation value : values()) {
                if (value.name().equals(name)) {
                    return value;
                }
            }
            return null;
        }
    }

    /** A claim on concrete items so two villagers don't plan around the same stack. */
    public static final class Reservation {
        private final UUID holder;
        private final ItemStack sample; // one of the reserved item, count ignored
        private int count;
        private final long expiry;

        private Reservation(UUID holder, ItemStack sample, int count, long expiry) {
            this.holder = holder;
            this.sample = sample;
            this.count = count;
            this.expiry = expiry;
        }

        public UUID holder() {
            return holder;
        }

        public int count() {
            return count;
        }
    }

    /** Everything the village remembers about one container. */
    public static final class ContainerRecord {
        private List<ItemStack> contents = new ArrayList<>(); // merged by item+components
        private long lastInspected = -1; // -1: designated but never opened
        private int freeSlots = -1;      // -1: unknown
        private Designation designation = Designation.UNCLAIMED;
        @Nullable
        private UUID owner;
        private final List<Reservation> reservations = new ArrayList<>();

        public List<ItemStack> contents() {
            return Collections.unmodifiableList(contents);
        }

        public long lastInspected() {
            return lastInspected;
        }

        public Designation designation() {
            return designation;
        }

        @Nullable
        public UUID owner() {
            return owner;
        }

        public List<Reservation> reservations() {
            return Collections.unmodifiableList(reservations);
        }

        public int count(Predicate<ItemStack> predicate) {
            int total = 0;
            for (ItemStack stack : contents) {
                if (predicate.test(stack)) {
                    total += stack.getCount();
                }
            }
            return total;
        }

        private int reserved(Predicate<ItemStack> predicate, @Nullable UUID except) {
            int total = 0;
            for (Reservation reservation : reservations) {
                if (!reservation.holder.equals(except) && predicate.test(reservation.sample)) {
                    total += reservation.count;
                }
            }
            return total;
        }

        private boolean containsMatching(List<ItemStack> samples) {
            for (ItemStack held : contents) {
                for (ItemStack sample : samples) {
                    if (ItemStack.isSameItemSameComponents(held, sample)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     * "The village wants `count` items matching `filter` delivered to `deliverTo`." Requests
     * direct work; they never teleport items — fulfillment is a villager physically hauling
     * (DeliverItemsTask). Posted by debug command now; Phase 4 construction sites later.
     */
    public static final class MaterialRequest {
        private final int id;
        private final String filter;
        private final BlockPos deliverTo;
        private int remaining;
        private final long created;

        private MaterialRequest(int id, String filter, BlockPos deliverTo, int remaining, long created) {
            this.id = id;
            this.filter = filter;
            this.deliverTo = deliverTo;
            this.remaining = remaining;
            this.created = created;
        }

        public int id() {
            return id;
        }

        public String filter() {
            return filter;
        }

        public BlockPos deliverTo() {
            return deliverTo;
        }

        public int remaining() {
            return remaining;
        }

        public long created() {
            return created;
        }
    }

    private static final SavedData.Factory<StorageLedger> FACTORY =
            new SavedData.Factory<>(StorageLedger::new, StorageLedger::load, null);

    private final Map<BlockPos, ContainerRecord> records = new HashMap<>();
    private final List<MaterialRequest> requests = new ArrayList<>();
    private int nextRequestId = 1;

    public static StorageLedger get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    @Nullable
    public ContainerRecord getRecord(BlockPos pos) {
        return records.get(pos);
    }

    /** Records what a villager saw inside a container it actually opened. */
    public void recordSnapshot(BlockPos pos, Container container, long now) {
        ContainerRecord record = records.computeIfAbsent(pos.immutable(), p -> new ContainerRecord());
        List<ItemStack> merged = new ArrayList<>();
        int free = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) {
                free++;
                continue;
            }
            boolean found = false;
            for (ItemStack held : merged) {
                if (ItemStack.isSameItemSameComponents(held, stack)) {
                    held.grow(stack.getCount());
                    found = true;
                    break;
                }
            }
            if (!found) {
                merged.add(stack.copy());
            }
        }
        record.contents = merged;
        record.freeSlots = free;
        record.lastInspected = now;
        pruneExpired(record, now);
        setDirty();
    }

    /** A villager saw that the container is gone — the village stops believing in it. */
    public void forget(BlockPos pos) {
        if (records.remove(pos) != null) {
            setDirty();
        }
    }

    public void setDesignation(BlockPos pos, Designation designation, @Nullable UUID owner) {
        ContainerRecord record = records.computeIfAbsent(pos.immutable(), p -> new ContainerRecord());
        record.designation = designation;
        record.owner = designation == Designation.PRIVATE ? owner : null;
        setDirty();
    }

    /** Private containers open only for their owner; everything else is fair game. */
    public boolean mayOpen(BlockPos pos, UUID villager) {
        ContainerRecord record = records.get(pos);
        return record == null
                || record.designation != Designation.PRIVATE
                || villager.equals(record.owner);
    }

    /** Matching items minus what other villagers have already claimed. */
    public int availableTo(BlockPos pos, UUID self, Predicate<ItemStack> predicate, long now) {
        ContainerRecord record = records.get(pos);
        if (record == null || !mayOpen(pos, self)) {
            return 0;
        }
        pruneExpired(record, now);
        return Math.max(0, record.count(predicate) - record.reserved(predicate, self));
    }

    /** What other villagers have claimed here — a withdrawal must leave that much behind. */
    public int reservedByOthers(BlockPos pos, UUID self, Predicate<ItemStack> predicate, long now) {
        ContainerRecord record = records.get(pos);
        if (record == null) {
            return 0;
        }
        pruneExpired(record, now);
        return record.reserved(predicate, self);
    }

    /**
     * The nearest container known to hold unreserved matching items that this villager may
     * open. Village knowledge: the villager need not have personally seen the container.
     */
    @Nullable
    public BlockPos findSource(BlockPos anchor, BlockPos from, UUID self,
                               Predicate<ItemStack> predicate, long now, Set<BlockPos> exclude) {
        return records.keySet().stream()
                .filter(pos -> !exclude.contains(pos))
                .filter(pos -> pos.closerThan(anchor, NETWORK_RANGE))
                .filter(pos -> availableTo(pos, self, predicate, now) > 0)
                .min(Comparator.comparingDouble(pos -> pos.distSqr(from)))
                .orElse(null);
    }

    /**
     * Claims up to {@code wanted} matching items, resolved against the snapshot to concrete
     * item kinds so later availability math is exact. @return how many were actually reserved.
     */
    public int reserve(BlockPos pos, UUID holder, Predicate<ItemStack> predicate, int wanted, long now) {
        ContainerRecord record = records.get(pos);
        if (record == null) {
            return 0;
        }
        pruneExpired(record, now);
        int reserved = 0;
        for (ItemStack stack : record.contents) {
            if (reserved >= wanted) {
                break;
            }
            if (!predicate.test(stack)) {
                continue;
            }
            Predicate<ItemStack> sameKind = other -> ItemStack.isSameItemSameComponents(other, stack);
            int available = stack.getCount() - record.reserved(sameKind, null);
            int claim = Math.min(wanted - reserved, Math.max(0, available));
            if (claim > 0) {
                record.reservations.add(new Reservation(
                        holder, stack.copyWithCount(1), claim, now + RESERVATION_TTL));
                reserved += claim;
            }
        }
        if (reserved > 0) {
            setDirty();
        }
        return reserved;
    }

    public void release(BlockPos pos, UUID holder) {
        ContainerRecord record = records.get(pos);
        if (record != null && record.reservations.removeIf(r -> r.holder.equals(holder))) {
            setDirty();
        }
    }

    /** Called when a reserving task ends and when a villager dies; expiry is the backstop. */
    public void releaseAll(UUID holder) {
        boolean changed = false;
        for (ContainerRecord record : records.values()) {
            changed |= record.reservations.removeIf(r -> r.holder.equals(holder));
        }
        if (changed) {
            setDirty();
        }
    }

    /**
     * Where a hauler should put things down: public storage already holding a matching item
     * kind first (sorting emerges), then any public container with known or unknown room.
     * Unclaimed containers are the caller's fallback, not the network's business.
     */
    @Nullable
    public BlockPos findDepositTarget(BlockPos anchor, BlockPos from, UUID self,
                                      List<ItemStack> toStore, Set<BlockPos> exclude) {
        return records.entrySet().stream()
                .filter(e -> e.getValue().designation == Designation.PUBLIC)
                .filter(e -> !exclude.contains(e.getKey()))
                .filter(e -> e.getKey().closerThan(anchor, NETWORK_RANGE))
                .filter(e -> mayOpen(e.getKey(), self))
                // a known-full chest is only worth a walk if something might merge into it
                .filter(e -> e.getValue().freeSlots != 0 || e.getValue().containsMatching(toStore))
                .min(Comparator
                        .<Map.Entry<BlockPos, ContainerRecord>>comparingInt(
                                e -> e.getValue().containsMatching(toStore) ? 0 : 1)
                        .thenComparingDouble(e -> e.getKey().distSqr(from)))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    public boolean hasPublicDeposit(BlockPos anchor, UUID self) {
        return records.entrySet().stream()
                .anyMatch(e -> e.getValue().designation == Designation.PUBLIC
                        && e.getKey().closerThan(anchor, NETWORK_RANGE)
                        && mayOpen(e.getKey(), self));
    }

    /** All records near an anchor, nearest first — for debug commands and inspection. */
    public List<Map.Entry<BlockPos, ContainerRecord>> recordsNear(BlockPos anchor, int range) {
        return records.entrySet().stream()
                .filter(e -> e.getKey().closerThan(anchor, range))
                .sorted(Comparator.comparingDouble(e -> e.getKey().distSqr(anchor)))
                .toList();
    }

    public MaterialRequest addRequest(String filter, int count, BlockPos deliverTo, long now) {
        MaterialRequest request = new MaterialRequest(nextRequestId++, filter, deliverTo.immutable(), count, now);
        requests.add(request);
        setDirty();
        return request;
    }

    /** Open requests whose destination lies within range of the anchor, oldest first. */
    public List<MaterialRequest> openRequests(BlockPos anchor, int range) {
        return requests.stream()
                .filter(request -> request.deliverTo.closerThan(anchor, range))
                .toList();
    }

    public List<MaterialRequest> allRequests() {
        return Collections.unmodifiableList(requests);
    }

    public boolean cancelRequest(int id) {
        boolean removed = requests.removeIf(request -> request.id == id);
        if (removed) {
            setDirty();
        }
        return removed;
    }

    /** Credits a physical delivery against a request; the request dies when satisfied. */
    public void fulfillRequest(int id, int amount) {
        for (MaterialRequest request : requests) {
            if (request.id == id) {
                request.remaining -= amount;
                if (request.remaining <= 0) {
                    requests.remove(request);
                }
                setDirty();
                return;
            }
        }
    }

    /** Wipes everything — gametest isolation (the ledger outlives test arenas). */
    public void clear() {
        records.clear();
        requests.clear();
        setDirty();
    }

    private void pruneExpired(ContainerRecord record, long now) {
        if (record.reservations.removeIf(r -> r.expiry <= now)) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (Map.Entry<BlockPos, ContainerRecord> entry : records.entrySet()) {
            ContainerRecord record = entry.getValue();
            CompoundTag recordTag = new CompoundTag();
            recordTag.putLong("pos", entry.getKey().asLong());
            recordTag.putLong("inspected", record.lastInspected);
            recordTag.putInt("free_slots", record.freeSlots);
            recordTag.putString("designation", record.designation.name());
            if (record.owner != null) {
                recordTag.putUUID("owner", record.owner);
            }
            ListTag items = new ListTag();
            for (ItemStack stack : record.contents) {
                items.add(stack.save(provider));
            }
            recordTag.put("items", items);
            ListTag reservations = new ListTag();
            for (Reservation reservation : record.reservations) {
                CompoundTag reservationTag = new CompoundTag();
                reservationTag.putUUID("holder", reservation.holder);
                reservationTag.put("item", reservation.sample.save(provider));
                reservationTag.putInt("count", reservation.count);
                reservationTag.putLong("expiry", reservation.expiry);
                reservations.add(reservationTag);
            }
            recordTag.put("reservations", reservations);
            list.add(recordTag);
        }
        tag.put("containers", list);
        ListTag requestList = new ListTag();
        for (MaterialRequest request : requests) {
            CompoundTag requestTag = new CompoundTag();
            requestTag.putInt("id", request.id);
            requestTag.putString("filter", request.filter);
            requestTag.putLong("deliver_to", request.deliverTo.asLong());
            requestTag.putInt("remaining", request.remaining);
            requestTag.putLong("created", request.created);
            requestList.add(requestTag);
        }
        tag.put("requests", requestList);
        tag.putInt("next_request_id", nextRequestId);
        return tag;
    }

    private static StorageLedger load(CompoundTag tag, HolderLookup.Provider provider) {
        StorageLedger ledger = new StorageLedger();
        for (Tag entry : tag.getList("containers", Tag.TAG_COMPOUND)) {
            if (!(entry instanceof CompoundTag recordTag)) {
                continue;
            }
            ContainerRecord record = new ContainerRecord();
            record.lastInspected = recordTag.getLong("inspected");
            record.freeSlots = recordTag.getInt("free_slots");
            Designation designation = Designation.byName(recordTag.getString("designation"));
            record.designation = designation != null ? designation : Designation.UNCLAIMED;
            record.owner = recordTag.hasUUID("owner") ? recordTag.getUUID("owner") : null;
            for (Tag item : recordTag.getList("items", Tag.TAG_COMPOUND)) {
                ItemStack.parse(provider, item).ifPresent(record.contents::add);
            }
            for (Tag reservation : recordTag.getList("reservations", Tag.TAG_COMPOUND)) {
                if (reservation instanceof CompoundTag reservationTag) {
                    ItemStack.parse(provider, reservationTag.get("item")).ifPresent(sample ->
                            record.reservations.add(new Reservation(
                                    reservationTag.getUUID("holder"), sample,
                                    reservationTag.getInt("count"), reservationTag.getLong("expiry"))));
                }
            }
            ledger.records.put(BlockPos.of(recordTag.getLong("pos")), record);
        }
        for (Tag entry : tag.getList("requests", Tag.TAG_COMPOUND)) {
            if (entry instanceof CompoundTag requestTag) {
                ledger.requests.add(new MaterialRequest(
                        requestTag.getInt("id"), requestTag.getString("filter"),
                        BlockPos.of(requestTag.getLong("deliver_to")),
                        requestTag.getInt("remaining"), requestTag.getLong("created")));
            }
        }
        ledger.nextRequestId = Math.max(1, tag.getInt("next_request_id"));
        return ledger;
    }
}
