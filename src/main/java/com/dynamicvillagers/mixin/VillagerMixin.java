package com.dynamicvillagers.mixin;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.behavior.EatFoodBehavior;
import com.dynamicvillagers.villager.behavior.ExecuteTaskBehavior;
import com.dynamicvillagers.villager.behavior.SeekFoodItemBehavior;
import com.google.common.collect.ImmutableList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Villager.class)
public abstract class VillagerMixin {

    /**
     * Adds our behaviors to the CORE activity at the end of every brain build (spawn and
     * refreshBrain). Injecting here instead of into VillagerGoalPackages avoids the shared
     * RETURN injection point that Guard Villagers cancels — its cancellable setReturnValue
     * short-circuits any other mod's handler at that point. Brain#addActivity appends, so
     * everyone else's behaviors are preserved.
     */
    @Inject(method = "registerBrainGoals", at = @At("TAIL"))
    private void dynamicvillagers$addCoreBehaviors(Brain<Villager> brain, CallbackInfo ci) {
        brain.addActivity(Activity.CORE, 5, ImmutableList.of(
                new EatFoodBehavior(),
                new SeekFoodItemBehavior(),
                new ExecuteTaskBehavior()));
    }

    /**
     * Vanilla villagers only pick up profession-wanted items (bread, seeds, ...). A hungry
     * villager should grab any food it can carry; the vanilla pickup path in Mob#aiStep does
     * the rest once this returns true.
     */

    @Inject(method = "wantsToPickUp", at = @At("RETURN"), cancellable = true)
    private void dynamicvillagers$wantsAnyFoodWhenHungry(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            return;
        }
        Villager self = (Villager) (Object) this;
        if (stack.has(DataComponents.FOOD)
                && VillagerEssence.get(self).getHunger() < EatFoodBehavior.HUNGER_THRESHOLD
                && self.getInventory().canAddItem(stack)) {
            cir.setReturnValue(true);
        }
    }
}
