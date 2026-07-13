package com.dynamicvillagers;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class VillageConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue SHOW_NAMEPLATES;
    public static final ModConfigSpec.BooleanValue AUTO_STAFF;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        SHOW_NAMEPLATES = builder.define("showVillagerNameplates", true);
        AUTO_STAFF = builder.define("autoStaffVillages", true);
        SPEC = builder.build();
    }

    private VillageConfig() {
    }
}
