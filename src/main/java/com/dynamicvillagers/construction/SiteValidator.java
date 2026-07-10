package com.dynamicvillagers.construction;

import com.dynamicvillagers.village.ConstructionLedger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

/**
 * Milestone 4.2: refuses construction sites that cannot work before a builder wastes days
 * on them — overlapping an existing site, or footprint columns so far above ground that
 * the bounded foundation fill can't reach it. The checks run at designation time
 * (`/dv build add`, Building Marker); `/dv build add ... force` skips them for creative
 * experiments.
 */
public final class SiteValidator {
    /** How deep a builder will pillar foundation material down to find ground. */
    public static final int MAX_FOUNDATION_DEPTH = 4;

    /** @return a human-readable refusal, or null when the site is placeable. */
    @Nullable
    public static String validate(ServerLevel level, ConstructionLedger ledger,
                                  Blueprint blueprint, BlockPos origin, Rotation rotation) {
        BoundingBox box = boxOf(blueprint, origin, rotation);
        for (ConstructionLedger.ConstructionSite other : ledger.allSites()) {
            Blueprint otherBlueprint = Blueprints.load(level, other.templateId());
            if (otherBlueprint != null
                    && box.intersects(boxOf(otherBlueprint, other.origin(), other.rotation()))) {
                return "overlaps site #" + other.id() + " at " + other.origin().toShortString();
            }
        }
        for (Blueprint.PlannedBlock plan : blueprint.placedBlocks(origin, rotation)) {
            if (plan.pos().getY() != origin.getY() || plan.state().isAir()) {
                continue; // only the bottom layer's solid cells need bearing
            }
            boolean grounded = false;
            for (int depth = 1; depth <= MAX_FOUNDATION_DEPTH + 1; depth++) {
                BlockPos below = plan.pos().below(depth);
                if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                    grounded = true;
                    break;
                }
            }
            if (!grounded) {
                return "no ground within " + MAX_FOUNDATION_DEPTH + " blocks below "
                        + plan.pos().toShortString() + " — pick flatter terrain";
            }
        }
        return null;
    }

    private static BoundingBox boxOf(Blueprint blueprint, BlockPos origin, Rotation rotation) {
        Vec3i size = blueprint.size(rotation);
        return new BoundingBox(origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + size.getX() - 1, origin.getY() + size.getY() - 1,
                origin.getZ() + size.getZ() - 1);
    }

    private SiteValidator() {
    }
}
