package com.dynamicvillagers.villager.task;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.work.ContainerUtil;
import com.dynamicvillagers.villager.work.ItemFilter;
import com.dynamicvillagers.villager.work.WorkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Withdraws items matching a filter from remembered containers, visiting them nearest-first.
 * Knowing a chest exists is not knowing its contents (design rule #1), so the villager must
 * walk to each and look inside. Done once `count` matching items are carried; fails when all
 * known containers have been searched.
 */
public class TakeItemsTask implements Task {
    public static final String TYPE = "take_items";
    private static final int GIVE_UP_TICKS = 1200;

    private final String filter;
    private final int count;
    private final Predicate<ItemStack> predicate;
    private final Set<BlockPos> visited = new HashSet<>(); // in-memory only; a reload restarts the search
    @Nullable
    private BlockPos target;
    private int ticksRun;

    public TakeItemsTask(String filter, int count) {
        this.filter = filter;
        this.count = count;
        this.predicate = ItemFilter.parse(filter);
    }

    @Override
    public String typeId() {
        return TYPE;
    }

    @Override
    public Status tick(ServerLevel level, Villager villager) {
        VillagerEssence essence = VillagerEssence.get(villager);
        if (essence.countItems(villager, predicate) >= count) {
            return Status.DONE;
        }
        if (++ticksRun > GIVE_UP_TICKS) {
            return Status.FAILED;
        }
        if (target == null) {
            target = essence.getMemory().knownContainers().stream()
                    .filter(pos -> !visited.contains(pos))
                    .min(Comparator.comparingDouble(pos -> pos.distSqr(villager.blockPosition())))
                    .orElse(null);
            if (target == null) {
                return Status.FAILED; // searched everywhere it knows
            }
        }
        if (!WorkHelper.moveIntoReachAndLook(villager, target)) {
            return Status.IN_PROGRESS;
        }
        if (WorkHelper.findObstruction(level, villager, target) != null) {
            visited.add(target); // can't see it, can't open it — walled off, try elsewhere
            target = null;
            return Status.IN_PROGRESS;
        }
        if (!(level.getBlockEntity(target) instanceof Container container)) {
            essence.getMemory().forgetContainer(target);
            visited.add(target);
            target = null;
            return Status.IN_PROGRESS;
        }

        int needed = count - essence.countItems(villager, predicate);
        boolean tookAnything = false;
        for (int i = 0; i < container.getContainerSize() && needed > 0; i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && predicate.test(stack)) {
                ItemStack moving = stack.copyWithCount(Math.min(needed, stack.getCount()));
                int offered = moving.getCount();
                moving = ContainerUtil.insert(villager.getInventory(), moving);
                moving = ContainerUtil.insert(essence.getExtraInventory(), moving);
                int moved = offered - moving.getCount();
                if (moved > 0) {
                    stack.shrink(moved);
                    container.setChanged();
                    needed -= moved;
                    tookAnything = true;
                }
            }
        }
        if (tookAnything) {
            level.playSound(null, target, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5F, 1.0F);
        }
        visited.add(target);
        target = null;
        return needed <= 0 ? Status.DONE : Status.IN_PROGRESS;
    }

    @Override
    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putString("filter", filter);
        tag.putInt("count", count);
        return tag;
    }

    public static Task load(CompoundTag tag, HolderLookup.Provider provider) {
        return new TakeItemsTask(tag.getString("filter"), tag.getInt("count"));
    }
}
