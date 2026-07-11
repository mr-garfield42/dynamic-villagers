package com.dynamicvillagers.gametest;

import com.dynamicvillagers.DynamicVillagers;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.role.VillagerRole;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Phase 2.7 gate: worst-case tick cost of 50 roled villagers that never find work — every
 * failed plan runs the full tree scan plus the torch-chore scan on backoff, which is the
 * most expensive idle path we have. Numbers are logged for the phase report; the assertion
 * only guards against a catastrophic regression. The plain-villager twin measures the same
 * scene without roles, so the difference is Dynamic Villagers' overhead.
 */
@GameTestHolder(DynamicVillagers.MOD_ID)
@PrefixGameTestTemplate(false)
public class PerformanceBenchmarks {

    private static final int VILLAGERS = 50;
    private static final int WARMUP_TICKS = 200;
    private static final int MEASURE_TICKS = 1000;
    private static final double MAX_MS_PER_TICK = 45.0;

    @GameTest(template = "empty5x5", timeoutTicks = 1600, batch = "dvBenchPlain")
    public static void benchmark_50_plain_villagers(GameTestHelper helper) {
        runBenchmark(helper, "50 plain villagers (baseline)", VillagerRole.NONE);
    }

    @GameTest(template = "empty5x5", timeoutTicks = 1600, batch = "dvBenchRoled")
    public static void benchmark_50_idle_lumberjacks(GameTestHelper helper) {
        runBenchmark(helper, "50 idle lumberjacks (worst-case scans)", VillagerRole.LUMBERJACK);
    }

    @GameTest(template = "empty5x5", timeoutTicks = 1600, batch = "dvBenchBuilders")
    public static void benchmark_50_idle_builders(GameTestHelper helper) {
        runBenchmark(helper, "50 idle builders (no site — planner idle cost)", VillagerRole.BUILDER);
    }

    /** The owner-facing number: how long one villager takes to hand-build a vanilla house. */
    @GameTest(template = "empty11x11", timeoutTicks = 40000, batch = "dvBenchHouse")
    public static void benchmark_vanilla_house_build_time(GameTestHelper helper) {
        com.dynamicvillagers.village.StorageLedger.get(helper.getLevel()).clear();
        var ledger = com.dynamicvillagers.village.ConstructionLedger.get(helper.getLevel());
        ledger.clear();
        helper.getLevel().getGameRules()
                .getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT)
                .set(false, helper.getLevel().getServer());
        helper.getLevel().setDayTime(6000);

        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(0, 2, 0));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_STAIRS, 64));
        essence.getExtraInventory().setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 64));
        essence.getExtraInventory().setItem(2, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, 64));
        essence.getExtraInventory().setItem(3, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STRIPPED_OAK_LOG, 32));
        essence.getExtraInventory().setItem(4, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GLASS_PANE, 16));
        essence.getExtraInventory().setItem(5, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TORCH, 16));
        essence.getExtraInventory().setItem(6, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_DOOR, 1));
        essence.getExtraInventory().setItem(7, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHITE_BED, 1));
        essence.getExtraInventory().setItem(8, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT, 64));

        BlockPos origin = helper.absolutePos(new BlockPos(2, 2, 2));
        var house = net.minecraft.resources.ResourceLocation.withDefaultNamespace(
                "village/plains/houses/plains_small_house_1");
        var site = ledger.addSite(house, origin, net.minecraft.world.level.block.Rotation.NONE,
                helper.getLevel().getGameTime());
        essence.setRole(VillagerRole.BUILDER);
        essence.setAssignedSiteId(site.id());
        var blueprint = com.dynamicvillagers.construction.Blueprints.load(helper.getLevel(), house);
        long start = helper.getLevel().getGameTime();
        int[] milestone = {0};

        helper.onEachTick(() -> {
            int total = 0;
            int matched = 0;
            for (var plan : blueprint.placedBlocks(origin, net.minecraft.world.level.block.Rotation.NONE)) {
                if (plan.state().isAir()
                        || com.dynamicvillagers.construction.BlockRequirements.isDependentPart(plan.state())) {
                    continue;
                }
                total++;
                if (com.dynamicvillagers.construction.BlockMatch.matches(
                        helper.getLevel().getBlockState(plan.pos()), plan.state())) {
                    matched++;
                }
            }
            long elapsed = helper.getLevel().getGameTime() - start;
            int pct = matched * 100 / total;
            while (milestone[0] < 5 && pct >= (milestone[0] + 1) * 20) {
                milestone[0]++;
                DynamicVillagers.LOGGER.info("[DV house-time] {}% built at {} ticks ({}/{})",
                        milestone[0] * 20, elapsed, matched, total);
            }
            if (pct >= 97) {
                DynamicVillagers.LOGGER.info("[DV house-time] DONE (>=97%) at {} ticks = {} min", elapsed,
                        String.format("%.1f", elapsed / 1200.0));
                helper.succeed();
            }
        });
    }

    private static void runBenchmark(GameTestHelper helper, String label, VillagerRole role) {
        helper.getLevel().setDayTime(1000);
        for (int i = 0; i < VILLAGERS; i++) {
            Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
            VillagerEssence.get(villager).setRole(role);
        }
        int[] ticks = {0};
        long[] startNanos = {0};
        helper.onEachTick(() -> {
            ticks[0]++;
            if (ticks[0] == WARMUP_TICKS) {
                startNanos[0] = System.nanoTime();
            }
            if (ticks[0] == WARMUP_TICKS + MEASURE_TICKS) {
                double msPerTick = (System.nanoTime() - startNanos[0]) / 1_000_000.0 / MEASURE_TICKS;
                DynamicVillagers.LOGGER.info("[DV benchmark] {}: {} ms/tick over {} ticks",
                        label, String.format("%.3f", msPerTick), MEASURE_TICKS);
                helper.assertTrue(msPerTick < MAX_MS_PER_TICK,
                        "benchmark exceeded " + MAX_MS_PER_TICK + " ms/tick: " + msPerTick);
                helper.succeed();
            }
        });
    }
}
