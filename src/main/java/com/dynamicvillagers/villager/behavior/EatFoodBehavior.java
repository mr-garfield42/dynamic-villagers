package com.dynamicvillagers.villager.behavior;

import com.dynamicvillagers.villager.VillagerEssence;
import com.google.common.collect.ImmutableMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * Injected into every villager's CORE activity package (see VillagerGoalPackagesMixin).
 * Consumes one food item from the villager's own inventory when hungry; the item is spent
 * and hunger restored at start so an interruption can't dupe or void food.
 */
public class EatFoodBehavior extends Behavior<Villager> {
    public static final int HUNGER_THRESHOLD = 12;
    private static final int EAT_PAUSE_TICKS = 20;

    public EatFoodBehavior() {
        super(ImmutableMap.of(), EAT_PAUSE_TICKS);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Villager villager) {
        if (villager.isSleeping()) {
            return false;
        }
        VillagerEssence essence = VillagerEssence.get(villager);
        return essence.getHunger() < HUNGER_THRESHOLD && essence.findFoodSlot(villager) != null;
    }

    @Override
    protected void start(ServerLevel level, Villager villager, long gameTime) {
        VillagerEssence essence = VillagerEssence.get(villager);
        VillagerEssence.SlotRef slot = essence.findFoodSlot(villager);
        if (slot == null) {
            return;
        }
        ItemStack stack = slot.stack();
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null) {
            return;
        }
        essence.addHunger(food.nutrition());
        stack.shrink(1);
        slot.container().setChanged();
        villager.playSound(SoundEvents.GENERIC_EAT, 1.0F,
                0.9F + villager.getRandom().nextFloat() * 0.2F);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager villager, long gameTime) {
        return true; // hold for the brief eating pause; duration ends the behavior
    }
}
