package com.dynamicvillagers.villager.behavior;

import com.dynamicvillagers.villager.VillagerEssence;
import com.google.common.collect.ImmutableMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;

/**
 * Hungry villagers with no food in their inventory walk to nearby dropped food. The actual
 * pickup happens through the vanilla pickup path (see VillagerMixin extending wantsToPickUp),
 * and eating through EatFoodBehavior — this behavior only closes the distance.
 */
public class SeekFoodItemBehavior extends Behavior<Villager> {
    /** Walking somewhere for food is only worth it when actually hungry (topping off is not). */
    public static final int SEEK_HUNGER_THRESHOLD = 12;
    private static final double SCAN_RADIUS = 16.0;
    private static final int SCAN_INTERVAL_TICKS = 40;
    private static final int MAX_DURATION = 400;
    private static final float WALK_SPEED = 0.6F;

    @Nullable
    private ItemEntity target;

    public SeekFoodItemBehavior() {
        super(ImmutableMap.of(), MAX_DURATION);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Villager villager) {
        if (villager.isSleeping()) {
            return false;
        }
        VillagerEssence essence = VillagerEssence.get(villager);
        if (essence.getHunger() >= SEEK_HUNGER_THRESHOLD
                || essence.findFoodSlot(villager) != null
                || !essence.getTaskQueue().isEmpty() // assigned work takes precedence over foraging
                || villager.tickCount % SCAN_INTERVAL_TICKS != 0) {
            return false;
        }
        target = findNearestFood(level, villager);
        return target != null;
    }

    /** Public for gametests: the gate that keeps villagers from chasing non-food drops. */
    @Nullable
    public static ItemEntity findNearestFood(ServerLevel level, Villager villager) {
        return level.getEntitiesOfClass(ItemEntity.class,
                        villager.getBoundingBox().inflate(SCAN_RADIUS, 8.0, SCAN_RADIUS),
                        item -> item.isAlive() && item.getItem().has(DataComponents.FOOD))
                .stream()
                .min(Comparator.comparingDouble(villager::distanceToSqr))
                .orElse(null);
    }

    @Override
    protected void start(ServerLevel level, Villager villager, long gameTime) {
        walkToTarget(villager);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager villager, long gameTime) {
        return target != null && target.isAlive() && !villager.isSleeping()
                && VillagerEssence.get(villager).getHunger() < SEEK_HUNGER_THRESHOLD;
    }

    @Override
    protected void tick(ServerLevel level, Villager villager, long gameTime) {
        walkToTarget(villager); // every tick: keeps idle strolls from hijacking the approach
    }

    @Override
    protected void stop(ServerLevel level, Villager villager, long gameTime) {
        target = null;
    }

    private void walkToTarget(Villager villager) {
        if (target != null) {
            BehaviorUtils.setWalkAndLookTargetMemories(villager, target, WALK_SPEED, 1);
        }
    }
}
