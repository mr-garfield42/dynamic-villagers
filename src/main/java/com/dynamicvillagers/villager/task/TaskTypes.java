package com.dynamicvillagers.villager.task;

import com.dynamicvillagers.DynamicVillagers;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

public final class TaskTypes {
    private static final Map<String, BiFunction<CompoundTag, HolderLookup.Provider, Task>> LOADERS = new HashMap<>();

    static {
        register(GoToTask.TYPE, GoToTask::load);
        register(BreakBlockTask.TYPE, BreakBlockTask::load);
        register(PlaceBlockTask.TYPE, PlaceBlockTask::load);
        register(DepositToContainerTask.TYPE, DepositToContainerTask::load);
        register(PickUpItemsTask.TYPE, PickUpItemsTask::load);
        register(ChopTreeTask.TYPE, ChopTreeTask::load);
        register(TakeItemsTask.TYPE, TakeItemsTask::load);
        register(TillSoilTask.TYPE, TillSoilTask::load);
        register(DeliverItemsTask.TYPE, DeliverItemsTask::load);
        register(PlaceStateTask.TYPE, PlaceStateTask::load);
    }

    public static void register(String typeId, BiFunction<CompoundTag, HolderLookup.Provider, Task> loader) {
        LOADERS.put(typeId, loader);
    }

    public static CompoundTag save(Task task, HolderLookup.Provider provider) {
        CompoundTag tag = task.save(provider);
        tag.putString("type", task.typeId());
        return tag;
    }

    @Nullable
    public static Task load(CompoundTag tag, HolderLookup.Provider provider) {
        BiFunction<CompoundTag, HolderLookup.Provider, Task> loader = LOADERS.get(tag.getString("type"));
        if (loader == null) {
            DynamicVillagers.LOGGER.warn("Dropping task of unknown type '{}'", tag.getString("type"));
            return null;
        }
        try {
            return loader.apply(tag, provider);
        } catch (Exception e) {
            // one corrupt task must not take the whole villager attachment down with it —
            // that turns into "the entity silently fails to restore after a reload"
            DynamicVillagers.LOGGER.error("Dropping unloadable task of type '{}'", tag.getString("type"), e);
            return null;
        }
    }

    private TaskTypes() {
    }
}
