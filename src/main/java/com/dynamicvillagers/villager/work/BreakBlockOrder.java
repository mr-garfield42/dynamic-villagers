package com.dynamicvillagers.villager.work;

import com.dynamicvillagers.villager.VillagerEssence;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class BreakBlockOrder implements WorkOrder {
    private final BlockPos pos;
    private float progress;
    private int lastCrackStage = -1;

    public BreakBlockOrder(BlockPos pos) {
        this.pos = pos;
    }

    @Override
    public BlockPos pos() {
        return pos;
    }

    @Override
    public boolean tick(ServerLevel level, Villager villager) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            clearCracks(level, villager);
            return true;
        }
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0) { // unbreakable, same as for players
            clearCracks(level, villager);
            return true;
        }

        VillagerEssence.SlotRef toolSlot = VillagerEssence.get(villager).findBestTool(villager, state);
        ItemStack tool = toolSlot != null ? toolSlot.stack() : ItemStack.EMPTY;
        float speed = tool.isEmpty() ? 1.0F : tool.getDestroySpeed(state);
        boolean correctTool = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        // Same formula as player mining: speed / hardness / 30 (harvestable) or / 100 (not).
        progress += speed / hardness / (correctTool ? 30.0F : 100.0F);

        int crackStage = (int) (progress * 10.0F) - 1;
        if (crackStage != lastCrackStage) {
            level.destroyBlockProgress(villager.getId(), pos, crackStage);
            lastCrackStage = crackStage;
        }

        if (progress < 1.0F) {
            return false;
        }

        if (correctTool) {
            Block.dropResources(state, level, pos, state.hasBlockEntity() ? level.getBlockEntity(pos) : null, villager, tool);
        }
        level.destroyBlock(pos, false, villager);
        if (!tool.isEmpty()) {
            tool.hurtAndBreak(1, villager, EquipmentSlot.MAINHAND);
        }
        clearCracks(level, villager);
        return true;
    }

    @Override
    public void abort(ServerLevel level, Villager villager) {
        clearCracks(level, villager);
    }

    private void clearCracks(ServerLevel level, Villager villager) {
        level.destroyBlockProgress(villager.getId(), pos, -1);
    }
}
