package com.dynamicvillagers.villager;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a villager has personally learned about the world (design rule: villagers only know
 * information they have learned). Entries are added by PerceptionSystem when the villager is
 * near and can see something, and removed when the remembered thing turns out to be gone.
 */
public class VillagerMemory {
    public static final int MAX_CONTAINERS = 16;
    public static final int MAX_SPOTS_PER_KIND = 16;

    private final Map<BlockPos, Long> containers = new LinkedHashMap<>(); // pos -> last seen game time
    // named resource spots (e.g. "tree" bases) learned by perception, capped per kind
    private final Map<String, Map<BlockPos, Long>> spots = new LinkedHashMap<>();

    public void rememberContainer(BlockPos pos, long gameTime) {
        containers.put(pos.immutable(), gameTime);
        if (containers.size() > MAX_CONTAINERS) {
            containers.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .ifPresent(oldest -> containers.remove(oldest.getKey()));
        }
    }

    public void forgetContainer(BlockPos pos) {
        containers.remove(pos);
    }

    public Set<BlockPos> knownContainers() {
        return Collections.unmodifiableSet(containers.keySet());
    }

    @Nullable
    public BlockPos nearestContainer(BlockPos from) {
        return containers.keySet().stream()
                .min(Comparator.comparingDouble(pos -> pos.distSqr(from)))
                .orElse(null);
    }

    public void rememberSpot(String kind, BlockPos pos, long gameTime) {
        Map<BlockPos, Long> ofKind = spots.computeIfAbsent(kind, k -> new LinkedHashMap<>());
        ofKind.put(pos.immutable(), gameTime);
        if (ofKind.size() > MAX_SPOTS_PER_KIND) {
            ofKind.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .ifPresent(oldest -> ofKind.remove(oldest.getKey()));
        }
    }

    public void forgetSpot(String kind, BlockPos pos) {
        Map<BlockPos, Long> ofKind = spots.get(kind);
        if (ofKind != null) {
            ofKind.remove(pos);
            if (ofKind.isEmpty()) {
                spots.remove(kind);
            }
        }
    }

    public Set<BlockPos> knownSpots(String kind) {
        Map<BlockPos, Long> ofKind = spots.get(kind);
        return ofKind == null ? Collections.emptySet() : Collections.unmodifiableSet(ofKind.keySet());
    }

    public List<BlockPos> spotsByDistance(String kind, BlockPos from) {
        return knownSpots(kind).stream()
                .sorted(Comparator.comparingDouble(pos -> pos.distSqr(from)))
                .toList();
    }

    public CompoundTag saveSpots() {
        CompoundTag tag = new CompoundTag();
        for (Map.Entry<String, Map<BlockPos, Long>> kind : spots.entrySet()) {
            ListTag list = new ListTag();
            for (Map.Entry<BlockPos, Long> entry : kind.getValue().entrySet()) {
                CompoundTag spot = new CompoundTag();
                spot.putLong("p", entry.getKey().asLong());
                spot.putLong("t", entry.getValue());
                list.add(spot);
            }
            tag.put(kind.getKey(), list);
        }
        return tag;
    }

    public void loadSpots(CompoundTag tag) {
        spots.clear();
        for (String kind : tag.getAllKeys()) {
            for (Tag entry : tag.getList(kind, Tag.TAG_COMPOUND)) {
                if (entry instanceof CompoundTag spot) {
                    rememberSpot(kind, BlockPos.of(spot.getLong("p")), spot.getLong("t"));
                }
            }
        }
    }

    public ListTag save() {
        ListTag list = new ListTag();
        for (Map.Entry<BlockPos, Long> entry : containers.entrySet()) {
            CompoundTag tag = new CompoundTag();
            tag.putLong("p", entry.getKey().asLong());
            tag.putLong("t", entry.getValue());
            list.add(tag);
        }
        return list;
    }

    public void load(ListTag list) {
        containers.clear();
        for (Tag tag : list) {
            if (tag instanceof CompoundTag entry) {
                containers.put(BlockPos.of(entry.getLong("p")), entry.getLong("t"));
            }
        }
    }

    // for /dv inspect
    public List<BlockPos> containersByDistance(BlockPos from) {
        return containers.keySet().stream()
                .sorted(Comparator.comparingDouble(pos -> pos.distSqr(from)))
                .toList();
    }
}
