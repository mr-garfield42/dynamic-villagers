package com.dynamicvillagers.registry;

import com.dynamicvillagers.DynamicVillagers;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

public final class DVTags {

    /**
     * Blocks that count as village storage (default: chests and barrels). Phase 2 remembered
     * any Container block entity, which technically included furnaces and hoppers — stuffing
     * logs into a furnace's fuel slot is not "storage". Data packs extend this for modded
     * storage blocks.
     */
    public static final TagKey<Block> STORAGE_CONTAINERS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(DynamicVillagers.MOD_ID, "storage_containers"));

    private DVTags() {
    }
}
