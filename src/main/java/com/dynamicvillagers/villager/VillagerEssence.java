package com.dynamicvillagers.villager;

import com.dynamicvillagers.registry.DVAttachments;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.INBTSerializable;
import org.jetbrains.annotations.Nullable;

/**
 * All Phase 1 per-villager state, stored as a single data attachment on vanilla villagers.
 * The vanilla 8-slot inventory stays untouched (vanilla farming/breeding and Villager Overhaul
 * keep working); {@link #findFoodSlot} treats vanilla slots + extra slots as one inventory.
 */
public class VillagerEssence implements INBTSerializable<CompoundTag> {
    public static final int MAX_HUNGER = 20;
    public static final int EXTRA_SLOTS = 19; // + 8 vanilla slots = 27 total, one player inventory

    private int hunger = MAX_HUNGER;
    private final SimpleContainer extraInventory = new SimpleContainer(EXTRA_SLOTS);

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

    public record FoodSlot(SimpleContainer container, int slot) {
        public ItemStack stack() {
            return container.getItem(slot);
        }
    }

    @Nullable
    public FoodSlot findFoodSlot(Villager villager) {
        FoodSlot vanilla = scanForFood(villager.getInventory());
        return vanilla != null ? vanilla : scanForFood(extraInventory);
    }

    @Nullable
    private static FoodSlot scanForFood(SimpleContainer container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && stack.has(DataComponents.FOOD)) {
                return new FoodSlot(container, i);
            }
        }
        return null;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("hunger", hunger);
        tag.put("extra_inventory", extraInventory.createTag(provider));
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        setHunger(tag.getInt("hunger"));
        extraInventory.fromTag(tag.getList("extra_inventory", Tag.TAG_COMPOUND), provider);
    }
}
