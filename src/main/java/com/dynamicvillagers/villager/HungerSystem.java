package com.dynamicvillagers.villager;

import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

public final class HungerSystem {
    // 1 hunger point per minute: a full bar lasts one Minecraft day (24000 ticks).
    public static final int DECAY_INTERVAL_TICKS = 1200;

    public static void onEntityTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof Villager villager
                && !villager.level().isClientSide
                && villager.isAlive()
                && villager.tickCount > 0
                && villager.tickCount % DECAY_INTERVAL_TICKS == 0) {
            applyDecay(villager);
        }
    }

    public static void applyDecay(Villager villager) {
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.addHunger(-1);
    }

    private HungerSystem() {
    }
}
