package com.dynamicvillagers.villager.task;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.work.ContainerUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;

/**
 * Collects all dropped items within a radius of an anchor position into the villager's
 * combined inventory (vanilla slots first, then extra slots). Done when no items remain;
 * fails when the inventory is full or collection stalls.
 */
public class PickUpItemsTask implements Task {
    public static final String TYPE = "pick_up_items";
    private static final int GIVE_UP_TICKS = 1200;
    private static final double PICKUP_DISTANCE = 2.0;

    private final BlockPos anchor;
    private final double radius;
    private int ticksRun;
    @Nullable
    private ItemEntity target;

    public PickUpItemsTask(BlockPos anchor, double radius) {
        this.anchor = anchor;
        this.radius = radius;
    }

    @Override
    public String typeId() {
        return TYPE;
    }

    @Override
    public Status tick(ServerLevel level, Villager villager) {
        if (++ticksRun > GIVE_UP_TICKS) {
            return Status.FAILED;
        }
        if (target == null || !target.isAlive()) {
            target = findNearestItem(level, villager);
            if (target == null) {
                return Status.DONE; // nothing left to collect
            }
        }
        if (villager.distanceTo(target) > PICKUP_DISTANCE) {
            if (ticksRun % 20 == 1) {
                BehaviorUtils.setWalkAndLookTargetMemories(villager, target, 0.6F, 1);
            }
            return Status.IN_PROGRESS;
        }

        VillagerEssence essence = VillagerEssence.get(villager);
        ItemStack stack = target.getItem().copy();
        int before = stack.getCount();
        stack = ContainerUtil.insert(villager.getInventory(), stack);
        stack = ContainerUtil.insert(essence.getExtraInventory(), stack);
        if (stack.getCount() == before) {
            return Status.FAILED; // inventory full
        }
        if (stack.isEmpty()) {
            target.discard();
        } else {
            target.setItem(stack);
        }
        level.playSound(null, villager.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL,
                0.2F, ((villager.getRandom().nextFloat() - villager.getRandom().nextFloat()) * 0.7F + 1.0F) * 2.0F);
        target = null;
        return Status.IN_PROGRESS;
    }

    @Nullable
    private ItemEntity findNearestItem(ServerLevel level, Villager villager) {
        AABB area = new AABB(anchor).inflate(radius, 4.0, radius);
        return level.getEntitiesOfClass(ItemEntity.class, area, ItemEntity::isAlive)
                .stream()
                .min(Comparator.comparingDouble(villager::distanceToSqr))
                .orElse(null);
    }

    @Override
    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("anchor", anchor.asLong());
        tag.putDouble("radius", radius);
        return tag;
    }

    public static Task load(CompoundTag tag, HolderLookup.Provider provider) {
        return new PickUpItemsTask(BlockPos.of(tag.getLong("anchor")), tag.getDouble("radius"));
    }
}
