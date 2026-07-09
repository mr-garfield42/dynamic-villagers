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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Converts a dirt/grass block into farmland with a carried hoe, under player rules: walk into
 * reach, see the block, one point of hoe durability. Fails without a hoe — bare hands don't
 * till for players either.
 */
public class TillSoilTask implements Task {
    public static final String TYPE = "till_soil";
    private static final int GIVE_UP_TICKS = 600;

    private final BlockPos pos;
    private int ticksRun;

    public TillSoilTask(BlockPos pos) {
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
        if (state.is(Blocks.FARMLAND)) {
            return Status.DONE; // someone beat us to it
        }
        if (!(state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK))
                || !level.getBlockState(pos.above()).isAir()) {
            return Status.FAILED; // not tillable (anymore)
        }
        VillagerEssence.SlotRef hoe = VillagerEssence.get(villager)
                .findSlot(villager, ItemFilter.parse("hoe"));
        if (hoe == null) {
            return Status.FAILED;
        }
        if (!WorkHelper.moveIntoReachAndLook(villager, pos)) {
            return Status.IN_PROGRESS;
        }
        if (WorkHelper.findObstruction(level, villager, pos) != null) {
            return Status.FAILED; // can't see it — no tilling through walls
        }
        level.setBlockAndUpdate(pos, Blocks.FARMLAND.defaultBlockState());
        level.playSound(null, pos, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 1.0F, 1.0F);
        hoe.stack().hurtAndBreak(1, villager, EquipmentSlot.MAINHAND);
        return Status.DONE;
    }

    @Override
    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("pos", pos.asLong());
        return tag;
    }

    public static Task load(CompoundTag tag, HolderLookup.Provider provider) {
        return new TillSoilTask(BlockPos.of(tag.getLong("pos")));
    }
}
