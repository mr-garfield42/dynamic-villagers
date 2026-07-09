package com.dynamicvillagers.villager.work;

import com.dynamicvillagers.villager.VillagerEssence;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * Places the first matching block item found in the villager's inventory at the target
 * position. A block obstructing the villager's view of the spot is mined away first —
 * players cannot place through walls.
 */
public class PlaceBlockOrder implements WorkOrder {
    private final BlockPos pos;
    private final Predicate<ItemStack> itemFilter;
    @Nullable
    private BreakBlockOrder clearing;

    public PlaceBlockOrder(BlockPos pos) {
        this(pos, stack -> true);
    }

    public PlaceBlockOrder(BlockPos pos, Predicate<ItemStack> itemFilter) {
        this.pos = pos;
        this.itemFilter = itemFilter;
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
        BlockPos obstruction = WorkHelper.findObstruction(level, villager, pos);
        if (obstruction != null) {
            if (level.getBlockState(obstruction).getDestroySpeed(level, obstruction) < 0) {
                return true; // can't see the spot and can't clear the way — abandon
            }
            if (clearing == null || !clearing.pos().equals(obstruction)) {
                clearing = new BreakBlockOrder(obstruction);
            }
            if (clearing.tick(level, villager)) {
                clearing = null;
            }
            return false;
        }

        VillagerEssence.SlotRef slot = VillagerEssence.get(villager)
                .findSlot(villager, stack -> stack.getItem() instanceof BlockItem && itemFilter.test(stack));
        if (slot == null) {
            return true; // nothing to place with
        }
        BlockItem item = (BlockItem) slot.stack().getItem();
        BlockState placed = item.getBlock().defaultBlockState();
        if (!placed.canSurvive(level, pos)) {
            return true; // e.g. sapling with no soil below — placing would just pop it off
        }
        level.setBlockAndUpdate(pos, placed);
        level.playSound(null, pos, placed.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
        slot.stack().shrink(1);
        slot.container().setChanged();
        return true;
    }

    @Override
    public void abort(ServerLevel level, Villager villager) {
        if (clearing != null) {
            clearing.abort(level, villager);
            clearing = null;
        }
    }
}
