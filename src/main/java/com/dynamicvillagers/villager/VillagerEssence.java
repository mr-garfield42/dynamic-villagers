package com.dynamicvillagers.villager;

import com.dynamicvillagers.registry.DVAttachments;
import com.dynamicvillagers.villager.role.VillagerRole;
import com.dynamicvillagers.villager.task.TaskQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.INBTSerializable;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * All Phase 1 per-villager state, stored as a single data attachment on vanilla villagers.
 * The vanilla 8-slot inventory stays untouched (vanilla farming/breeding and Villager Overhaul
 * keep working); the slot helpers treat vanilla slots + extra slots as one inventory.
 */
public class VillagerEssence implements INBTSerializable<CompoundTag> {
    public static final int MAX_HUNGER = 20;
    public static final int EXTRA_SLOTS = 19; // + 8 vanilla slots = 27 total, one player inventory

    /** A designated 1×2 strip-mine tunnel: dug block by block from start toward direction. */
    public record MineSite(BlockPos start, Direction direction) {
    }

    /** A designated quarry pit: the box spanned by two corners, dug layer by layer. */
    public record QuarrySite(BlockPos cornerA, BlockPos cornerB) {
    }

    private int hunger = MAX_HUNGER;
    private VillagerRole role = VillagerRole.NONE;
    @Nullable
    private MineSite mineSite;
    @Nullable
    private QuarrySite quarrySite;
    private int assignedSiteId = -1; // construction site this villager was told to work; -1 = none
    private final SimpleContainer extraInventory = new SimpleContainer(EXTRA_SLOTS);
    private final VillagerMemory memory = new VillagerMemory();
    private final TaskQueue taskQueue = new TaskQueue();

    // planner throttles; deliberately not serialized (replanning after reload is harmless)
    private long nextPlanTime;
    private long nextToolFetchTime;
    private long nextTorchFetchTime;

    public static VillagerEssence get(Villager villager) {
        return villager.getData(DVAttachments.VILLAGER_ESSENCE);
    }

    public int getHunger() {
        return hunger;
    }

    public void setHunger(int value) {
        this.hunger = Mth.clamp(value, 0, MAX_HUNGER);
    }

    public void addHunger(int amount) {
        setHunger(hunger + amount);
    }

    public VillagerRole getRole() {
        return role;
    }

    public void setRole(VillagerRole role) {
        this.role = role;
    }

    public long getNextPlanTime() {
        return nextPlanTime;
    }

    public void setNextPlanTime(long gameTime) {
        this.nextPlanTime = gameTime;
    }

    public long getNextToolFetchTime() {
        return nextToolFetchTime;
    }

    public void setNextToolFetchTime(long gameTime) {
        this.nextToolFetchTime = gameTime;
    }

    @Nullable
    public MineSite getMineSite() {
        return mineSite;
    }

    public void setMineSite(@Nullable MineSite mineSite) {
        this.mineSite = mineSite;
    }

    @Nullable
    public QuarrySite getQuarrySite() {
        return quarrySite;
    }

    public void setQuarrySite(@Nullable QuarrySite quarrySite) {
        this.quarrySite = quarrySite;
    }

    public int getAssignedSiteId() {
        return assignedSiteId;
    }

    public void setAssignedSiteId(int siteId) {
        this.assignedSiteId = siteId;
    }

    public long getNextTorchFetchTime() {
        return nextTorchFetchTime;
    }

    public void setNextTorchFetchTime(long gameTime) {
        this.nextTorchFetchTime = gameTime;
    }

    public SimpleContainer getExtraInventory() {
        return extraInventory;
    }

    public VillagerMemory getMemory() {
        return memory;
    }

    public TaskQueue getTaskQueue() {
        return taskQueue;
    }

    public record SlotRef(SimpleContainer container, int slot) {
        public ItemStack stack() {
            return container.getItem(slot);
        }
    }

    @Nullable
    public SlotRef findFoodSlot(Villager villager) {
        return findSlot(villager, stack -> stack.has(DataComponents.FOOD));
    }

    @Nullable
    public SlotRef findSlot(Villager villager, Predicate<ItemStack> predicate) {
        SlotRef vanilla = scan(villager.getInventory(), predicate);
        return vanilla != null ? vanilla : scan(extraInventory, predicate);
    }

    /** Best mining tool in the combined inventory, or null if bare hands are just as good. */
    @Nullable
    public SlotRef findBestTool(Villager villager, BlockState state) {
        SlotRef best = null;
        float bestSpeed = 1.0F; // bare hands
        for (SimpleContainer container : new SimpleContainer[]{villager.getInventory(), extraInventory}) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty()) {
                    float speed = stack.getDestroySpeed(state);
                    if (speed > bestSpeed) {
                        bestSpeed = speed;
                        best = new SlotRef(container, i);
                    }
                }
            }
        }
        return best;
    }

    public boolean hasItem(Villager villager, Predicate<ItemStack> predicate) {
        return findSlot(villager, predicate) != null;
    }

    public int countItems(Villager villager, Predicate<ItemStack> predicate) {
        int total = 0;
        for (SimpleContainer container : new SimpleContainer[]{villager.getInventory(), extraInventory}) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty() && predicate.test(stack)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    public int countEmptySlots(Villager villager) {
        int empty = 0;
        for (SimpleContainer container : new SimpleContainer[]{villager.getInventory(), extraInventory}) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (container.getItem(i).isEmpty()) {
                    empty++;
                }
            }
        }
        return empty;
    }

    @Nullable
    private static SlotRef scan(SimpleContainer container, Predicate<ItemStack> predicate) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && predicate.test(stack)) {
                return new SlotRef(container, i);
            }
        }
        return null;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("hunger", hunger);
        tag.putString("role", role.name());
        if (mineSite != null) {
            CompoundTag site = new CompoundTag();
            site.putLong("p", mineSite.start().asLong());
            site.putString("d", mineSite.direction().getName());
            tag.put("mine_site", site);
        }
        if (quarrySite != null) {
            CompoundTag site = new CompoundTag();
            site.putLong("a", quarrySite.cornerA().asLong());
            site.putLong("b", quarrySite.cornerB().asLong());
            tag.put("quarry_site", site);
        }
        tag.putInt("assigned_site", assignedSiteId);
        tag.put("extra_inventory", extraInventory.createTag(provider));
        tag.put("memory_containers", memory.save());
        tag.put("memory_spots", memory.saveSpots());
        tag.put("tasks", taskQueue.save(provider));
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        setHunger(tag.getInt("hunger"));
        VillagerRole loaded = VillagerRole.byName(tag.getString("role"));
        role = loaded != null ? loaded : VillagerRole.NONE;
        mineSite = null;
        if (tag.contains("mine_site")) {
            CompoundTag site = tag.getCompound("mine_site");
            Direction direction = Direction.byName(site.getString("d"));
            if (direction != null) {
                mineSite = new MineSite(BlockPos.of(site.getLong("p")), direction);
            }
        }
        quarrySite = null;
        if (tag.contains("quarry_site")) {
            CompoundTag site = tag.getCompound("quarry_site");
            quarrySite = new QuarrySite(BlockPos.of(site.getLong("a")), BlockPos.of(site.getLong("b")));
        }
        assignedSiteId = tag.contains("assigned_site") ? tag.getInt("assigned_site") : -1;
        extraInventory.fromTag(tag.getList("extra_inventory", Tag.TAG_COMPOUND), provider);
        memory.load(tag.getList("memory_containers", Tag.TAG_COMPOUND));
        memory.loadSpots(tag.getCompound("memory_spots"));
        taskQueue.load(tag.getList("tasks", Tag.TAG_COMPOUND), provider);
    }
}
