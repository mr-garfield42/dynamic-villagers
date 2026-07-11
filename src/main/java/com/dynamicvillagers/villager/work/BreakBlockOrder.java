package com.dynamicvillagers.villager.work;

import com.dynamicvillagers.villager.VillagerEssence;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Breaks the target block under player rules. If another block obstructs the villager's view
 * of the target (leaves over a log, grass over an ore), that block is mined first — players
 * cannot mine through things, and neither can villagers.
 */
public class BreakBlockOrder implements WorkOrder {
    private final BlockPos pos;
    @Nullable
    private BlockPos workPos; // the block actually being hit right now: an obstruction or pos
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
        if (level.getBlockState(pos).isAir()) {
            abort(level, villager);
            return true;
        }

        BlockPos obstruction = WorkHelper.findObstruction(level, villager, pos);
        BlockPos target = obstruction != null ? obstruction : pos;
        if (!target.equals(workPos)) {
            abort(level, villager); // switching blocks: cracks and progress belong to one block
            workPos = target;
        }
        // eyes on the block actually being hit (this runs after the task's look call, so the
        // obstruction wins over the final target while it is being cleared)
        villager.getLookControl().setLookAt(Vec3.atCenterOf(target));
        // hold position against the block: mining a hard block takes many ticks, and without
        // this a vanilla idle stroll would wander the villager off and reset progress forever
        WorkHelper.holdAtSolid(villager, target);

        BlockState state = level.getBlockState(target);
        if (state.isAir()) {
            return false; // changed under us; re-evaluate next tick
        }
        float hardness = state.getDestroySpeed(level, target);
        if (hardness < 0) { // unbreakable (target or obstruction), same as for players
            abort(level, villager);
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
            level.destroyBlockProgress(villager.getId(), target, crackStage);
            lastCrackStage = crackStage;
        }

        if (progress < 1.0F) {
            return false;
        }

        if (correctTool) {
            Block.dropResources(state, level, target, state.hasBlockEntity() ? level.getBlockEntity(target) : null, villager, tool);
        }
        level.destroyBlock(target, false, villager);
        if (!tool.isEmpty()) {
            tool.hurtAndBreak(1, villager, EquipmentSlot.MAINHAND);
        }
        boolean wasFinalTarget = target.equals(pos);
        abort(level, villager);
        return wasFinalTarget; // an obstruction down is progress, not completion
    }

    @Override
    public void abort(ServerLevel level, Villager villager) {
        if (workPos != null) {
            level.destroyBlockProgress(villager.getId(), workPos, -1);
        }
        workPos = null;
        progress = 0.0F;
        lastCrackStage = -1;
    }
}
