package com.dynamicvillagers.villager.task;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.work.ContainerUtil;
import com.dynamicvillagers.villager.work.WorkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Walks to the nearest container the villager remembers and empties the extra inventory into
 * it. Only remembered containers count — a villager that has never seen a chest fails here.
 */
public class DepositToContainerTask implements Task {
    public static final String TYPE = "deposit_to_container";
    private static final int GIVE_UP_TICKS = 600;

    @Nullable
    private BlockPos target;
    private int ticksRun;

    public DepositToContainerTask() {
    }

    private DepositToContainerTask(@Nullable BlockPos target) {
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
            target = essence.getMemory().nearestContainer(villager.blockPosition());
            if (target == null) {
                return Status.FAILED; // knows of no container
            }
        }
        if (!WorkHelper.moveIntoReachAndLook(villager, target)) {
            return Status.IN_PROGRESS;
        }
        if (!(level.getBlockEntity(target) instanceof Container container)) {
            essence.getMemory().forgetContainer(target); // it's gone — stop believing in it
            target = null;
            return Status.IN_PROGRESS;
        }

        SimpleContainer extra = essence.getExtraInventory();
        boolean movedAnything = false;
        for (int i = 0; i < extra.getContainerSize(); i++) {
            ItemStack stack = extra.getItem(i);
            if (!stack.isEmpty()) {
                int before = stack.getCount();
                ItemStack remainder = ContainerUtil.insert(container, stack);
                extra.setItem(i, remainder);
                movedAnything |= remainder.getCount() < before;
            }
        }
        if (movedAnything) {
            level.playSound(null, target, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5F, 1.0F);
        }
        return Status.DONE; // whatever didn't fit stays carried
    }

    @Override
    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        if (target != null) {
            tag.putLong("target", target.asLong());
        }
        return tag;
    }

    public static Task load(CompoundTag tag, HolderLookup.Provider provider) {
        return new DepositToContainerTask(tag.contains("target") ? BlockPos.of(tag.getLong("target")) : null);
    }
}
