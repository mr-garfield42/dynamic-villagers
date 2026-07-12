package com.dynamicvillagers.villager.task;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.work.ContainerUtil;
import com.dynamicvillagers.villager.work.WorkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.Optional;

/**
 * Cooks raw meat on a lit campfire, physically (owner decision: campfire, best-effort). The
 * hunter walks to the fire, lays its cookable food onto the free slots — the same right-click
 * interaction a player uses — waits by the fire while it cooks, and collects the cooked drops
 * that pop out. Done when everything it laid down has cooked and been gathered (or the give-up
 * timer trips; any stragglers are gleaned on a later visit). If a slot is busy it waits for it
 * to free, so a stack larger than the fire's four slots cooks in waves.
 */
public class CookAtCampfireTask implements Task {
    public static final String TYPE = "cook_at_campfire";
    private static final int GIVE_UP_TICKS = 1200; // one food cook is ~600 ticks; leaves margin for a wave
    private static final double COLLECT_RADIUS = 3.0;

    private final BlockPos campfire;
    private int ticksRun;
    private int placed; // items we've laid on the fire and are waiting to collect back cooked

    public CookAtCampfireTask(BlockPos campfire) {
        this.campfire = campfire;
    }

    @Override
    public String typeId() {
        return TYPE;
    }

    @Override
    public Status tick(ServerLevel level, Villager villager) {
        if (++ticksRun > GIVE_UP_TICKS) {
            return placed > 0 ? Status.DONE : Status.FAILED;
        }
        if (!(level.getBlockState(campfire).getBlock() instanceof CampfireBlock)
                || !level.getBlockState(campfire).getValue(CampfireBlock.LIT)
                || !(level.getBlockEntity(campfire) instanceof CampfireBlockEntity fire)) {
            return placed > 0 ? Status.DONE : Status.FAILED; // fire's gone out or gone — bail
        }
        if (!WorkHelper.moveIntoReachAndLook(villager, campfire)) {
            return Status.IN_PROGRESS;
        }

        VillagerEssence essence = VillagerEssence.get(villager);
        placeCookableFood(level, villager, essence, fire);
        collectCookedNearby(level, villager, essence);

        boolean noMoreToPlace = !hasCookableCarried(level, villager, essence);
        if (placed == 0 && noMoreToPlace) {
            return Status.DONE; // nothing to cook (or the fire stayed full and we could place none)
        }
        boolean fireEmpty = fire.getItems().stream().allMatch(ItemStack::isEmpty);
        if (noMoreToPlace && fireEmpty && cookedDropsNearby(level).isEmpty()) {
            return Status.DONE; // everything cooked and gathered
        }
        // pin to the fire so a stroll doesn't drag the hunter off mid-cook
        WorkHelper.holdAtSolid(villager, campfire);
        return Status.IN_PROGRESS;
    }

    private void placeCookableFood(ServerLevel level, Villager villager, VillagerEssence essence,
                                   CampfireBlockEntity fire) {
        // stop when the fire is full (no empty slot) or nothing cookable is carried
        while (fire.getItems().stream().anyMatch(ItemStack::isEmpty)) {
            VillagerEssence.SlotRef slot = findCookableSlot(level, villager, essence);
            if (slot == null) {
                return;
            }
            Optional<RecipeHolder<CampfireCookingRecipe>> recipe = cookRecipe(level, slot.stack());
            if (recipe.isEmpty()) {
                return;
            }
            // placeFood consumes one from the passed stack in place and lays it on the fire
            if (fire.placeFood(villager, slot.stack(), recipe.get().value().getCookingTime())) {
                placed++;
            } else {
                return; // no free slot after all
            }
        }
    }

    private void collectCookedNearby(ServerLevel level, Villager villager, VillagerEssence essence) {
        for (ItemEntity drop : cookedDropsNearby(level)) {
            ItemStack stack = drop.getItem().copy();
            stack = ContainerUtil.insert(villager.getInventory(), stack);
            stack = ContainerUtil.insert(essence.getExtraInventory(), stack);
            if (stack.isEmpty()) {
                drop.discard();
            } else {
                drop.setItem(stack); // inventory full — leave the rest on the ground
            }
        }
    }

    private java.util.List<ItemEntity> cookedDropsNearby(ServerLevel level) {
        AABB area = new AABB(campfire).inflate(COLLECT_RADIUS, 2.0, COLLECT_RADIUS);
        return level.getEntitiesOfClass(ItemEntity.class, area,
                item -> item.isAlive() && item.getItem().has(DataComponents.FOOD));
    }

    private static boolean hasCookableCarried(ServerLevel level, Villager villager, VillagerEssence essence) {
        return findCookableSlot(level, villager, essence) != null;
    }

    private static VillagerEssence.SlotRef findCookableSlot(ServerLevel level, Villager villager,
                                                            VillagerEssence essence) {
        for (SimpleContainer container : new SimpleContainer[]{villager.getInventory(), essence.getExtraInventory()}) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty() && cookRecipe(level, stack).isPresent()) {
                    return new VillagerEssence.SlotRef(container, i);
                }
            }
        }
        return null;
    }

    private static Optional<RecipeHolder<CampfireCookingRecipe>> cookRecipe(ServerLevel level, ItemStack stack) {
        return level.getRecipeManager().getRecipeFor(
                RecipeType.CAMPFIRE_COOKING, new SingleRecipeInput(stack), level);
    }

    @Override
    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("campfire", campfire.asLong());
        return tag;
    }

    public static Task load(CompoundTag tag, HolderLookup.Provider provider) {
        return new CookAtCampfireTask(BlockPos.of(tag.getLong("campfire")));
    }
}
