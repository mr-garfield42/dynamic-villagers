package com.dynamicvillagers.villager.role;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * A Dynamic Villagers gathering role, independent of the vanilla profession (which keeps its
 * trades, brain schedule, and Guard Villagers/Thief interactions). Assigned by command or
 * debug GUI for now; the village manager takes over in Phase 5.
 */
public enum VillagerRole {
    NONE,
    LUMBERJACK,
    FARMER,
    MINER,
    BUILDER;

    @Nullable
    public static VillagerRole byName(String name) {
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String lowerName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
