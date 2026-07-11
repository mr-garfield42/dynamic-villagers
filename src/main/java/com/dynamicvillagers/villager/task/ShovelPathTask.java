package com.dynamicvillagers.villager.task;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.work.ItemFilter;
import com.dynamicvillagers.villager.work.WorkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Flattens a dirt-family block into a dirt path with a carried shovel, under player rules:
 * walk into reach, see the block, one point of shovel durability. Fails without a shovel —
 * bare hands don't make paths for players either. Used by the path builder (milestone 4.6).
 */
public class ShovelPathTask implements Task {
    public static final String TYPE = "shovel_path";
    private static final int GIVE_UP_TICKS = 300;

    private final BlockPos pos;
    private int ticksRun;

    public ShovelPathTask(BlockPos pos) {
        this.pos = pos;
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
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.DIRT_PATH)) {
            return Status.DONE; // already a path
        }
        if (!state.is(BlockTags.DIRT) || !level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()) {
            return Status.FAILED; // not flattenable (anymore), or something sits on top
        }
        VillagerEssence.SlotRef shovel = VillagerEssence.get(villager)
                .findSlot(villager, ItemFilter.parse("shovel"));
        if (shovel == null) {
            return Status.FAILED;
        }
        if (!WorkHelper.moveIntoReachAndLook(villager, pos)) {
            return Status.IN_PROGRESS;
        }
        if (WorkHelper.findObstruction(level, villager, pos) != null) {
            return Status.FAILED; // can't see it — no shaping through walls
        }
        level.setBlockAndUpdate(pos, Blocks.DIRT_PATH.defaultBlockState());
        level.playSound(null, pos, SoundEvents.SHOVEL_FLATTEN, SoundSource.BLOCKS, 1.0F, 1.0F);
        shovel.stack().hurtAndBreak(1, villager, EquipmentSlot.MAINHAND);
        return Status.DONE;
    }

    @Override
    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("pos", pos.asLong());
        return tag;
    }

    public static Task load(CompoundTag tag, HolderLookup.Provider provider) {
        return new ShovelPathTask(BlockPos.of(tag.getLong("pos")));
    }
}
