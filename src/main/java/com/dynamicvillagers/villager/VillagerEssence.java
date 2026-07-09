package com.dynamicvillagers.villager;

import com.dynamicvillagers.registry.DVAttachments;
import com.dynamicvillagers.villager.task.TaskQueue;
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

    private int hunger = MAX_HUNGER;
    private final SimpleContainer extraInventory = new SimpleContainer(EXTRA_SLOTS);
    private final VillagerMemory memory = new VillagerMemory();
    private final TaskQueue taskQueue = new TaskQueue();

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
        tag.put("extra_inventory", extraInventory.createTag(provider));
        tag.put("memory_containers", memory.save());
        tag.put("tasks", taskQueue.save(provider));
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        setHunger(tag.getInt("hunger"));
        extraInventory.fromTag(tag.getList("extra_inventory", Tag.TAG_COMPOUND), provider);
        memory.load(tag.getList("memory_containers", Tag.TAG_COMPOUND));
        taskQueue.load(tag.getList("tasks", Tag.TAG_COMPOUND), provider);
    }
}
