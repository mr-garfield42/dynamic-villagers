package com.dynamicvillagers.villager.task;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Priority queue of tasks: lower priority number runs first, FIFO within a priority. */
public class TaskQueue {
    public static final int DEFAULT_PRIORITY = 0;

    private final List<Entry> entries = new ArrayList<>();

    private record Entry(int priority, Task task) {
    }

    public void enqueue(Task task) {
        enqueue(task, DEFAULT_PRIORITY);
    }

    public void enqueue(Task task, int priority) {
        int index = entries.size();
        while (index > 0 && entries.get(index - 1).priority() > priority) {
            index--;
        }
        entries.add(index, new Entry(priority, task));
    }

    @Nullable
    public Task current() {
        return entries.isEmpty() ? null : entries.getFirst().task();
    }

    public void popCurrent() {
        if (!entries.isEmpty()) {
            entries.removeFirst();
        }
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }

    public List<Task> tasks() {
        return entries.stream().map(Entry::task).toList();
    }

    public ListTag save(HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (Entry entry : entries) {
            CompoundTag tag = TaskTypes.save(entry.task(), provider);
            tag.putInt("queue_priority", entry.priority());
            list.add(tag);
        }
        return list;
    }

    public void load(ListTag list, HolderLookup.Provider provider) {
        entries.clear();
        for (Tag tag : list) {
            if (tag instanceof CompoundTag compound) {
                Task task = TaskTypes.load(compound, provider);
                if (task != null) {
                    entries.add(new Entry(compound.getInt("queue_priority"), task));
                }
            }
        }
    }
}
