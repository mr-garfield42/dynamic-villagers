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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Collects dropped items within a radius of an anchor position into the villager's combined
 * inventory (vanilla slots first, then extra slots). Done when nothing collectible remains;
 * fails when the inventory is full.
 *
 * Two rules keep this from wasting the workday (owner report 2026-07-10: a lumberjack stood
 * under its felled tree for a minute "waiting for items"): only items present during the
 * first few seconds are chased — leaf decay drips saplings for a minute-plus, and those are
 * gleaned opportunistically by passing villagers instead — and an item the villager can't
 * get close to (stuck on top of the canopy) is skipped rather than stared at.
 */
public class PickUpItemsTask implements Task {
    public static final String TYPE = "pick_up_items";
    private static final int GIVE_UP_TICKS = 1200;
    private static final double PICKUP_DISTANCE = 2.0;
    private static final int NEW_ITEM_WINDOW_TICKS = 100;
    private static final int TARGET_STALL_TICKS = 100;

    private final BlockPos anchor;
    private final double radius;
    private int ticksRun;
    private int targetTicks;
    @Nullable
    private ItemEntity target;
    private final Set<Integer> unreachable = new HashSet<>(); // entity ids; in-memory only
    @Nullable
    private Set<Integer> eligible; // snapshot after the window; null = everything qualifies

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
        if (ticksRun == NEW_ITEM_WINDOW_TICKS) {
            // whatever has dropped by now is the haul; later leaf-decay drips are not waited on
            eligible = itemsInRange(level).stream().map(ItemEntity::getId).collect(Collectors.toSet());
        }
        if (target == null || !target.isAlive()) {
            target = findNearestItem(level, villager);
            targetTicks = 0;
            if (target == null) {
                return Status.DONE; // nothing (collectible) left
            }
        }
        if (villager.distanceTo(target) > PICKUP_DISTANCE) {
            if (++targetTicks > TARGET_STALL_TICKS) {
                unreachable.add(target.getId()); // can't get to it — stop staring at the canopy
                target = null;
                return Status.IN_PROGRESS;
            }
            // every tick, so idle strolls can't hijack a cleared walk target mid-collection
            BehaviorUtils.setWalkAndLookTargetMemories(villager, target, 0.6F, 1);
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
        return itemsInRange(level).stream()
                .filter(item -> !unreachable.contains(item.getId()))
                .filter(item -> eligible == null || eligible.contains(item.getId()))
                .min(Comparator.comparingDouble(villager::distanceToSqr))
                .orElse(null);
    }

    private List<ItemEntity> itemsInRange(ServerLevel level) {
        AABB area = new AABB(anchor).inflate(radius, 4.0, radius);
        return level.getEntitiesOfClass(ItemEntity.class, area, ItemEntity::isAlive);
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
