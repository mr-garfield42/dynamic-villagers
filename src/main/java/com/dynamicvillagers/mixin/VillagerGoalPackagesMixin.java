package com.dynamicvillagers.mixin;

import com.dynamicvillagers.villager.behavior.EatFoodBehavior;
import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.VillagerGoalPackages;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Appends Dynamic Villagers behaviors to the vanilla villager Brain packages. Injecting at
 * the package factories (rather than mutating a live Brain) survives brain rebuilds on
 * profession change and composes with Guard Villagers' identical pattern.
 */
@Mixin(VillagerGoalPackages.class)
public abstract class VillagerGoalPackagesMixin {

    @Inject(method = "getCorePackage", cancellable = true, at = @At("RETURN"))
    private static void dynamicvillagers$addCoreBehaviors(VillagerProfession profession, float speedModifier,
            CallbackInfoReturnable<ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>>> cir) {
        List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> behaviors = new ArrayList<>(cir.getReturnValue());
        behaviors.add(Pair.of(5, new EatFoodBehavior()));
        cir.setReturnValue(ImmutableList.copyOf(behaviors));
    }
}
