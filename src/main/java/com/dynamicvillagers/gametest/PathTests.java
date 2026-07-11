package com.dynamicvillagers.gametest;

import com.dynamicvillagers.DynamicVillagers;
import com.dynamicvillagers.village.ConstructionLedger;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.role.VillagerRole;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Milestone 4.6 path-building tests. Like construction, planning stops at night, so the
 * shared gametest clock is pinned to noon.
 */
@GameTestHolder(DynamicVillagers.MOD_ID)
@PrefixGameTestTemplate(false)
public class PathTests {

    private static void perpetualDay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.setDayTime(6000);
    }

    /** A builder flattens a grass line into a continuous dirt path with a shovel. */
    @GameTest(template = "empty11x11", timeoutTicks = 6000, batch = "dvPathBuild")
    public static void builder_flattens_a_grass_path(GameTestHelper helper) {
        ConstructionLedger ledger = ConstructionLedger.get(helper.getLevel());
        ledger.clear();
        perpetualDay(helper);

        // a grass strip at floor level for the path to follow
        for (int x = 1; x <= 8; x++) {
            helper.setBlock(new BlockPos(x, 1, 3), Blocks.GRASS_BLOCK);
        }

        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.IRON_SHOVEL, 1));

        ConstructionLedger.PathSite path = ledger.addPath(
                java.util.List.of(helper.absolutePos(new BlockPos(1, 1, 3)),
                        helper.absolutePos(new BlockPos(8, 1, 3))),
                helper.getLevel().getGameTime());
        essence.setRole(VillagerRole.BUILDER);
        essence.setAssignedPathId(path.id());

        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.DIRT_PATH, new BlockPos(2, 1, 3));
            helper.assertBlockPresent(Blocks.DIRT_PATH, new BlockPos(5, 1, 3));
            helper.assertBlockPresent(Blocks.DIRT_PATH, new BlockPos(7, 1, 3));
            helper.assertTrue(path.status() == ConstructionLedger.Status.DONE,
                    "the path should be marked DONE");
            helper.assertTrue(essence.getAssignedPathId() == -1,
                    "a finished path clears the assignment");
        });
    }

    /** A 1-deep gap in the line is filled with dirt and then flattened. */
    @GameTest(template = "empty11x11", timeoutTicks = 6000, batch = "dvPathGap")
    public static void builder_fills_a_gap_in_the_path(GameTestHelper helper) {
        ConstructionLedger ledger = ConstructionLedger.get(helper.getLevel());
        ledger.clear();
        perpetualDay(helper);

        for (int x = 1; x <= 6; x++) {
            helper.setBlock(new BlockPos(x, 1, 5), Blocks.DIRT);
        }
        helper.setBlock(new BlockPos(4, 1, 5), Blocks.AIR); // a pothole in the line

        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.IRON_SHOVEL, 1));
        essence.getExtraInventory().setItem(1, new ItemStack(Items.DIRT, 16));

        ConstructionLedger.PathSite path = ledger.addPath(
                java.util.List.of(helper.absolutePos(new BlockPos(1, 1, 5)),
                        helper.absolutePos(new BlockPos(6, 1, 5))),
                helper.getLevel().getGameTime());
        essence.setRole(VillagerRole.BUILDER);
        essence.setAssignedPathId(path.id());

        helper.succeedWhen(() ->
                helper.assertBlockPresent(Blocks.DIRT_PATH, new BlockPos(4, 1, 5))); // filled + flattened
    }

    @GameTest(template = "empty5x5")
    public static void path_site_persists(GameTestHelper helper) {
        ConstructionLedger ledger = ConstructionLedger.get(helper.getLevel());
        ledger.clear();
        ConstructionLedger.PathSite path = ledger.addPath(
                java.util.List.of(new BlockPos(0, 64, 0), new BlockPos(4, 64, 0), new BlockPos(4, 64, 4)),
                helper.getLevel().getGameTime());
        helper.assertTrue(ledger.getPath(path.id()) == path, "the path is retrievable by id");
        helper.assertTrue(path.waypoints().size() == 3, "all three waypoints are kept");
        helper.assertTrue(ledger.cancelPath(path.id()) && ledger.getPath(path.id()) == null,
                "cancelling removes the path");
        ledger.clear();
        helper.succeed();
    }
}
