package com.dynamicvillagers.villager.task;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.role.Crafting;
import com.dynamicvillagers.villager.work.WorkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;

/**
 * Crafts up to {@code count} of a target item from carried materials. Recipes that fit a 2×2
 * grid are made in the villager's hands on the spot; anything needing the full 3×3 grid sends
 * the villager to a nearby crafting table first (fails if none is in reach and a table is
 * required — the planner is responsible for putting one there). Uses the vanilla recipe book
 * via {@link Crafting}, so villagers can only make what a player could, from real ingredients.
 */
public class CraftTask implements Task {
    public static final String TYPE = "craft";
    private static final int GIVE_UP_TICKS = 400;
    private static final int TABLE_SCAN = 8;
    private static final int MAX_CRAFTS_PER_TICK = 64; // crafting is instant once at the bench

    private final Item target;
    private final int count;
    private final boolean allowTable;
    private int ticksRun;
    private boolean craftedAnything;

    public CraftTask(Item target, int count, boolean allowTable) {
        this.target = target;
        this.count = count;
        this.allowTable = allowTable;
    }

    @Override
    public String typeId() {
        return TYPE;
    }

    @Override
    public Status tick(ServerLevel level, Villager villager) {
        VillagerEssence essence = VillagerEssence.get(villager);
        if (essence.countItems(villager, stack -> stack.is(target)) >= count) {
            return Status.DONE;
        }
        if (++ticksRun > GIVE_UP_TICKS) {
            return craftedAnything ? Status.DONE : Status.FAILED;
        }
        RecipeHolder<CraftingRecipe> recipe = Crafting.pickAffordableRecipe(level, villager, essence, target);
        if (recipe == null) {
            return craftedAnything ? Status.DONE : Status.FAILED; // nothing craftable from what's carried
        }
        if (Crafting.needsTable(recipe.value())) {
            if (!allowTable) {
                return craftedAnything ? Status.DONE : Status.FAILED;
            }
            BlockPos table = findTable(level, villager);
            if (table == null) {
                return craftedAnything ? Status.DONE : Status.FAILED;
            }
            if (!WorkHelper.moveIntoReachAndLook(villager, table)) {
                return Status.IN_PROGRESS;
            }
        }

        int guard = 0;
        while (essence.countItems(villager, stack -> stack.is(target)) < count && guard++ < MAX_CRAFTS_PER_TICK) {
            RecipeHolder<CraftingRecipe> next = Crafting.pickAffordableRecipe(level, villager, essence, target);
            if (next == null || (Crafting.needsTable(next.value()) && !allowTable)) {
                break;
            }
            if (!Crafting.craftOnce(level, villager, essence, next)) {
                break; // out of ingredients or out of room
            }
            craftedAnything = true;
            villager.swing(InteractionHand.MAIN_HAND);
        }
        if (essence.countItems(villager, stack -> stack.is(target)) >= count) {
            return Status.DONE;
        }
        return craftedAnything ? Status.DONE : Status.FAILED;
    }

    /** Nearest crafting table within reach-scan of the villager, or null. */
    @Nullable
    private static BlockPos findTable(ServerLevel level, Villager villager) {
        BlockPos origin = villager.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-TABLE_SCAN, -TABLE_SCAN, -TABLE_SCAN),
                origin.offset(TABLE_SCAN, TABLE_SCAN, TABLE_SCAN))) {
            if (level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                double dist = pos.distSqr(origin);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = pos.immutable();
                }
            }
        }
        return best;
    }

    @Override
    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putString("item", BuiltInRegistries.ITEM.getKey(target).toString());
        tag.putInt("count", count);
        tag.putBoolean("table", allowTable);
        return tag;
    }

    public static Task load(CompoundTag tag, HolderLookup.Provider provider) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(tag.getString("item")));
        return new CraftTask(item, tag.getInt("count"), tag.getBoolean("table"));
    }
}
