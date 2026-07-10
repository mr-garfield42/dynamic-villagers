package com.dynamicvillagers.villager.work;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Briefly opens a container's lid when a villager uses it (owner request, 2026-07-10).
 * Chest lids swing via block event 1, which drives only the lid controller — no redstone,
 * so trapped chests don't fire; barrels toggle their OPEN blockstate. The lid closes itself
 * a moment later on the level tick, so tasks stay fire-and-forget and no exit path can leave
 * a lid hanging open. Modded storage blocks we don't recognize just get the chest sounds.
 */
public final class ContainerAnimator {
    private static final int OPEN_TICKS = 15;

    // per-level countdowns; weak keys so unloading a level drops its entries
    private static final Map<ServerLevel, Map<BlockPos, Integer>> OPEN_LIDS = new WeakHashMap<>();

    /** Swings the lid open now; it falls shut on its own shortly after. */
    public static void flash(ServerLevel level, BlockPos pos) {
        Map<BlockPos, Integer> lids = OPEN_LIDS.computeIfAbsent(level, l -> new HashMap<>());
        if (lids.put(pos.immutable(), OPEN_TICKS) == null) {
            setLid(level, pos, true); // only animate/sound on the first opener; refreshes are silent
        }
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Map<BlockPos, Integer> lids = OPEN_LIDS.get(level);
        if (lids == null || lids.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<BlockPos, Integer>> iterator = lids.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            if (entry.getValue() <= 1) {
                iterator.remove();
                setLid(level, entry.getKey(), false);
            } else {
                entry.setValue(entry.getValue() - 1);
            }
        }
    }

    private static void setLid(ServerLevel level, BlockPos pos, boolean open) {
        BlockState state = level.getBlockState(pos);
        SoundEvent sound;
        if (state.getBlock() instanceof BarrelBlock && state.hasProperty(BlockStateProperties.OPEN)) {
            level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, open), Block.UPDATE_ALL);
            sound = open ? SoundEvents.BARREL_OPEN : SoundEvents.BARREL_CLOSE;
        } else {
            if (state.getBlock() instanceof ChestBlock) {
                level.blockEvent(pos, state.getBlock(), 1, open ? 1 : 0);
                // a double chest renders its lid from one half's controller, which may be
                // the partner block — swing both halves so the animation always shows
                if (state.hasProperty(BlockStateProperties.CHEST_TYPE)
                        && state.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.SINGLE) {
                    BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(state));
                    BlockState partnerState = level.getBlockState(partner);
                    if (partnerState.getBlock() instanceof ChestBlock) {
                        level.blockEvent(partner, partnerState.getBlock(), 1, open ? 1 : 0);
                    }
                }
            }
            sound = open ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE;
        }
        level.playSound(null, pos, sound, SoundSource.BLOCKS, 0.5F,
                level.random.nextFloat() * 0.1F + 0.9F);
    }

    private ContainerAnimator() {
    }
}
