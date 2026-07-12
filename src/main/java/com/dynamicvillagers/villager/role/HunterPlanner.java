package com.dynamicvillagers.villager.role;

import com.dynamicvillagers.village.StorageLedger;
import com.dynamicvillagers.village.VillageAnchor;
import com.dynamicvillagers.villager.PerceptionSystem;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.task.CookAtCampfireTask;
import com.dynamicvillagers.villager.task.DepositToContainerTask;
import com.dynamicvillagers.villager.task.KillAnimalTask;
import com.dynamicvillagers.villager.task.PickUpItemsTask;
import com.dynamicvillagers.villager.task.TakeItemsTask;
import com.dynamicvillagers.villager.task.TaskQueue;
import com.dynamicvillagers.villager.work.ItemFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The hunter (owner request; wears the vanilla butcher skin via {@link RoleProfessions}). One
 * work cycle: cook any raw meat it carries on a nearby lit campfire, haul the surplus food to
 * storage when full, gather the drops from a fresh kill, or go hunt the nearest adult food
 * animal — bare-handed, or with a sword it fetched. Selling the food to other villagers waits
 * for the Phase 6 economy (owner note); this is the kill → cook → deposit loop.
 */
public class HunterPlanner implements RolePlanner {
    private static final List<String> KEEP_ON_DEPOSIT = List.of("sword"); // deposit the meat, keep the blade
    private static final Set<EntityType<?>> HUNTABLE = Set.of(
            EntityType.COW, EntityType.PIG, EntityType.CHICKEN, EntityType.SHEEP, EntityType.RABBIT);
    private static final Set<net.minecraft.world.item.Item> RAW_MEATS = Set.of(
            Items.BEEF, Items.PORKCHOP, Items.CHICKEN, Items.MUTTON, Items.RABBIT);
    private static final int MIN_FREE_SLOTS = 4;
    private static final int HAUL_THRESHOLD = 16;
    private static final int HUNT_RADIUS = 16;
    private static final int HUNT_HEIGHT = 6;
    private static final int CAMPFIRE_RADIUS = 12;
    private static final double PICKUP_RADIUS = 6.0;
    private static final double DROP_SCAN_RADIUS = 10.0;
    private static final int FETCH_COOLDOWN = 1200;

    @Override
    public boolean plan(ServerLevel level, Villager villager, VillagerEssence essence) {
        TaskQueue queue = essence.getTaskQueue();

        // cook first, so what reaches storage is cooked meat — worth more and ready to eat.
        // Falls through when no campfire is reachable: the raw meat is deposited as-is.
        if (carriesRawMeat(villager, essence)) {
            BlockPos fire = findLitCampfire(level, villager);
            if (fire != null) {
                queue.enqueue(new CookAtCampfireTask(fire));
                return true;
            }
        }

        Predicate<ItemStack> keep = ItemFilter.parseAny(KEEP_ON_DEPOSIT);
        boolean haulReady = essence.countEmptySlots(villager) < MIN_FREE_SLOTS
                || essence.countItems(villager, stack -> !keep.test(stack)) >= HAUL_THRESHOLD;
        boolean knowsStorage = essence.getMemory().nearestContainer(villager.blockPosition()) != null
                || StorageLedger.get(level).hasPublicDeposit(
                        VillageAnchor.resolve(level, villager), villager.getUUID());
        if (haulReady && knowsStorage) {
            if (!RequestChore.planCarriedDelivery(level, villager, essence, KEEP_ON_DEPOSIT)) {
                queue.enqueue(new DepositToContainerTask(KEEP_ON_DEPOSIT));
            }
            return true;
        }
        if (essence.countEmptySlots(villager) < MIN_FREE_SLOTS) {
            return false; // full and nowhere to store — stop hunting until that changes
        }

        // pick up the meat from a recent kill before wandering off after the next animal
        BlockPos drop = nearestFoodDrop(level, villager);
        if (drop != null) {
            queue.enqueue(new PickUpItemsTask(drop, PICKUP_RADIUS));
            return true;
        }

        Animal animal = findHuntable(level, villager);
        if (animal != null) {
            long now = level.getGameTime();
            if (!essence.hasItem(villager, ItemFilter.parse("sword"))
                    && !essence.getMemory().knownContainers().isEmpty()
                    && now >= essence.getNextToolFetchTime()
                    && swordInNetwork(level, villager)) {
                essence.setNextToolFetchTime(now + FETCH_COOLDOWN);
                queue.enqueue(new TakeItemsTask("sword", 1));
                return true; // hunt next cycle, armed — bare hands still work if the fetch fails
            }
            queue.enqueue(new KillAnimalTask(animal.getUUID()));
            return true;
        }

        // no game around — serve the request board, else light up the neighborhood
        return RequestChore.plan(level, villager, essence, KEEP_ON_DEPOSIT)
                || TorchChore.plan(level, villager, essence);
    }

    private static boolean carriesRawMeat(Villager villager, VillagerEssence essence) {
        return essence.countItems(villager, stack -> RAW_MEATS.contains(stack.getItem())) > 0;
    }

    @Nullable
    private static BlockPos findLitCampfire(ServerLevel level, Villager villager) {
        BlockPos origin = villager.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-CAMPFIRE_RADIUS, -4, -CAMPFIRE_RADIUS),
                origin.offset(CAMPFIRE_RADIUS, 4, CAMPFIRE_RADIUS))) {
            var state = level.getBlockState(pos);
            if (state.getBlock() instanceof CampfireBlock && state.getValue(CampfireBlock.LIT)
                    && PerceptionSystem.canSee(level, villager, pos)) {
                // line of sight is a cheap "can I get to it" proxy — a walled-off campfire is
                // skipped so the hunter deposits its raw meat instead of forever failing to reach it
                double dist = pos.distSqr(origin);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = pos.immutable();
                }
            }
        }
        return best;
    }

    @Nullable
    private static BlockPos nearestFoodDrop(ServerLevel level, Villager villager) {
        AABB area = new AABB(villager.blockPosition()).inflate(DROP_SCAN_RADIUS, 4.0, DROP_SCAN_RADIUS);
        return level.getEntitiesOfClass(ItemEntity.class, area,
                        item -> item.isAlive() && item.getItem().has(DataComponents.FOOD)).stream()
                .min(Comparator.comparingDouble(villager::distanceToSqr))
                .map(item -> item.blockPosition())
                .orElse(null);
    }

    @Nullable
    private static Animal findHuntable(ServerLevel level, Villager villager) {
        AABB area = new AABB(villager.blockPosition())
                .inflate(HUNT_RADIUS, HUNT_HEIGHT, HUNT_RADIUS);
        return level.getEntitiesOfClass(Animal.class, area,
                        animal -> animal.isAlive() && !animal.isBaby()
                                && HUNTABLE.contains(animal.getType())
                                && villager.hasLineOfSight(animal)).stream()
                .min(Comparator.comparingDouble(villager::distanceToSqr))
                .orElse(null);
    }

    private static boolean swordInNetwork(ServerLevel level, Villager villager) {
        UUID self = villager.getUUID();
        return StorageLedger.get(level).findSource(
                VillageAnchor.resolve(level, villager), villager.blockPosition(), self,
                ItemFilter.parse("sword"), level.getGameTime(), Set.of()) != null;
    }
}
