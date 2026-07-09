package com.dynamicvillagers.villager.work;

import com.dynamicvillagers.villager.VillagerEssence;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.state.BlockState;

/** Places the first block item found in the villager's inventory at the target position. */
public class PlaceBlockOrder implements WorkOrder {
    private final BlockPos pos;

    public PlaceBlockOrder(BlockPos pos) {
        this.pos = pos;
    }

    @Override
    public BlockPos pos() {
        return pos;
    }

    @Override
    public boolean tick(ServerLevel level, Villager villager) {
        if (!level.getBlockState(pos).canBeReplaced()) {
            return true; // spot already occupied
        }
        VillagerEssence.SlotRef slot = VillagerEssence.get(villager)
                .findSlot(villager, stack -> stack.getItem() instanceof BlockItem);
        if (slot == null) {
            return true; // nothing to place with
        }
        BlockItem item = (BlockItem) slot.stack().getItem();
        BlockState placed = item.getBlock().defaultBlockState();
        level.setBlockAndUpdate(pos, placed);
        level.playSound(null, pos, placed.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
        slot.stack().shrink(1);
        slot.container().setChanged();
        return true;
    }
}
