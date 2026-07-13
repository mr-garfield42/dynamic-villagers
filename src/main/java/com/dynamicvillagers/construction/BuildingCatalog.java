package com.dynamicvillagers.construction;

import com.dynamicvillagers.DynamicVillagers;
import com.dynamicvillagers.village.ConstructionLedger;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class BuildingCatalog extends SimpleJsonResourceReloadListener {
    public record Entry(ResourceLocation templateId, ConstructionLedger.SiteType type, String biomeGroup) {
    }

    public static final BuildingCatalog INSTANCE = new BuildingCatalog();
    private volatile List<Entry> entries = List.of();

    private BuildingCatalog() {
        super(new Gson(), "building_catalog");
    }

    public List<Entry> entries(String biomeGroup, ConstructionLedger.SiteType type) {
        return entries.stream()
                .filter(entry -> entry.biomeGroup().equals(biomeGroup))
                .filter(entry -> entry.type() == type)
                .toList();
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager,
                         ProfilerFiller profiler) {
        List<Entry> loaded = new ArrayList<>();
        for (JsonElement file : files.values()) {
            for (JsonElement value : file.getAsJsonArray()) {
                JsonObject object = value.getAsJsonObject();
                ResourceLocation template = ResourceLocation.tryParse(GsonHelper.getAsString(object, "template"));
                ConstructionLedger.SiteType type;
                try {
                    type = ConstructionLedger.SiteType.valueOf(
                            GsonHelper.getAsString(object, "type").toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (template != null) {
                    loaded.add(new Entry(template, type, GsonHelper.getAsString(object, "biome")));
                }
            }
        }
        entries = List.copyOf(loaded);
        DynamicVillagers.LOGGER.info("Loaded {} village building catalog entries", entries.size());
    }
}
