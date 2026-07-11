package com.dynamicvillagers.mixin;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.behavior.EatFoodBehavior;
import com.dynamicvillagers.villager.behavior.ExecuteTaskBehavior;
import com.dynamicvillagers.villager.behavior.PlanWorkBehavior;
import com.dynamicvillagers.villager.behavior.SeekFoodItemBehavior;
import com.dynamicvillagers.villager.behavior.SeekJobSiteBehavior;
import com.dynamicvillagers.villager.role.RoleProfessions;
import com.dynamicvillagers.villager.role.VillagerRole;
import com.google.common.collect.ImmutableList;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
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
        // SeekJobSiteBehavior first so it lands at CORE priority 5, ahead of vanilla's
        // AcquirePoi at 6 — our specific-jobsite claim wins over vanilla's any-job grab.
        brain.addActivity(Activity.CORE, 5, ImmutableList.of(
                new SeekJobSiteBehavior(),
                new EatFoodBehavior(),
                new SeekFoodItemBehavior(),
                new PlanWorkBehavior(),
                new ExecuteTaskBehavior()));
    }

    /**
     * Reverse of the role→profession bridge: mirror a profession change back into the mod
     * role so a villager that claims a mapped job site (composter → farmer, ...) becomes the
     * matching DV worker, and one that loses its profession drops the role. {@code
     * setVillagerData} is the single funnel vanilla routes every profession change through
     * (AssignProfessionFromJobSite on gain, ResetProfession on loss) — NeoForge 21.1.235 has
     * no profession-change event, so this HEAD inject is the reliable hook. At HEAD the
     * villager still holds the OLD data, so we compare against the incoming value.
     * Unmapped professions (librarian, ...) leave the role untouched — the transition through
     * NONE already cleared it. See docs/RESEARCH_PROFESSIONS.md.
     */
    @Inject(method = "setVillagerData", at = @At("HEAD"))
    private void dynamicvillagers$syncRoleFromProfession(VillagerData data, CallbackInfo ci) {
        Villager self = (Villager) (Object) this;
        if (self.level().isClientSide) {
            return; // entityData syncs to the client too; the role lives server-side only
        }
        VillagerProfession oldProfession = self.getVillagerData().getProfession();
        VillagerProfession newProfession = data.getProfession();
        if (oldProfession == newProfession) {
            return; // level-up or type change with the profession unchanged — not our concern
        }
        VillagerRole mapped = RoleProfessions.roleFor(newProfession);
        if (mapped != null && VillagerEssence.get(self).getRole() != mapped) {
            VillagerEssence.get(self).setRole(mapped);
        }
    }

    /**
     * Vanilla villagers only pick up profession-wanted items (bread, seeds, ...). Villagers
     * with a Dynamic Villagers role accept anything they have room for, like a player would
     * — the vanilla pickup path in Mob#aiStep does the rest once this returns true. They
     * still only *walk toward* dropped food (SeekFoodItemBehavior); other items are grabbed
     * only when already within arm's reach. Role-less villagers keep pure vanilla behavior:
     * socializers strolling past a work site must not pocket the workers' drops (owner
     * playtest, 2026-07-10).
     */
    @Inject(method = "wantsToPickUp", at = @At("RETURN"), cancellable = true)
    private void dynamicvillagers$wantsAnyCarriableItem(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            return;
        }
        Villager self = (Villager) (Object) this;
        if (VillagerEssence.get(self).getRole() != VillagerRole.NONE
                && self.getInventory().canAddItem(stack)) {
            cir.setReturnValue(true);
        }
    }
}
