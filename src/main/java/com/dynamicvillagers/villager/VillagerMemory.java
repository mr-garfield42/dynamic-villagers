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

    private final Map<BlockPos, Long> containers = new LinkedHashMap<>(); // pos -> last seen game time

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
