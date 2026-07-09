package com.dynamicvillagers.mixin;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.behavior.EatFoodBehavior;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla villagers only pick up profession-wanted items (bread, seeds, ...). A hungry
 * villager should grab any food it can carry; the vanilla pickup path in Mob#aiStep does
 * the rest once this returns true.
 */
@Mixin(Villager.class)
public abstract class VillagerMixin {

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
