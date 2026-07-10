package com.dynamicvillagers.construction;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.jetbrains.annotations.Nullable;

/**
 * What a villager must carry to realize one planned block state. The default is the block's
 * own item; the specials table covers states that players also don't buy one-for-one: the
 * upper half of a door (the door item pays for both halves), the head of a bed, a double
 * slab (two items, one state), farmland and dirt paths (made from placed dirt), and water
 * sources (a carried water bucket, emptied on site). States with no item form at all
 * (e.g. fire) are unbuildable and reported as such at parse time.
 */
public final class BlockRequirements {

    /** One planned block's material bill: the item to consume and how many of it. */
    public record Requirement(Item item, int count) {
    }

    /**
     * @return the material bill for placing {@code state}, null when placement is free
     * (air to clear, the dependent half of a multi-part block), or a requirement on
     * {@link Items#AIR} marking a state that cannot be built by hand.
     */
    @Nullable
    public static Requirement resolve(BlockState state) {
        if (state.isAir()) {
            return null; // "must be air" costs nothing — it is clear work
        }
        if (isDependentPart(state)) {
            return null; // the primary half's item covers both parts
        }
        if (state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH)) {
            return new Requirement(Items.DIRT, 1); // placed as dirt, then tilled/shoveled
        }
        if (state.is(Blocks.WATER)) {
            return state.getFluidState().isSource()
                    ? new Requirement(Items.WATER_BUCKET, 1)
                    : new Requirement(Items.AIR, 1); // flowing water can't be placed
        }
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE)
                && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
            return new Requirement(state.getBlock().asItem(), 2);
        }
        Item item = state.getBlock().asItem();
        return new Requirement(item, 1); // item == AIR marks an unbuildable state
    }

    /**
     * The half of a multi-part block that its primary places atomically (door upper, bed
     * head). These never get their own placement work: a lone half pops off on the next
     * neighbor update, so PlaceStateOrder sets both parts in one tick from the primary,
     * and the builder's diff skips dependent entries entirely (vanilla breaks the primary
     * whenever a dependent half is destroyed, so the primary's mismatch covers repair).
     */
    public static boolean isDependentPart(BlockState state) {
        return (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)
                || (state.hasProperty(BlockStateProperties.BED_PART)
                        && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD);
    }

    /** True when the state can physically be built by a villager carrying items. */
    public static boolean isBuildable(BlockState state) {
        Requirement requirement = resolve(state);
        return requirement == null || requirement.item() != Items.AIR;
    }

    private BlockRequirements() {
    }
}
