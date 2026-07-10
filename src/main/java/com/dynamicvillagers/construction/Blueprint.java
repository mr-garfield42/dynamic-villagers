package com.dynamicvillagers.construction;

import com.dynamicvillagers.DynamicVillagers;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A structure template parsed into the village's terms: an ordered list of "this exact
 * state belongs at this relative position" entries that villagers realize one block at a
 * time. Air entries are kept — they mean "this space must be clear" (interiors, doorways).
 * Normalization at parse time: jigsaw blocks become their final_state, structure_void
 * positions are dropped (don't-care), waterlogging is stripped, entities are ignored, and
 * only the first palette is read. Entries are sorted bottom-up (then z, then x), which is
 * the base build order.
 */
public final class Blueprint {

    /** One planned block; pos is blueprint-relative from {@link #blocks}, world-absolute from {@link #placedBlocks}. */
    public record PlannedBlock(BlockPos pos, BlockState state) {
    }

    private final ResourceLocation id;
    private final Vec3i size;
    private final List<PlannedBlock> blocks;
    private final Map<Item, Integer> requirements;
    private final int unbuildableCount;

    private Blueprint(ResourceLocation id, Vec3i size, List<PlannedBlock> blocks) {
        this.id = id;
        this.size = size;
        this.blocks = List.copyOf(blocks);
        Map<Item, Integer> needed = new LinkedHashMap<>();
        int unbuildable = 0;
        for (PlannedBlock block : this.blocks) {
            BlockRequirements.Requirement requirement = BlockRequirements.resolve(block.state());
            if (requirement == null) {
                continue;
            }
            if (requirement.item() == Items.AIR) {
                unbuildable++;
                continue;
            }
            needed.merge(requirement.item(), requirement.count(), Integer::sum);
        }
        this.requirements = Map.copyOf(needed);
        this.unbuildableCount = unbuildable;
    }

    /** Parses saved structure-template NBT (the format of {@code StructureTemplate#save}). */
    public static Blueprint parse(ResourceLocation id, CompoundTag tag, HolderLookup<Block> blockLookup) {
        ListTag sizeTag = tag.getList("size", Tag.TAG_INT);
        Vec3i size = new Vec3i(sizeTag.getInt(0), sizeTag.getInt(1), sizeTag.getInt(2));

        List<BlockState> palette = new ArrayList<>();
        for (Tag entry : tag.getList("palette", Tag.TAG_COMPOUND)) {
            palette.add(NbtUtils.readBlockState(blockLookup, (CompoundTag) entry));
        }

        List<PlannedBlock> blocks = new ArrayList<>();
        for (Tag entry : tag.getList("blocks", Tag.TAG_COMPOUND)) {
            CompoundTag blockTag = (CompoundTag) entry;
            int stateIndex = blockTag.getInt("state");
            if (stateIndex < 0 || stateIndex >= palette.size()) {
                continue;
            }
            BlockState state = palette.get(stateIndex);
            if (state.is(Blocks.STRUCTURE_VOID)) {
                continue; // don't-care position: whatever the world has there is fine
            }
            if (state.is(Blocks.JIGSAW)) {
                state = resolveJigsaw(id, blockTag.getCompound("nbt"), blockLookup);
                if (state == null) {
                    continue;
                }
            }
            if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
                state = state.setValue(BlockStateProperties.WATERLOGGED, false);
            }
            ListTag posTag = blockTag.getList("pos", Tag.TAG_INT);
            blocks.add(new PlannedBlock(
                    new BlockPos(posTag.getInt(0), posTag.getInt(1), posTag.getInt(2)), state));
        }
        blocks.sort(Comparator
                .comparingInt((PlannedBlock block) -> block.pos().getY())
                .thenComparingInt(block -> block.pos().getZ())
                .thenComparingInt(block -> block.pos().getX()));
        return new Blueprint(id, size, blocks);
    }

    private static BlockState resolveJigsaw(ResourceLocation id, CompoundTag nbt,
                                            HolderLookup<Block> blockLookup) {
        String finalState = nbt.getString("final_state");
        if (finalState.isEmpty()) {
            return Blocks.AIR.defaultBlockState(); // jigsaws default to becoming air
        }
        try {
            return BlockStateParser.parseForBlock(blockLookup, finalState, true).blockState();
        } catch (CommandSyntaxException e) {
            DynamicVillagers.LOGGER.warn("Blueprint {}: unparseable jigsaw final_state '{}' — skipping",
                    id, finalState);
            return null;
        }
    }

    public ResourceLocation id() {
        return id;
    }

    public Vec3i size() {
        return size;
    }

    public Vec3i size(Rotation rotation) {
        return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90
                ? new Vec3i(size.getZ(), size.getY(), size.getX())
                : size;
    }

    /** All entries, unrotated, in build order (bottom-up, then z, then x). */
    public List<PlannedBlock> blocks() {
        return blocks;
    }

    /**
     * The world-space view of this blueprint: absolute positions and rotated states, still
     * in build order. The rotated box occupies {@code origin .. origin + size(rotation) - 1}
     * (same convention as vanilla's zero-position transform).
     */
    public List<PlannedBlock> placedBlocks(BlockPos origin, Rotation rotation) {
        BlockPos shift = origin.offset(zeroShift(rotation));
        List<PlannedBlock> placed = new ArrayList<>(blocks.size());
        for (PlannedBlock block : blocks) {
            BlockPos pos = StructureTemplate.transform(block.pos(), Mirror.NONE, rotation, BlockPos.ZERO)
                    .offset(shift.getX(), shift.getY(), shift.getZ());
            placed.add(new PlannedBlock(pos, block.state().rotate(rotation)));
        }
        return placed;
    }

    private Vec3i zeroShift(Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new Vec3i(size.getZ() - 1, 0, 0);
            case CLOCKWISE_180 -> new Vec3i(size.getX() - 1, 0, size.getZ() - 1);
            case COUNTERCLOCKWISE_90 -> new Vec3i(0, 0, size.getX() - 1);
            case NONE -> Vec3i.ZERO;
        };
    }

    /** Aggregate material bill (build-order iteration order), excluding free/clear entries. */
    public Map<Item, Integer> requirements() {
        return requirements;
    }

    /** Planned states no villager can place by hand (no item form) — a template authoring smell. */
    public int unbuildableCount() {
        return unbuildableCount;
    }
}
