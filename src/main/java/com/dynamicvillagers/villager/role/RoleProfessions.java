package com.dynamicvillagers.villager.role;

import net.minecraft.world.entity.npc.VillagerProfession;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The unification of Dynamic Villagers roles with vanilla professions (owner decision,
 * 2026-07-10): a DV role IS a vanilla profession. Setting a role makes the villager seek the
 * matching job-site block and gain the profession the vanilla way (SeekJobSiteBehavior);
 * naturally claiming a mapped job site grants the role back (VillagerMixin's setVillagerData
 * sync). Unmapped professions (librarian, cleric, ...) have no role — those villagers stay
 * pure-vanilla traders. See docs/RESEARCH_PROFESSIONS.md for the verified mechanics.
 */
public final class RoleProfessions {

    private static final Map<VillagerRole, VillagerProfession> ROLE_TO_PROFESSION = Map.of(
            VillagerRole.FARMER, VillagerProfession.FARMER,
            VillagerRole.MINER, VillagerProfession.TOOLSMITH,
            VillagerRole.LUMBERJACK, VillagerProfession.FLETCHER,
            VillagerRole.BUILDER, VillagerProfession.MASON,
            // the hunter claims a smoker for the butcher skin (owner: "should be Butcher skin");
            // its cooking is done physically on a campfire, not the smoker's furnace slots
            VillagerRole.HUNTER, VillagerProfession.BUTCHER);

    private static final Map<VillagerProfession, VillagerRole> PROFESSION_TO_ROLE = Map.of(
            VillagerProfession.FARMER, VillagerRole.FARMER,
            VillagerProfession.TOOLSMITH, VillagerRole.MINER,
            VillagerProfession.FLETCHER, VillagerRole.LUMBERJACK,
            VillagerProfession.MASON, VillagerRole.BUILDER,
            VillagerProfession.BUTCHER, VillagerRole.HUNTER,
            VillagerProfession.NONE, VillagerRole.NONE);

    /** The vanilla profession a role should acquire, or null for NONE / unmapped roles. */
    @Nullable
    public static VillagerProfession professionFor(VillagerRole role) {
        return ROLE_TO_PROFESSION.get(role);
    }

    /**
     * The role a profession grants: a mapped role, NONE for {@link VillagerProfession#NONE},
     * or null for professions we don't drive (their villagers keep no DV role).
     */
    @Nullable
    public static VillagerRole roleFor(VillagerProfession profession) {
        return PROFESSION_TO_ROLE.get(profession);
    }

    private RoleProfessions() {
    }
}
