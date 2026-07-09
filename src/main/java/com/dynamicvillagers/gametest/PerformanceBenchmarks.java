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
