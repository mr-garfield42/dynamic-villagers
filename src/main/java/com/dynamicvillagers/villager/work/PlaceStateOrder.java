package com.dynamicvillagers.villager.work;

import com.dynamicvillagers.construction.BlockMatch;
import com.dynamicvillagers.construction.BlockRequirements;
import com.dynamicvillagers.villager.VillagerEssence;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

/**
 * Realizes one blueprint entry: makes the target position hold exactly the given block
 * state. A wrong occupant is mined away first (player rules — no replacing blocks by fiat),
 * view obstructions are cleared like every other order, the mapped material item is
 * consumed from the villager's inventory, and nothing is placed where an entity stands
 * (players cannot entomb themselves either). Support-dependent states whose support is
 * missing finish without placing — the builder's next diff pass retries them once the
 * support exists.
 */
public class PlaceStateOrder implements WorkOrder {
    private static final int REPOSITION_TICKS = 60;

    private final BlockPos pos;
    private final BlockState target;
    @Nullable
    private BreakBlockOrder clearing; // wrong occupant, or a block in the line of sight
    private int obstructedTicks;

    public PlaceStateOrder(BlockPos pos, BlockState target) {
        this.pos = pos;
        this.target = target;
    }

    @Override
    public BlockPos pos() {
        return pos;
    }

    @Override
    public boolean tick(ServerLevel level, Villager villager) {
        BlockState current = level.getBlockState(pos);
        if (BlockMatch.matches(current, target)) {
            return true; // already as planned (ignoring neighbor-derived shape/connections)
        }

        // wrong occupant: mine it away before placing. Replaceables (grass etc.) are
        // overwritten by placement itself like for players — unless the plan wants air
        // there, in which case they too must go.
        if (!current.isAir() && (target.isAir() || !current.canBeReplaced())) {
            if (current.getDestroySpeed(level, pos) < 0) {
                return true; // unbreakable occupant — abandon; the planner skips these too
            }
            if (clearing == null || !clearing.pos().equals(pos)) {
                clearing = new BreakBlockOrder(pos);
            }
            if (clearing.tick(level, villager)) {
                clearing = null;
            }
            return false;
        }
        if (target.isAir()) {
            return current.isAir(); // clear-work: replaceables fall to the break path above
        }

        BlockPos obstruction = WorkHelper.findObstruction(level, villager, pos);
        if (obstruction != null) {
            if (level.getBlockState(obstruction).getDestroySpeed(level, obstruction) < 0) {
                return true; // can't see the spot and can't clear the way — abandon
            }
            // the obstruction is usually the wall WE just placed — walk to a clearer angle
            // first; only mine through when repositioning doesn't find one
            if (++obstructedTicks <= REPOSITION_TICKS) {
                WorkHelper.sidestep(villager, pos, obstructedTicks);
                return false;
            }
            if (clearing == null || !clearing.pos().equals(obstruction)) {
                clearing = new BreakBlockOrder(obstruction);
            }
            if (clearing.tick(level, villager)) {
                clearing = null;
            }
            return false;
        }
        obstructedTicks = 0; // clear view — a later re-obstruction gets a fresh try

        if (!target.canSurvive(level, pos)) {
            return true; // support missing (torch before its wall) — a later pass retries
        }

        // Multi-part blocks place whole or not at all: a lone door/bed half pops off on the
        // next neighbor update and eats the item (owner playtest: "cut off partially").
        BlockPos secondaryPos = null;
        BlockState secondaryState = null;
        if (target.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && target.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
            secondaryPos = pos.above();
            secondaryState = target.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        } else if (target.hasProperty(BlockStateProperties.BED_PART)
                && target.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) {
            secondaryPos = pos.relative(target.getValue(BlockStateProperties.HORIZONTAL_FACING));
            secondaryState = target.setValue(BlockStateProperties.BED_PART, BedPart.HEAD);
        }
        if (secondaryPos != null) {
            BlockState occupant = level.getBlockState(secondaryPos);
            if (occupant != secondaryState && !occupant.isAir() && !occupant.canBeReplaced()) {
                if (occupant.getDestroySpeed(level, secondaryPos) < 0) {
                    return true; // the other half's spot is unclearable — abandon
                }
                if (clearing == null || !clearing.pos().equals(secondaryPos)) {
                    clearing = new BreakBlockOrder(secondaryPos);
                }
                if (clearing.tick(level, villager)) {
                    clearing = null;
                }
                return false;
            }
            if (!level.isUnobstructed(secondaryState, secondaryPos, CollisionContext.empty())) {
                return false; // an entity is standing where the other half goes
            }
        }
        if (!level.isUnobstructed(target, pos, CollisionContext.empty())) {
            // an entity is in the way — when it is the builder itself, step aside instead
            // of waiting for the give-up timer (players move out of their own way too)
            if (villager.getBoundingBox().intersects(new AABB(pos))) {
                Vec3 away = villager.position().subtract(Vec3.atCenterOf(pos));
                Vec3 dir = away.horizontalDistanceSqr() < 1.0E-4
                        ? Vec3.atLowerCornerOf(villager.getDirection().getOpposite().getNormal())
                        : new Vec3(away.x, 0, away.z).normalize();
                BlockPos step = BlockPos.containing(villager.position().add(dir.scale(2)));
                villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                        new WalkTarget(step, WorkHelper.WALK_SPEED, 0));
            }
            return false;
        }

        // A null requirement is a free dependent part (door upper half, bed head): the
        // paying half already consumed the item, this half still has to be set in-world.
        BlockRequirements.Requirement requirement = BlockRequirements.resolve(target);
        VillagerEssence.SlotRef slot = null;
        if (requirement != null) {
            if (requirement.item() == Items.AIR) {
                return true; // unbuildable state — the planner filters these; safety net
            }
            slot = VillagerEssence.get(villager).findSlot(villager, stack ->
                    stack.is(requirement.item()) && stack.getCount() >= requirement.count());
            if (slot == null) {
                return true; // materials ran out mid-batch — the planner fetches and retries
            }
        }
        level.setBlockAndUpdate(pos, target);
        if (secondaryPos != null) {
            level.setBlockAndUpdate(secondaryPos, secondaryState);
        }
        level.playSound(null, pos, target.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
        if (slot != null) {
            ItemStack stack = slot.stack();
            if (stack.getItem() instanceof BucketItem) {
                // emptying a bucket leaves the empty bucket behind, like for a player
                stack.shrink(1);
                slot.container().setItem(slot.slot(), new ItemStack(Items.BUCKET));
            } else {
                stack.shrink(requirement.count());
            }
            slot.container().setChanged();
        }
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
