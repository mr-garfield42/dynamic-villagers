package com.dynamicvillagers.construction;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Set;

/**
 * Compares a world block against a blueprint's captured state, ignoring properties the
 * builder can't hold fixed: those Minecraft derives from neighbors — stair {@code shape},
 * the six connection booleans (panes, fences, walls, redstone, vines), redstone power — and
 * operational state like a door's {@code open} (a villager opens a door by walking through
 * it). The builder places the captured state and these settle to whatever the world dictates;
 * comparing them would make the diff (and the placement order) re-work a settled block forever.
 */
public final class BlockMatch {

    private static final Set<Property<?>> DERIVED = Set.of(
            BlockStateProperties.STAIRS_SHAPE,
            BlockStateProperties.NORTH,
            BlockStateProperties.EAST,
            BlockStateProperties.SOUTH,
            BlockStateProperties.WEST,
            BlockStateProperties.UP,
            BlockStateProperties.DOWN,
            BlockStateProperties.POWERED,
            BlockStateProperties.POWER,
            // operational, not structural: a villager walking through opens the door it just
            // placed, and OPEN≠closed would make the diff break-and-replace it forever
            BlockStateProperties.OPEN);

    public static boolean matches(BlockState world, BlockState planned) {
        if (world == planned) {
            return true;
        }
        if (planned.isAir()) {
            return world.isAir();
        }
        if (!world.is(planned.getBlock())) {
            return false;
        }
        for (Property<?> property : planned.getProperties()) {
            if (!DERIVED.contains(property) && !world.getValue(property).equals(planned.getValue(property))) {
                return false; // a meaningful property (facing, half, ...) differs — real work
            }
        }
        return true; // same block, only neighbor-derived properties differ — already built
    }

    private BlockMatch() {
    }
}
