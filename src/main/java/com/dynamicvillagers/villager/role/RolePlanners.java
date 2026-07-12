package com.dynamicvillagers.villager.role;

import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

public final class RolePlanners {
    private static final Map<VillagerRole, RolePlanner> PLANNERS = new EnumMap<>(VillagerRole.class);

    static {
        PLANNERS.put(VillagerRole.LUMBERJACK, new LumberjackPlanner());
        PLANNERS.put(VillagerRole.FARMER, new FarmerPlanner());
        PLANNERS.put(VillagerRole.MINER, new MinerPlanner());
        PLANNERS.put(VillagerRole.BUILDER, new BuilderPlanner());
        PLANNERS.put(VillagerRole.HUNTER, new HunterPlanner());
    }

    @Nullable
    public static RolePlanner get(VillagerRole role) {
        return PLANNERS.get(role);
    }

    private RolePlanners() {
    }
}
