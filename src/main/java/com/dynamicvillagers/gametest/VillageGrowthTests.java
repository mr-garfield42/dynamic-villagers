package com.dynamicvillagers.gametest;

import com.dynamicvillagers.DynamicVillagers;
import com.dynamicvillagers.network.VillageDebugStatePayload;
import com.dynamicvillagers.village.ConstructionLedger;
import com.dynamicvillagers.village.StorageLedger;
import com.dynamicvillagers.village.Village;
import com.dynamicvillagers.village.VillageAnchor;
import com.dynamicvillagers.village.VillageManager;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.behavior.PlanWorkBehavior;
import com.dynamicvillagers.villager.role.LumberjackPlanner;
import com.dynamicvillagers.villager.role.MinerPlanner;
import com.dynamicvillagers.villager.role.VillagerRole;
import com.dynamicvillagers.villager.role.WorkerTools;
import com.dynamicvillagers.villager.task.ExploreForTreesTask;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

@GameTestHolder(DynamicVillagers.MOD_ID)
@PrefixGameTestTemplate(false)
public class VillageGrowthTests {
    private static final BlockPos BELL = new BlockPos(5, 2, 5);

    @GameTest(template = "empty11x11", timeoutTicks = 400, batch = "dvGrowthPopulation")
    public static void natural_village_seed_reaches_25_once(GameTestHelper helper) {
        prepare(helper, true);
        VillageManager manager = VillageManager.get(helper.getLevel());
        Village village = manager.create(helper.getLevel(), helper.absolutePos(BELL));
        for (int i = 0; i < 5; i++) {
            Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(3 + i, 2, 5));
            manager.adopt(helper.getLevel(), villager, village);
        }
        manager.refreshTallies(helper.getLevel(), village);
        for (int i = 0; i < 6; i++) manager.seedInitialPopulation(helper.getLevel(), village);
        helper.assertTrue(village.population() == 25, "initial village population should reach 25");
        helper.assertTrue(village.initialPopulationSeeded(), "the one-time population seed should persist");

        Villager removed = villagers(helper, village).getFirst();
        manager.removeMember(removed.getUUID());
        removed.discard();
        manager.refreshTallies(helper.getLevel(), village);
        manager.seedInitialPopulation(helper.getLevel(), village);
        helper.assertTrue(village.population() == 24, "a seeded village must not respawn later deaths");
        cleanup(helper);
        helper.succeed();
    }

    @GameTest(template = "empty11x11", timeoutTicks = 600, batch = "dvGrowthStaffing")
    public static void population_25_gets_five_lumberjacks_and_two_miners_promptly(GameTestHelper helper) {
        prepare(helper, true);
        VillageManager manager = VillageManager.get(helper.getLevel());
        Village village = manager.create(helper.getLevel(), helper.absolutePos(BELL));
        for (int i = 0; i < 25; i++) {
            Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2 + i % 7, 2, 2 + i / 7));
            manager.adopt(helper.getLevel(), villager, village);
        }
        village.setInitialPopulationSeeded(true);
        long start = helper.getLevel().getGameTime();
        helper.succeedWhen(() -> {
            manager.refreshTallies(helper.getLevel(), village);
            helper.assertTrue(village.roles().getOrDefault(VillagerRole.LUMBERJACK, 0) >= 5,
                    "a 25-person village should staff at least five lumberjacks");
            helper.assertTrue(village.roles().getOrDefault(VillagerRole.MINER, 0) >= 2,
                    "a 25-person village should staff at least two miners");
            helper.assertTrue(villagers(helper, village).stream()
                            .filter(v -> VillagerEssence.get(v).getRole() == VillagerRole.MINER)
                            .allMatch(v -> VillagerEssence.get(v).getQuarrySite() != null),
                    "manager-staffed miners should receive starter quarries");
            helper.assertTrue(helper.getLevel().getGameTime() - start <= 400,
                    "initial staffing should finish within 20 seconds");
            cleanup(helper);
        });
    }

    @GameTest(template = "empty11x11", timeoutTicks = 500, batch = "dvGrowthProductivity")
    public static void manager_staffed_lumberjacks_start_work_despite_rest_activity(GameTestHelper helper) {
        prepare(helper, false);
        VillageManager manager = VillageManager.get(helper.getLevel());
        Village village = manager.create(helper.getLevel(), helper.absolutePos(BELL));
        for (int i = 0; i < 25; i++) {
            Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(1 + i % 3, 2, 1 + i / 3));
            manager.adopt(helper.getLevel(), villager, village);
        }
        village.setInitialPopulationSeeded(true);
        BlockPos tree = new BlockPos(9, 2, 5);
        helper.setBlock(tree, Blocks.OAK_LOG);
        helper.setBlock(tree.above(), Blocks.OAK_LOG);
        helper.setBlock(tree.above(2), Blocks.OAK_LEAVES);
        helper.setBlock(tree.above(2).north(), Blocks.OAK_LEAVES);
        helper.setBlock(tree.above(2).south(), Blocks.OAK_LEAVES);
        helper.setBlock(tree.above(2).east(), Blocks.OAK_LEAVES);
        helper.setBlock(tree.above(2).west(), Blocks.OAK_LEAVES);
        long start = helper.getLevel().getGameTime();
        long[] firstTask = {-1};
        helper.onEachTick(() -> {
            for (Villager villager : villagers(helper, village)) {
                VillagerEssence essence = VillagerEssence.get(villager);
                if (essence.getRole() == VillagerRole.LUMBERJACK) {
                    villager.getBrain().setActiveActivityIfPossible(Activity.REST);
                    if (!essence.getTaskQueue().isEmpty() && firstTask[0] < 0) {
                        firstTask[0] = helper.getLevel().getGameTime();
                    }
                }
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(firstTask[0] >= 0 && firstTask[0] - start <= 100,
                    "a manager-staffed lumberjack should receive work within five seconds");
            helper.assertTrue(!helper.getBlockState(tree).is(Blocks.OAK_LOG)
                            && !helper.getBlockState(tree.above()).is(Blocks.OAK_LOG),
                    "a nearby tree should be felled instead of workers clustering at a house");
            helper.assertTrue(helper.getLevel().getGameTime() - start <= 400,
                    "staffing and felling a nearby tree should finish within twenty seconds");
            cleanup(helper);
        });
    }

    @GameTest(template = "empty11x11", timeoutTicks = 100, batch = "dvGrowthReplan")
    public static void forced_replan_queues_nearby_work_immediately(GameTestHelper helper) {
        prepare(helper, false);
        Villager lumberjack = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 5));
        VillagerEssence essence = VillagerEssence.get(lumberjack);
        essence.setRole(VillagerRole.LUMBERJACK);
        essence.setNextPlanTime(helper.getLevel().getGameTime() + 10_000);
        lumberjack.getBrain().setActiveActivityIfPossible(Activity.REST);
        BlockPos tree = new BlockPos(9, 2, 5);
        helper.setBlock(tree, Blocks.OAK_LOG);
        helper.setBlock(tree.above(), Blocks.OAK_LOG);
        helper.setBlock(tree.above(2), Blocks.OAK_LEAVES);
        helper.setBlock(tree.above(2).north(), Blocks.OAK_LEAVES);
        helper.setBlock(tree.above(2).south(), Blocks.OAK_LEAVES);
        helper.setBlock(tree.above(2).east(), Blocks.OAK_LEAVES);
        helper.setBlock(tree.above(2).west(), Blocks.OAK_LEAVES);

        boolean planned = PlanWorkBehavior.tryPlan(helper.getLevel(), lumberjack, true);
        helper.assertTrue(planned && !essence.getTaskQueue().isEmpty(),
                "Replan should queue visible work immediately despite cooldown or REST activity");
        cleanup(helper);
        helper.succeed();
    }

    @GameTest(template = "empty11x11", timeoutTicks = 200, batch = "dvGrowthGuards")
    public static void guards_receive_names_and_appear_in_village_debug(GameTestHelper helper) {
        prepare(helper, false);
        Village village = VillageManager.get(helper.getLevel()).create(helper.getLevel(), helper.absolutePos(BELL));
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(
                ResourceLocation.fromNamespaceAndPath("guardvillagers", "guard")).orElseThrow();
        Entity guard = type.create(helper.getLevel());
        helper.assertTrue(guard != null, "Guard Villagers should provide its guard entity");
        BlockPos pos = helper.absolutePos(new BlockPos(6, 2, 5));
        guard.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        helper.getLevel().addFreshEntity(guard);
        VillageDebugStatePayload state = VillageDebugStatePayload.snapshot(helper.getLevel(), village);
        helper.assertTrue(guard.hasCustomName() && guard.isCustomNameVisible(),
                "guards should receive visible generated names");
        helper.assertTrue(state.guards() == 1, "the village inspector should count nearby guards");

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), helper.getLevel().registryAccess());
        VillageDebugStatePayload.STREAM_CODEC.encode(buf, state);
        helper.assertTrue(VillageDebugStatePayload.STREAM_CODEC.decode(buf).guards() == 1,
                "guard count should survive the inspector network payload");
        cleanup(helper);
        helper.succeed();
    }

    @GameTest(template = "empty11x11", timeoutTicks = 1000, batch = "dvGrowthStorage")
    public static void lumberjack_crafts_axe_and_public_storage_promptly(GameTestHelper helper) {
        prepare(helper, false);
        for (int x = 2; x <= 8; x++) {
            for (int z = 2; z <= 8; z++) helper.setBlock(new BlockPos(x, 4, z), Blocks.COBBLESTONE);
        }
        VillageManager manager = VillageManager.get(helper.getLevel());
        Village village = manager.create(helper.getLevel(), helper.absolutePos(BELL));
        Villager lumberjack = helper.spawn(EntityType.VILLAGER, new BlockPos(5, 2, 6));
        VillagerEssence essence = VillagerEssence.get(lumberjack);
        manager.adopt(helper.getLevel(), lumberjack, village);
        essence.setRole(VillagerRole.LUMBERJACK);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.OAK_LOG, 8));
        long start = helper.getLevel().getGameTime();
        helper.succeedWhen(() -> {
            List<java.util.Map.Entry<BlockPos, StorageLedger.ContainerRecord>> storage =
                    StorageLedger.get(helper.getLevel()).recordsNear(village.center(), 8).stream()
                            .filter(entry -> entry.getValue().villageId() == village.id())
                            .filter(entry -> entry.getValue().designation() == StorageLedger.Designation.PUBLIC)
                            .toList();
            helper.assertTrue(!storage.isEmpty() && helper.getLevel().getBlockState(storage.getFirst().getKey()).is(Blocks.CHEST),
                    "the lead lumberjack should physically place a public chest near the bell; tasks="
                            + essence.getTaskQueue().tasks().stream().map(task -> task.typeId()).toList()
                            + ", carried logs/planks/table/chest/axe=" + carried(lumberjack, Items.OAK_LOG)
                            + "/" + carried(lumberjack, Items.OAK_PLANKS)
                            + "/" + carried(lumberjack, Items.CRAFTING_TABLE)
                            + "/" + carried(lumberjack, Items.CHEST)
                            + "/" + carried(lumberjack, Items.WOODEN_AXE)
                            + ", tables=" + countBlocks(helper, Blocks.CRAFTING_TABLE, 12)
                            + ", chests=" + countBlocks(helper, Blocks.CHEST, 12)
                            + ", pos=" + lumberjack.blockPosition()
                            + ", taskData=" + (essence.getTaskQueue().current() == null ? "none"
                            : essence.getTaskQueue().current().save(helper.getLevel().registryAccess())));
            helper.assertTrue(storage.getFirst().getKey().getY() <= village.center().getY() + 2,
                    "public storage should be placed on village ground, not on a well or house roof");
            helper.assertTrue(carried(lumberjack, Items.WOODEN_AXE) > 0,
                    "the lumberjack should craft and retain a wooden axe");
            helper.assertTrue(helper.getLevel().getGameTime() - start <= 600,
                    "a nearby chest and wooden axe should take less than 30 seconds");
            cleanup(helper);
        });
    }

    @GameTest(template = "empty11x11", timeoutTicks = 600, batch = "dvGrowthTreeSearch")
    public static void exploring_lumberjack_walks_until_it_sees_and_remembers_trees(GameTestHelper helper) {
        prepare(helper, false);
        Villager lumberjack = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 5));
        VillagerEssence essence = VillagerEssence.get(lumberjack);
        BlockPos tree = new BlockPos(9, 2, 5);
        helper.setBlock(tree, Blocks.OAK_LOG);
        helper.setBlock(tree.above(), Blocks.OAK_LOG);
        helper.setBlock(tree.above(2), Blocks.OAK_LEAVES);
        helper.setBlock(tree.above(2).north(), Blocks.OAK_LEAVES);
        helper.setBlock(tree.above(2).south(), Blocks.OAK_LEAVES);
        helper.setBlock(tree.above(2).east(), Blocks.OAK_LEAVES);
        helper.setBlock(tree.above(2).west(), Blocks.OAK_LEAVES);
        essence.getTaskQueue().enqueue(new ExploreForTreesTask(helper.absolutePos(new BlockPos(8, 2, 5))));
        long start = helper.getLevel().getGameTime();
        helper.succeedWhen(() -> {
            helper.assertTrue(!essence.getMemory().knownSpots(LumberjackPlanner.TREE_SPOT).isEmpty(),
                    "the exploring lumberjack should remember the tree it saw");
            helper.assertTrue(lumberjack.getX() > helper.absolutePos(new BlockPos(2, 2, 5)).getX() + 0.5,
                    "the lumberjack should physically walk toward the distant tree");
            helper.assertTrue(helper.getLevel().getGameTime() - start <= 300,
                    "a visible tree should be found within 15 seconds");
            cleanup(helper);
        });
    }

    @GameTest(template = "empty11x11", timeoutTicks = 100, batch = "dvGrowthTreeSearchRange")
    public static void lumberjack_exploration_uses_a_larger_search_step(GameTestHelper helper) {
        prepare(helper, false);
        Villager lumberjack = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 5));
        VillagerEssence essence = VillagerEssence.get(lumberjack);
        essence.setRole(VillagerRole.LUMBERJACK);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.WOODEN_AXE));
        helper.assertTrue(new LumberjackPlanner().plan(helper.getLevel(), lumberjack, essence),
                "a lumberjack without known trees should plan exploration");
        helper.assertTrue(essence.getTaskQueue().current() instanceof ExploreForTreesTask,
                "the planned work should be tree exploration");
        CompoundTag saved = essence.getTaskQueue().current().save(helper.getLevel().registryAccess());
        BlockPos target = BlockPos.of(saved.getLong("target"));
        BlockPos anchor = VillageAnchor.resolve(helper.getLevel(), lumberjack);
        helper.assertTrue(Math.sqrt(target.distSqr(anchor)) >= 32.0,
                "each exploration leg should cover at least 32 blocks");
        cleanup(helper);
        helper.succeed();
    }

    @GameTest(template = "empty11x11", timeoutTicks = 300, batch = "dvGrowthTreeBroadcast")
    public static void discovered_trees_stop_other_lumberjacks_exploring(GameTestHelper helper) {
        prepare(helper, false);
        VillageManager manager = VillageManager.get(helper.getLevel());
        Village village = manager.create(helper.getLevel(), helper.absolutePos(BELL));
        manager.setAutoStaff(village, false);
        BlockPos tree = new BlockPos(9, 2, 5);
        helper.setBlock(tree, Blocks.OAK_LOG);
        helper.setBlock(tree.above(), Blocks.OAK_LOG);
        helper.setBlock(tree.above(2), Blocks.OAK_LEAVES);
        helper.setBlock(tree.above(2).north(), Blocks.OAK_LEAVES);
        helper.setBlock(tree.above(2).south(), Blocks.OAK_LEAVES);
        helper.setBlock(tree.above(2).east(), Blocks.OAK_LEAVES);
        helper.setBlock(tree.above(2).west(), Blocks.OAK_LEAVES);

        Villager finder = helper.spawn(EntityType.VILLAGER, new BlockPos(5, 2, 5));
        Villager other = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        manager.adopt(helper.getLevel(), finder, village);
        manager.adopt(helper.getLevel(), other, village);
        VillagerEssence finderEssence = VillagerEssence.get(finder);
        VillagerEssence otherEssence = VillagerEssence.get(other);
        finderEssence.setRole(VillagerRole.LUMBERJACK);
        otherEssence.setRole(VillagerRole.LUMBERJACK);
        finderEssence.getTaskQueue().enqueue(new ExploreForTreesTask(helper.absolutePos(new BlockPos(9, 2, 5))));
        otherEssence.getTaskQueue().enqueue(new ExploreForTreesTask(helper.absolutePos(new BlockPos(1, 2, 9))));

        helper.succeedWhen(() -> {
            helper.assertTrue(otherEssence.getMemory().knownSpots(LumberjackPlanner.TREE_SPOT)
                            .contains(helper.absolutePos(tree)),
                    "a discovered grove should be shared with other village lumberjacks");
            helper.assertTrue(!(otherEssence.getTaskQueue().current() instanceof ExploreForTreesTask),
                    "other lumberjacks should stop exploring once a grove is reported");
            cleanup(helper);
        });
    }

    @GameTest(template = "empty11x11", timeoutTicks = 900, batch = "dvGrowthSharedTables")
    public static void workers_share_one_spread_out_crafting_table(GameTestHelper helper) {
        prepare(helper, false);
        VillageManager manager = VillageManager.get(helper.getLevel());
        Village village = manager.create(helper.getLevel(), helper.absolutePos(BELL));
        manager.setAutoStaff(village, false);
        Villager first = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 2, 5));
        Villager second = helper.spawn(EntityType.VILLAGER, new BlockPos(7, 2, 5));
        manager.adopt(helper.getLevel(), first, village);
        manager.adopt(helper.getLevel(), second, village);
        for (Villager worker : List.of(first, second)) {
            VillagerEssence essence = VillagerEssence.get(worker);
            essence.getExtraInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 7));
            essence.getExtraInventory().setItem(1, new ItemStack(Items.STICK, 2));
            WorkerTools.planCraftedItem(helper.getLevel(), worker, essence, Items.WOODEN_PICKAXE, 1);
        }
        helper.onEachTick(() -> {
            for (Villager worker : List.of(first, second)) {
                VillagerEssence essence = VillagerEssence.get(worker);
                if (essence.getTaskQueue().isEmpty() && carried(worker, Items.WOODEN_PICKAXE) == 0) {
                    WorkerTools.planCraftedItem(helper.getLevel(), worker, essence, Items.WOODEN_PICKAXE, 1);
                }
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(carried(first, Items.WOODEN_PICKAXE) == 1
                            && carried(second, Items.WOODEN_PICKAXE) == 1,
                    "both workers should craft at the shared village station");
            helper.assertTrue(countBlocks(helper, Blocks.CRAFTING_TABLE, 12) == 1,
                    "workers in the same village area should place only one crafting table");
            cleanup(helper);
        });
    }

    @GameTest(template = "empty11x11", timeoutTicks = 900, batch = "dvGrowthStorageExpansion")
    public static void full_public_storage_causes_another_chest_to_be_built(GameTestHelper helper) {
        prepare(helper, false);
        VillageManager manager = VillageManager.get(helper.getLevel());
        Village village = manager.create(helper.getLevel(), helper.absolutePos(BELL));
        manager.setAutoStaff(village, false);
        BlockPos fullChest = new BlockPos(2, 2, 2);
        helper.setBlock(fullChest, Blocks.CHEST);
        Container container = (Container) helper.getBlockEntity(fullChest);
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            container.setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        BlockPos absoluteChest = helper.absolutePos(fullChest);
        StorageLedger ledger = StorageLedger.get(helper.getLevel());
        ledger.setDesignation(absoluteChest, StorageLedger.Designation.PUBLIC, null, village.id());
        ledger.recordSnapshot(absoluteChest, container, helper.getLevel().getGameTime());

        Villager lumberjack = helper.spawn(EntityType.VILLAGER, new BlockPos(5, 2, 6));
        manager.adopt(helper.getLevel(), lumberjack, village);
        VillagerEssence essence = VillagerEssence.get(lumberjack);
        essence.setRole(VillagerRole.LUMBERJACK);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.WOODEN_AXE));
        essence.getExtraInventory().setItem(1, new ItemStack(Items.CHEST));
        helper.assertTrue(new LumberjackPlanner().plan(helper.getLevel(), lumberjack, essence),
                "a full public chest should immediately queue storage expansion");

        helper.succeedWhen(() -> {
            List<BlockPos> publicChests = ledger.recordsNear(village.center(), village.radius()).stream()
                    .filter(entry -> entry.getValue().villageId() == village.id())
                    .filter(entry -> entry.getValue().designation() == StorageLedger.Designation.PUBLIC)
                    .map(java.util.Map.Entry::getKey)
                    .filter(pos -> helper.getLevel().getBlockState(pos).is(Blocks.CHEST))
                    .toList();
            helper.assertTrue(publicChests.size() >= 2,
                    "a full public chest should cause the lead lumberjack to add storage");
            helper.assertTrue(publicChests.stream().anyMatch(pos -> pos.distManhattan(absoluteChest) > 1),
                    "expanded storage should be a separate accessible chest");
            cleanup(helper);
        });
    }

    @GameTest(template = "empty11x11", timeoutTicks = 1200, batch = "dvGrowthMining")
    public static void miner_crafts_wood_then_stone_pickaxe_and_mines_iron_promptly(GameTestHelper helper) {
        prepare(helper, false);
        helper.setBlock(new BlockPos(2, 2, 4), Blocks.CRAFTING_TABLE);
        Villager miner = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 5));
        VillagerEssence essence = VillagerEssence.get(miner);
        essence.setRole(VillagerRole.MINER);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.OAK_LOG, 4));
        for (int x = 3; x <= 5; x++) {
            for (int z = 4; z <= 6; z++) helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
        }
        essence.setQuarrySite(new VillagerEssence.QuarrySite(
                helper.absolutePos(new BlockPos(3, 2, 4)), helper.absolutePos(new BlockPos(5, 2, 6))));
        helper.setBlock(new BlockPos(7, 2, 5), Blocks.IRON_ORE);
        helper.setBlock(new BlockPos(7, 2, 4), Blocks.GLOWSTONE);
        long start = helper.getLevel().getGameTime();
        helper.succeedWhen(() -> {
            helper.assertTrue(carried(miner, Items.WOODEN_PICKAXE) > 0,
                    "the miner should first craft a wooden pickaxe");
            helper.assertTrue(carried(miner, Items.STONE_PICKAXE) > 0,
                    "cobblestone should be upgraded into a stone pickaxe");
            helper.assertTrue(carried(miner, Items.RAW_IRON) + ground(helper, Items.RAW_IRON, 20) > 0,
                    "the stone-equipped miner should mine the visible iron ore");
            helper.assertTrue(helper.getLevel().getGameTime() - start <= 800,
                    "wood-to-stone-to-iron progression should finish within 40 seconds");
            cleanup(helper);
        });
    }

    @GameTest(template = "empty11x11", timeoutTicks = 1000, batch = "dvGrowthMinerStarts")
    public static void managed_miner_with_a_wooden_pickaxe_claims_and_works_a_starter_quarry(GameTestHelper helper) {
        prepare(helper, false);
        VillageManager manager = VillageManager.get(helper.getLevel());
        Village village = manager.create(helper.getLevel(), helper.absolutePos(BELL));
        manager.setAutoStaff(village, false);
        Villager miner = helper.spawn(EntityType.VILLAGER, new BlockPos(5, 2, 6));
        manager.adopt(helper.getLevel(), miner, village);
        VillagerEssence essence = VillagerEssence.get(miner);
        essence.setRole(VillagerRole.MINER);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
        helper.assertTrue(new MinerPlanner().plan(helper.getLevel(), miner, essence)
                        && essence.getQuarrySite() != null,
                "a managed miner with a wooden pickaxe should immediately receive a starter quarry");
        essence.getTaskQueue().clear();
        for (int x = 7; x <= 9; x++) {
            for (int z = 7; z <= 9; z++) helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
        }
        essence.setQuarrySite(new VillagerEssence.QuarrySite(
                helper.absolutePos(new BlockPos(7, 2, 7)), helper.absolutePos(new BlockPos(9, 1, 9))));
        helper.assertTrue(new MinerPlanner().plan(helper.getLevel(), miner, essence),
                "the assigned miner should queue nearby quarry work rather than idle");
        long started = helper.getLevel().getGameTime();

        helper.succeedWhen(() -> {
            BlockPos firstWorkBlock = essence.getQuarrySite().cornerA().south();
            helper.assertTrue(!helper.getLevel().getBlockState(firstWorkBlock).is(Blocks.STONE),
                    "a wooden-pickaxe miner should leave the village and begin quarrying stone; site="
                            + essence.getQuarrySite() + ", work=" + firstWorkBlock
                            + ", state=" + helper.getLevel().getBlockState(firstWorkBlock)
                            + ", tasks=" + essence.getTaskQueue().tasks().stream()
                            .map(task -> task.typeId()).toList() + ", pos=" + miner.blockPosition());
            helper.assertTrue(helper.getLevel().getGameTime() - started <= 600,
                    "starter quarry work should begin within thirty seconds");
            cleanup(helper);
        });
    }

    @GameTest(template = "empty11x11", timeoutTicks = 2200, batch = "dvGrowthStoneSupply")
    public static void stored_wood_wakes_miner_who_crafts_mines_and_deposits_stone(GameTestHelper helper) {
        prepare(helper, false);
        VillageManager manager = VillageManager.get(helper.getLevel());
        Village village = manager.create(helper.getLevel(), helper.absolutePos(BELL));
        manager.setAutoStaff(village, false);
        BlockPos chestPos = new BlockPos(1, 2, 1);
        helper.setBlock(chestPos, Blocks.CHEST);
        helper.setBlock(new BlockPos(2, 2, 1), Blocks.CRAFTING_TABLE);
        helper.setBlock(new BlockPos(5, 3, 4), Blocks.GLOWSTONE);
        Container chest = (Container) helper.getBlockEntity(chestPos);
        chest.setItem(0, new ItemStack(Items.OAK_LOG, 8));
        BlockPos absoluteChest = helper.absolutePos(chestPos);
        StorageLedger storage = StorageLedger.get(helper.getLevel());
        storage.setDesignation(absoluteChest, StorageLedger.Designation.PUBLIC, null, village.id());
        storage.recordSnapshot(absoluteChest, chest, helper.getLevel().getGameTime());

        Villager miner = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 2, 5));
        manager.adopt(helper.getLevel(), miner, village);
        VillagerEssence essence = VillagerEssence.get(miner);
        essence.setRole(VillagerRole.MINER);
        essence.setNextPlanTime(helper.getLevel().getGameTime() + 10_000);
        for (int x = 6; x <= 9; x++) {
            for (int z = 2; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, 3, z), Blocks.STONE);
            }
        }
        essence.setQuarrySite(new VillagerEssence.QuarrySite(
                helper.absolutePos(new BlockPos(6, 3, 2)), helper.absolutePos(new BlockPos(9, 2, 6))));
        BlockPos start = miner.blockPosition();
        long began = helper.getLevel().getGameTime();
        long[] firstTask = {-1};
        helper.onEachTick(() -> {
            if (firstTask[0] < 0 && !essence.getTaskQueue().isEmpty()) {
                firstTask[0] = helper.getLevel().getGameTime();
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(firstTask[0] >= 0 && firstTask[0] - began <= 40,
                    "stored village wood should alert a tool-less miner within two seconds");
            helper.assertTrue(!miner.blockPosition().closerThan(start, 2.0),
                    "the alerted miner should leave its idle position to work");
            helper.assertTrue(countItem(chest, Items.COBBLESTONE) > 0,
                    "the miner should return mined stone to public storage; carried="
                            + carried(miner, Items.COBBLESTONE) + ", tasks="
                            + essence.getTaskQueue().tasks().stream().map(task -> task.typeId()).toList()
                            + ", pick=" + carried(miner, Items.WOODEN_PICKAXE) + "/"
                            + carried(miner, Items.STONE_PICKAXE) + ", chestLogs="
                            + countItem(chest, Items.OAK_LOG) + ", pos=" + miner.blockPosition());
            helper.assertTrue(carried(miner, Items.STONE_PICKAXE) > 0,
                    "the miner should upgrade its fetched wooden supply into a stone pickaxe");
            helper.assertTrue(helper.getLevel().getGameTime() - began <= 1800,
                    "wood-to-stored-stone progression should finish within ninety seconds");
            cleanup(helper);
        });
    }

    @GameTest(template = "empty11x11", timeoutTicks = 800, batch = "dvGrowthStoneTools")
    public static void lumberjack_upgrades_wooden_axe_from_public_stone(GameTestHelper helper) {
        prepare(helper, false);
        VillageManager manager = VillageManager.get(helper.getLevel());
        Village village = manager.create(helper.getLevel(), helper.absolutePos(BELL));
        manager.setAutoStaff(village, false);
        BlockPos chestPos = new BlockPos(2, 2, 2);
        helper.setBlock(chestPos, Blocks.CHEST);
        helper.setBlock(new BlockPos(3, 2, 2), Blocks.CRAFTING_TABLE);
        Container chest = (Container) helper.getBlockEntity(chestPos);
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
        StorageLedger storage = StorageLedger.get(helper.getLevel());
        BlockPos absoluteChest = helper.absolutePos(chestPos);
        storage.setDesignation(absoluteChest, StorageLedger.Designation.PUBLIC, null, village.id());
        storage.recordSnapshot(absoluteChest, chest, helper.getLevel().getGameTime());
        Villager lumberjack = helper.spawn(EntityType.VILLAGER, new BlockPos(5, 2, 5));
        manager.adopt(helper.getLevel(), lumberjack, village);
        VillagerEssence essence = VillagerEssence.get(lumberjack);
        essence.setRole(VillagerRole.LUMBERJACK);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.WOODEN_AXE));
        essence.getExtraInventory().setItem(1, new ItemStack(Items.STICK, 2));
        long began = helper.getLevel().getGameTime();
        helper.succeedWhen(() -> {
            helper.assertTrue(carried(lumberjack, Items.STONE_AXE) > 0,
                    "a lumberjack should fetch public cobblestone and craft a stone axe");
            helper.assertTrue(helper.getLevel().getGameTime() - began <= 500,
                    "the stone upgrade should finish within twenty-five seconds");
            cleanup(helper);
        });
    }

    @GameTest(template = "empty11x11", timeoutTicks = 600, batch = "dvGrowthOtherTools")
    public static void farmer_crafts_its_wooden_hoe(GameTestHelper helper) {
        prepare(helper, false);
        helper.setBlock(new BlockPos(2, 2, 2), Blocks.CRAFTING_TABLE);
        helper.setBlock(new BlockPos(4, 2, 4), Blocks.FARMLAND);
        helper.setBlock(new BlockPos(3, 2, 4), Blocks.DIRT);
        helper.setBlock(new BlockPos(4, 2, 5), Blocks.WATER);
        Villager farmer = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 4));
        VillagerEssence.get(farmer).setRole(VillagerRole.FARMER);
        VillagerEssence.get(farmer).getExtraInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
        VillagerEssence.get(farmer).getExtraInventory().setItem(1, new ItemStack(Items.STICK, 2));
        helper.succeedWhen(() -> {
            helper.assertTrue(carried(farmer, Items.WOODEN_HOE) > 0,
                    "a farmer with tilling work should craft a wooden hoe");
            cleanup(helper);
        });
    }

    @GameTest(template = "empty11x11", timeoutTicks = 600, batch = "dvGrowthHunterTool")
    public static void hunter_crafts_its_wooden_sword(GameTestHelper helper) {
        prepare(helper, false);
        helper.setBlock(new BlockPos(8, 2, 2), Blocks.CRAFTING_TABLE);
        Villager hunter = helper.spawn(EntityType.VILLAGER, new BlockPos(8, 2, 4));
        VillagerEssence.get(hunter).setRole(VillagerRole.HUNTER);
        VillagerEssence.get(hunter).getExtraInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        VillagerEssence.get(hunter).getExtraInventory().setItem(1, new ItemStack(Items.STICK, 1));
        helper.spawn(EntityType.COW, new BlockPos(9, 2, 5));
        helper.succeedWhen(() -> {
            helper.assertTrue(carried(hunter, Items.WOODEN_SWORD) > 0,
                    "a hunter with prey should craft a wooden sword");
            cleanup(helper);
        });
    }

    private static void prepare(GameTestHelper helper, boolean wideFloor) {
        VillageManager.get(helper.getLevel()).clear();
        ConstructionLedger.get(helper.getLevel()).clear();
        StorageLedger.get(helper.getLevel()).clear();
        helper.getLevel().setDayTime(1000);
        helper.setBlock(BELL, Blocks.BELL);
        if (wideFloor) {
            for (int x = -14; x <= 24; x++) {
                for (int z = -14; z <= 24; z++) helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
    }

    private static void cleanup(GameTestHelper helper) {
        helper.setBlock(BELL, Blocks.AIR);
        BlockPos min = helper.absolutePos(new BlockPos(-30, -5, -30));
        BlockPos max = helper.absolutePos(new BlockPos(30, 15, 30));
        AABB local = new AABB(min.getX(), min.getY(), min.getZ(),
                max.getX() + 1, max.getY() + 1, max.getZ() + 1);
        for (Entity entity : new ArrayList<>(helper.getLevel().getEntities((Entity) null, local))) {
            if (entity instanceof Villager || VillageManager.isGuard(entity)) entity.discard();
        }
        for (int x = -14; x <= 24; x++) {
            for (int z = -14; z <= 24; z++) {
                if (x >= 0 && x <= 10 && z >= 0 && z <= 10) continue;
                for (int y = 1; y <= 6; y++) {
                    helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, y, z)),
                            Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        VillageManager.get(helper.getLevel()).clear();
        ConstructionLedger.get(helper.getLevel()).clear();
        StorageLedger.get(helper.getLevel()).clear();
    }

    private static List<Villager> villagers(GameTestHelper helper, Village village) {
        List<Villager> result = new ArrayList<>();
        for (Entity entity : helper.getLevel().getAllEntities()) {
            if (entity instanceof Villager villager
                    && VillagerEssence.get(villager).getHomeVillageId() == village.id()) result.add(villager);
        }
        return result;
    }

    private static int carried(Villager villager, Item item) {
        int count = 0;
        for (var inventory : List.of(villager.getInventory(), VillagerEssence.get(villager).getExtraInventory())) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                if (inventory.getItem(i).is(item)) count += inventory.getItem(i).getCount();
            }
        }
        return count;
    }

    private static int countItem(Container container, Item item) {
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(item)) count += container.getItem(i).getCount();
        }
        return count;
    }

    private static int countBlocks(GameTestHelper helper, net.minecraft.world.level.block.Block block, int radius) {
        BlockPos center = helper.absolutePos(BELL);
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -4, -radius),
                center.offset(radius, 4, radius))) {
            if (helper.getLevel().getBlockState(pos).is(block)) count++;
        }
        return count;
    }

    private static int ground(GameTestHelper helper, Item item, double radius) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                        new AABB(helper.absolutePos(BELL)).inflate(radius), entity -> entity.getItem().is(item))
                .stream().mapToInt(entity -> entity.getItem().getCount()).sum();
    }
}
