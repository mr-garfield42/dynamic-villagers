package com.dynamicvillagers.gametest;

import com.dynamicvillagers.DynamicVillagers;
import com.dynamicvillagers.village.ConstructionLedger;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.role.RoleProfessions;
import com.dynamicvillagers.villager.role.VillagerRole;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Profession-integration tests (owner decision, 2026-07-10): the mod role and the vanilla
 * profession are one identity. Forward tests exercise the real vanilla pipeline (claim a
 * job-site POI → vanilla assigns the profession); reverse tests drive setVillagerData to
 * confirm the mixin mirrors a profession back into the role. See docs/RESEARCH_PROFESSIONS.md.
 */
@GameTestHolder(DynamicVillagers.MOD_ID)
@PrefixGameTestTemplate(false)
public class ProfessionTests {

    @GameTest(template = "empty5x5")
    public static void mapping_is_bidirectional(GameTestHelper helper) {
        helper.assertTrue(RoleProfessions.professionFor(VillagerRole.FARMER) == VillagerProfession.FARMER,
                "farmer role → farmer profession");
        helper.assertTrue(RoleProfessions.professionFor(VillagerRole.MINER) == VillagerProfession.TOOLSMITH,
                "miner role → toolsmith profession");
        helper.assertTrue(RoleProfessions.professionFor(VillagerRole.LUMBERJACK) == VillagerProfession.FLETCHER,
                "lumberjack role → fletcher profession");
        helper.assertTrue(RoleProfessions.professionFor(VillagerRole.BUILDER) == VillagerProfession.MASON,
                "builder role → mason profession");
        helper.assertTrue(RoleProfessions.roleFor(VillagerProfession.TOOLSMITH) == VillagerRole.MINER,
                "toolsmith profession → miner role");
        helper.assertTrue(RoleProfessions.roleFor(VillagerProfession.NONE) == VillagerRole.NONE,
                "none profession → none role");
        helper.assertTrue(RoleProfessions.roleFor(VillagerProfession.LIBRARIAN) == null,
                "unmapped profession → no role");
        helper.assertTrue(RoleProfessions.professionFor(VillagerRole.NONE) == null,
                "none role → no profession to seek");
        helper.succeed();
    }

    /** Forward, end-to-end: role FARMER → the villager claims the composter and turns farmer. */
    @GameTest(template = "empty11x11", timeoutTicks = 2400, batch = "dvProfessionFarmer")
    public static void farmer_role_claims_composter_and_gains_profession(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.COMPOSTER);
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 3));
        VillagerEssence.get(villager).setRole(VillagerRole.FARMER);

        helper.succeedWhen(() -> helper.assertTrue(
                villager.getVillagerData().getProfession() == VillagerProfession.FARMER,
                "the villager should walk to the composter and become a vanilla farmer"));
    }

    /** Forward: role MINER → the villager claims a smithing table and turns toolsmith (skin). */
    @GameTest(template = "empty11x11", timeoutTicks = 2400, batch = "dvProfessionMiner")
    public static void miner_role_claims_smithing_table_and_gains_toolsmith(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.SMITHING_TABLE);
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 3));
        VillagerEssence.get(villager).setRole(VillagerRole.MINER);

        helper.succeedWhen(() -> helper.assertTrue(
                villager.getVillagerData().getProfession() == VillagerProfession.TOOLSMITH,
                "the miner should become a vanilla toolsmith (toolsmith skin)"));
    }

    /**
     * Owner playtest regression: a villager assigned to build (Building Marker) near a
     * claimable jobsite must stay a BUILDER — it must NOT get re-roled by claiming, say, a
     * composter, which is what silently stopped marked builders from building.
     */
    @GameTest(template = "empty11x11", timeoutTicks = 800, batch = "dvProfessionAssignedBuilder")
    public static void assigned_builder_keeps_role_near_jobsite(GameTestHelper helper) {
        ConstructionLedger ledger = ConstructionLedger.get(helper.getLevel());
        ledger.clear();
        helper.setBlock(new BlockPos(3, 2, 1), Blocks.COMPOSTER); // a temptation right next door

        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 1));
        VillagerEssence essence = VillagerEssence.get(villager);
        ConstructionLedger.ConstructionSite site = ledger.addSite(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(DynamicVillagers.MOD_ID, "starter_shelter"),
                helper.absolutePos(new BlockPos(6, 2, 6)), net.minecraft.world.level.block.Rotation.NONE,
                helper.getLevel().getGameTime());
        essence.setRole(VillagerRole.BUILDER);
        essence.setAssignedSiteId(site.id());

        helper.runAfterDelay(600, () -> {
            helper.assertTrue(essence.getRole() == VillagerRole.BUILDER,
                    "an assigned builder must keep the builder role, not become "
                            + essence.getRole().lowerName() + " from a nearby jobsite");
            helper.assertTrue(essence.getAssignedSiteId() == site.id(),
                    "its build assignment must survive");
            helper.succeed();
        });
    }

    /** Reverse: a villager that gains a mapped profession auto-gains the DV role. */
    @GameTest(template = "empty5x5")
    public static void gaining_mapped_profession_grants_role(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        helper.assertTrue(VillagerEssence.get(villager).getRole() == VillagerRole.NONE,
                "a fresh villager has no role");
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
        helper.assertTrue(VillagerEssence.get(villager).getRole() == VillagerRole.FARMER,
                "employing a villager as a vanilla farmer should grant the farmer role");
        helper.succeed();
    }

    /** Reverse: losing a profession drops the role back to NONE. */
    @GameTest(template = "empty5x5")
    public static void losing_profession_clears_role(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.MASON));
        helper.assertTrue(VillagerEssence.get(villager).getRole() == VillagerRole.BUILDER,
                "a mason should be a builder");
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.NONE));
        helper.assertTrue(VillagerEssence.get(villager).getRole() == VillagerRole.NONE,
                "losing the profession should clear the role");
        helper.succeed();
    }

    /** An unmapped vanilla job (librarian) leaves any existing role untouched. */
    @GameTest(template = "empty5x5")
    public static void unmapped_profession_leaves_role_alone(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.LIBRARIAN));
        helper.assertTrue(VillagerEssence.get(villager).getRole() == VillagerRole.NONE,
                "a librarian is not a DV worker — role stays none");
        helper.succeed();
    }

    /** A villager with no role never claims a job site on our account (pure vanilla stays vanilla). */
    @GameTest(template = "empty11x11", timeoutTicks = 400, batch = "dvProfessionNoRole")
    public static void roleless_villager_does_not_seek_our_way(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.SMITHING_TABLE);
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 3));
        // no role set; vanilla's own AcquirePoi may still employ it, but our seek must not
        // force a specific profession — assert we didn't hand it the miner role out of band
        helper.runAfterDelay(200, () -> {
            VillagerProfession profession = villager.getVillagerData().getProfession();
            VillagerRole role = VillagerEssence.get(villager).getRole();
            // whatever vanilla did, role and profession must agree (never a role without its
            // profession) — the invariant the integration guarantees
            if (role == VillagerRole.NONE) {
                helper.assertTrue(RoleProfessions.roleFor(profession) == null
                                || profession == VillagerProfession.NONE,
                        "no DV role means no mapped profession claimed on our behalf");
            } else {
                helper.assertTrue(RoleProfessions.professionFor(role) == profession,
                        "any role the villager holds must match its profession");
            }
            helper.succeed();
        });
    }
}
