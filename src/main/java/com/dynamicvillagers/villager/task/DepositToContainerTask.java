package com.dynamicvillagers.villager.task;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.work.ContainerUtil;
import com.dynamicvillagers.villager.work.ItemFilter;
import com.dynamicvillagers.villager.work.WorkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Walks to the nearest container the villager remembers and empties the combined inventory
 * (vanilla + extra slots) into it, except items matching the keep filters. The default keeps
 * food — villagers don't dump their lunch. Only remembered containers count — a villager that
 * has never seen a chest fails here.
 */
public class DepositToContainerTask implements Task {
    public static final String TYPE = "deposit_to_container";
    private static final int GIVE_UP_TICKS = 600;

    private final List<String> keepFilters;
    private final Predicate<ItemStack> keep;
    private final Set<BlockPos> skipped = new HashSet<>(); // walled-off chests; in-memory only
    @Nullable
    private BlockPos target;
    private int ticksRun;

    public DepositToContainerTask() {
        this(List.of("food"));
    }

    public DepositToContainerTask(List<String> keepFilters) {
        this(keepFilters, null);
    }

    private DepositToContainerTask(List<String> keepFilters, @Nullable BlockPos target) {
        this.keepFilters = List.copyOf(keepFilters);
        this.keep = ItemFilter.parseAny(this.keepFilters);
        this.target = target;
    }

    @Override
    public String typeId() {
        return TYPE;
    }

    @Override
    public Status tick(ServerLevel level, Villager villager) {
        VillagerEssence essence = VillagerEssence.get(villager);
        if (++ticksRun > GIVE_UP_TICKS) {
            return Status.FAILED;
        }
        if (target == null) {
            target = essence.getMemory().knownContainers().stream()
                    .filter(pos -> !skipped.contains(pos))
                    .min(Comparator.comparingDouble(pos -> pos.distSqr(villager.blockPosition())))
                    .orElse(null);
            if (target == null) {
                return Status.FAILED; // knows of no (reachable) container
            }
        }
        if (!WorkHelper.moveIntoReachAndLook(villager, target)) {
            return Status.IN_PROGRESS;
        }
        if (WorkHelper.findObstruction(level, villager, target) != null) {
            skipped.add(target); // can't see it, can't open it — walled off, try elsewhere
            target = null;
            return Status.IN_PROGRESS;
        }
        if (!(level.getBlockEntity(target) instanceof Container container)) {
            essence.getMemory().forgetContainer(target); // it's gone — stop believing in it
            target = null;
            return Status.IN_PROGRESS;
        }

        boolean movedAnything = depositFrom(villager.getInventory(), container)
                | depositFrom(essence.getExtraInventory(), container);
        if (movedAnything) {
            level.playSound(null, target, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5F, 1.0F);
        }
        return Status.DONE; // whatever didn't fit stays carried
    }

    private boolean depositFrom(SimpleContainer source, Container destination) {
        boolean movedAnything = false;
        for (int i = 0; i < source.getContainerSize(); i++) {
            ItemStack stack = source.getItem(i);
            if (!stack.isEmpty() && !keep.test(stack)) {
                int before = stack.getCount();
                ItemStack remainder = ContainerUtil.insert(destination, stack);
                source.setItem(i, remainder);
                movedAnything |= remainder.getCount() < before;
            }
        }
        return movedAnything;
    }

    @Override
    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        if (target != null) {
            tag.putLong("target", target.asLong());
        }
        ListTag keeps = new ListTag();
        for (String filter : keepFilters) {
            keeps.add(StringTag.valueOf(filter));
        }
        tag.put("keep", keeps);
        return tag;
    }

    public static Task load(CompoundTag tag, HolderLookup.Provider provider) {
        List<String> keeps = new ArrayList<>();
        for (Tag entry : tag.getList("keep", Tag.TAG_STRING)) {
            keeps.add(entry.getAsString());
        }
        return new DepositToContainerTask(keeps,
                tag.contains("target") ? BlockPos.of(tag.getLong("target")) : null);
    }
}
