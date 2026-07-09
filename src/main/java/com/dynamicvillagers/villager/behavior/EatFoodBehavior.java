package com.dynamicvillagers.villager.behavior;

import com.dynamicvillagers.villager.VillagerEssence;
import com.google.common.collect.ImmutableMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * Injected into every villager's CORE activity (see VillagerMixin). Villagers top themselves
 * off like players: they eat from their own inventory whenever hunger is below full. The item
 * is spent and hunger restored at start so an interruption can't dupe or void food; the eat
 * pause afterwards plays chewing sounds and crumb particles.
 */
public class EatFoodBehavior extends Behavior<Villager> {
    private static final int EAT_PAUSE_TICKS = 24;

    private ItemStack eating = ItemStack.EMPTY;

    public EatFoodBehavior() {
        super(ImmutableMap.of(), EAT_PAUSE_TICKS);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Villager villager) {
        if (villager.isSleeping()) {
            return false;
        }
        VillagerEssence essence = VillagerEssence.get(villager);
        return essence.getHunger() < VillagerEssence.MAX_HUNGER && essence.findFoodSlot(villager) != null;
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
        eating = stack.copyWithCount(1);
        stack.shrink(1);
        slot.container().setChanged();
    }

    @Override
    protected void tick(ServerLevel level, Villager villager, long gameTime) {
        if (!eating.isEmpty() && villager.tickCount % 4 == 0) {
            level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, eating),
                    villager.getX(), villager.getEyeY() - 0.25, villager.getZ(),
                    4, 0.15, 0.1, 0.15, 0.05);
            villager.playSound(SoundEvents.GENERIC_EAT, 0.7F,
                    0.9F + villager.getRandom().nextFloat() * 0.2F);
        }
    }

    @Override
    protected void stop(ServerLevel level, Villager villager, long gameTime) {
        eating = ItemStack.EMPTY;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager villager, long gameTime) {
        return true; // hold for the eating pause; duration ends the behavior
    }
}
