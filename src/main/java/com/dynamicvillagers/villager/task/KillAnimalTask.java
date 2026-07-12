package com.dynamicvillagers.villager.task;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.work.ItemFilter;
import com.dynamicvillagers.villager.work.WorkHelper;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Hunts a single animal to death, physically: walk into arm's reach, swing on a cooldown, and
 * deal the villager's melee damage (a carried sword hits harder and wears down, bare hands are
 * weaker — the same tool rules the miner's pickaxe follows). Done when the target is dead or
 * gone; the planner picks up the meat drops on a following cycle. Only ever aimed at adult food
 * animals by {@link com.dynamicvillagers.villager.role.HunterPlanner} — this task itself just
 * kills whatever id it was handed.
 */
public class KillAnimalTask implements Task {
    public static final String TYPE = "kill_animal";
    private static final int GIVE_UP_TICKS = 1200; // a fleeing animal must not stall the queue forever
    private static final int ATTACK_INTERVAL = 16;  // ticks between swings, just under vanilla's 20
    private static final double MELEE_RANGE = 2.2;   // reach to the animal's centre
    private static final float BASE_DAMAGE = 3.0F;   // an unarmed strike
    private static final float SWORD_DAMAGE = 7.0F;  // with a sword in hand

    private final UUID targetId;
    private int ticksRun;
    private int attackCooldown;

    public KillAnimalTask(UUID targetId) {
        this.targetId = targetId;
    }

    @Override
    public String typeId() {
        return TYPE;
    }

    @Override
    public Status tick(ServerLevel level, Villager villager) {
        if (++ticksRun > GIVE_UP_TICKS) {
            return Status.FAILED;
        }
        Entity entity = level.getEntity(targetId);
        if (!(entity instanceof Animal animal) || !animal.isAlive()) {
            return Status.DONE; // killed, or despawned/moved out of the world — either way we're done
        }
        if (attackCooldown > 0) {
            attackCooldown--;
        }
        if (villager.distanceTo(animal) > MELEE_RANGE) {
            // every tick, so idle strolls can't hijack a cleared walk target mid-chase
            BehaviorUtils.setWalkAndLookTargetMemories(villager, animal, WorkHelper.WALK_SPEED, 1);
            return Status.IN_PROGRESS;
        }
        villager.getLookControl().setLookAt(animal);
        if (attackCooldown <= 0) {
            strike(level, villager, animal);
            attackCooldown = ATTACK_INTERVAL;
        }
        return Status.IN_PROGRESS;
    }

    private void strike(ServerLevel level, Villager villager, Animal animal) {
        VillagerEssence essence = VillagerEssence.get(villager);
        VillagerEssence.SlotRef sword = essence.findSlot(villager, ItemFilter.parse("sword"));
        float damage = sword != null ? SWORD_DAMAGE : BASE_DAMAGE;
        villager.swing(InteractionHand.MAIN_HAND);
        animal.hurt(level.damageSources().mobAttack(villager), damage);
        if (sword != null) {
            ItemStack blade = sword.stack();
            blade.hurtAndBreak(1, level, villager, broken -> {}); // tools wear down like a player's
        }
    }

    @Override
    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("target", targetId);
        return tag;
    }

    public static Task load(CompoundTag tag, HolderLookup.Provider provider) {
        return new KillAnimalTask(tag.getUUID("target"));
    }
}
