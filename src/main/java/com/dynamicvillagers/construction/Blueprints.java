package com.dynamicvillagers.construction;

import com.dynamicvillagers.DynamicVillagers;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and caches parsed blueprints. Templates come from the vanilla structure manager
 * (any {@code data/<ns>/structure/*.nbt}, ours or a data pack's); since
 * {@code StructureTemplate}'s palettes are private, reading goes through the public
 * {@code save()} NBT round-trip (see RESEARCH.md). The cache is cleared on datapack reload.
 */
public final class Blueprints {
    private static final Map<ResourceLocation, Blueprint> CACHE = new ConcurrentHashMap<>();

    /** @return the parsed blueprint, or null when no such template exists or it won't parse. */
    public static Blueprint load(ServerLevel level, ResourceLocation id) {
        return CACHE.computeIfAbsent(id, key -> {
            Optional<StructureTemplate> template = level.getServer().getStructureManager().get(key);
            if (template.isEmpty()) {
                return null;
            }
            try {
                CompoundTag tag = template.get().save(new CompoundTag());
                return Blueprint.parse(key, tag, level.holderLookup(Registries.BLOCK));
            } catch (Exception e) {
                DynamicVillagers.LOGGER.error("Blueprint {} failed to parse", key, e);
                return null;
            }
        });
    }

    /** Datapack reloads can change templates; drop the parse results with them. */
    public static void clearCache() {
        CACHE.clear();
    }

    private Blueprints() {
    }
}
