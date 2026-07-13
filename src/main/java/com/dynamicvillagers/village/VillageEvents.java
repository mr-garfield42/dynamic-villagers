package com.dynamicvillagers.village;

import com.dynamicvillagers.VillageConfig;
import com.dynamicvillagers.villager.VillagerEssence;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

public final class VillageEvents {
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            VillageManager.get(level).tick(level);
        }
    }

    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (VillageManager.isGuard(event.getEntity()) && !event.getEntity().hasCustomName()) {
            event.getEntity().setCustomName(net.minecraft.network.chat.Component.literal(
                    Names.villager(event.getEntity().getUUID())));
            event.getEntity().setCustomNameVisible(VillageConfig.SHOW_NAMEPLATES.get());
        }
        if (!(event.getEntity() instanceof Villager villager)) return;
        VillagerEssence essence = VillagerEssence.get(villager);
        if (essence.getGeneratedName() == null) essence.setGeneratedName(Names.villager(villager.getUUID()));
        if (VillageConfig.SHOW_NAMEPLATES.get() && !villager.hasCustomName()) {
            villager.setCustomName(net.minecraft.network.chat.Component.literal(essence.getGeneratedName()));
            villager.setCustomNameVisible(true);
        }
        if (villager.isBaby() && essence.getHomeVillageId() == -1) {
            VillageManager manager = VillageManager.get(level);
            Village village = manager.nearestVillage(villager.blockPosition(), Village.DEFAULT_RADIUS);
            if (village != null) manager.adopt(level, villager, village);
        }
    }

    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (event.getSpawnType() != MobSpawnType.BREEDING
                || !(event.getEntity() instanceof Villager villager)
                || !(event.getLevel() instanceof ServerLevel level)) return;
        VillageManager manager = VillageManager.get(level);
        Village village = manager.nearestVillage(villager.blockPosition(), Village.DEFAULT_RADIUS);
        if (village == null) return;
        manager.refreshTallies(level, village);
        if (village.beds() <= village.population()) event.setSpawnCancelled(true);
    }

    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Villager villager && villager.level() instanceof ServerLevel level) {
            VillageManager.get(level).removeMember(villager.getUUID());
        }
    }

    private VillageEvents() {
    }
}
