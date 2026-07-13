package com.dynamicvillagers.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;

/**
 * Where "the village" is, from one villager's point of view, until real village identity
 * arrives with the Village Manager (Phase 5): the bell it meets at, else its bed, else where
 * it stands. Used to distance-gate anything village-scoped (torch coverage, storage network).
 */
public final class VillageAnchor {

    public static BlockPos resolve(ServerLevel level, Villager villager) {
        Village home = VillageManager.get(level).getVillage(
                com.dynamicvillagers.villager.VillagerEssence.get(villager).getHomeVillageId());
        if (home != null) {
            return home.anchor();
        }
        return villager.getBrain().getMemory(MemoryModuleType.MEETING_POINT)
                .filter(globalPos -> globalPos.dimension() == level.dimension())
                .map(GlobalPos::pos)
                .or(() -> villager.getBrain().getMemory(MemoryModuleType.HOME)
                        .filter(globalPos -> globalPos.dimension() == level.dimension())
                        .map(GlobalPos::pos))
                .orElse(villager.blockPosition());
    }

    private VillageAnchor() {
    }
}
