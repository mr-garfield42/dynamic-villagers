package com.dynamicvillagers.gametest;

import com.dynamicvillagers.DynamicVillagers;
import com.dynamicvillagers.item.DVItems;
import com.dynamicvillagers.item.SiteMarkerItem;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.role.VillagerRole;
import com.dynamicvillagers.villager.task.BreakBlockTask;
import com.dynamicvillagers.villager.task.ChopTreeTask;
import com.dynamicvillagers.villager.task.DepositToContainerTask;
import com.dynamicvillagers.villager.task.PickUpItemsTask;
import com.dynamicvillagers.villager.task.TakeItemsTask;
import com.dynamicvillagers.villager.work.ItemFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

@GameTestHolder(DynamicVillagers.MOD_ID)
@PrefixGameTestTemplate(false)
public class ResourceGatheringTests {

    private static final BlockPos CENTER = new BlockPos(2, 2, 2);
    private static final BlockPos CHEST = new BlockPos(4, 2, 4);

    @GameTest(template = "empty5x5", timeoutTicks = 400)
    public static void take_items_fetches_axe_from_known_chest(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        helper.setBlock(CHEST, Blocks.CHEST);
        Container chest = (Container) helper.getBlockEntity(CHEST);
        chest.setItem(0, new ItemStack(Items.IRON_AXE));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getMemory().rememberContainer(helper.absolutePos(CHEST), 0);
        essence.getTaskQueue().enqueue(new TakeItemsTask("axe", 1));
        helper.succeedWhen(() -> {
            helper.assertTrue(essence.hasItem(villager, ItemFilter.parse("axe")),
                    "villager should have taken the axe from the chest");
            helper.assertTrue(chest.getItem(0).isEmpty(), "the chest should no longer hold the axe");
            helper.assertTrue(essence.getTaskQueue().isEmpty(), "take task should complete");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 400)
    public static void take_items_fails_when_no_known_chest_has_them(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        helper.setBlock(CHEST, Blocks.CHEST); // empty chest
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getMemory().rememberContainer(helper.absolutePos(CHEST), 0);
        essence.getTaskQueue().enqueue(new TakeItemsTask("axe", 1));
        helper.succeedWhen(() -> {
            helper.assertTrue(essence.getTaskQueue().isEmpty(),
                    "take task should give up after searching every known container");
            helper.assertTrue(!essence.hasItem(villager, ItemFilter.parse("axe")),
                    "no axe should have appeared from nowhere");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 600)
    public static void chop_tree_task_fells_logs_and_drops_are_collected(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        BlockPos lower = new BlockPos(1, 2, 1);
        BlockPos upper = new BlockPos(1, 3, 1);
        helper.setBlock(lower, Blocks.OAK_LOG);
        helper.setBlock(upper, Blocks.OAK_LOG);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.IRON_AXE));
        essence.getTaskQueue().enqueue(
                new ChopTreeTask(List.of(helper.absolutePos(lower), helper.absolutePos(upper))));
        essence.getTaskQueue().enqueue(new PickUpItemsTask(helper.absolutePos(lower), 4.0));
        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(Blocks.OAK_LOG, lower);
            helper.assertBlockNotPresent(Blocks.OAK_LOG, upper);
            helper.assertTrue(carriedCount(villager, Items.OAK_LOG) == 2,
                    "villager should carry both chopped logs");
            helper.assertTrue(essence.getExtraInventory().getItem(0).getDamageValue() > 0,
                    "the axe should have lost durability");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 400)
    public static void deposit_keeps_tools_food_and_saplings(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        helper.setBlock(CHEST, Blocks.CHEST);
        Container chest = (Container) helper.getBlockEntity(CHEST);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getMemory().rememberContainer(helper.absolutePos(CHEST), 0);
        villager.getInventory().setItem(0, new ItemStack(Items.STICK, 4));
        essence.getExtraInventory().setItem(0, new ItemStack(Items.IRON_AXE));
        essence.getExtraInventory().setItem(1, new ItemStack(Items.BREAD, 2));
        essence.getExtraInventory().setItem(2, new ItemStack(Items.OAK_SAPLING, 3));
        essence.getExtraInventory().setItem(3, new ItemStack(Items.COBBLESTONE, 5));
        essence.getTaskQueue().enqueue(new DepositToContainerTask(List.of("axe", "food", "sapling")));
        helper.succeedWhen(() -> {
            helper.assertTrue(countItem(chest, Items.COBBLESTONE) == 5, "cobblestone should be deposited");
            helper.assertTrue(countItem(chest, Items.STICK) == 4,
                    "sticks from the vanilla inventory should be deposited too");
            helper.assertTrue(carriedCount(villager, Items.IRON_AXE) == 1, "the axe must stay carried");
            helper.assertTrue(carriedCount(villager, Items.BREAD) == 2, "food must stay carried");
            helper.assertTrue(carriedCount(villager, Items.OAK_SAPLING) == 3, "saplings must stay carried");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 1200, batch = "dvLumberjackCycle")
    public static void lumberjack_fells_tree_and_replants(GameTestHelper helper) {
        helper.getLevel().setDayTime(1000); // planner only works in daylight
        BlockPos soil = new BlockPos(1, 2, 1);
        BlockPos trunk = new BlockPos(1, 3, 1);
        BlockPos trunkTop = new BlockPos(1, 4, 1);
        helper.setBlock(soil, Blocks.DIRT);
        helper.setBlock(trunk, Blocks.OAK_LOG);
        helper.setBlock(trunkTop, Blocks.OAK_LOG);
        // natural (non-persistent) canopy around the crown; trunk stays bare at eye level so
        // the villager can actually see it (perception uses real line of sight)
        helper.setBlock(new BlockPos(0, 4, 1), Blocks.OAK_LEAVES);
        helper.setBlock(new BlockPos(2, 4, 1), Blocks.OAK_LEAVES);
        helper.setBlock(new BlockPos(1, 4, 0), Blocks.OAK_LEAVES);
        helper.setBlock(new BlockPos(1, 4, 2), Blocks.OAK_LEAVES);
        helper.setBlock(new BlockPos(1, 5, 1), Blocks.OAK_LEAVES);

        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.setRole(VillagerRole.LUMBERJACK);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.IRON_AXE));
        essence.getExtraInventory().setItem(1, new ItemStack(Items.OAK_SAPLING, 2));

        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(Blocks.OAK_LOG, trunk);
            helper.assertBlockNotPresent(Blocks.OAK_LOG, trunkTop);
            helper.assertBlockPresent(Blocks.OAK_SAPLING, trunk);
            helper.assertTrue(carriedCount(villager, Items.OAK_LOG) >= 1,
                    "villager should carry the chopped logs");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 800, batch = "dvTorchChore")
    public static void idle_lumberjack_torches_dark_ground(GameTestHelper helper) {
        helper.getLevel().setDayTime(1000);
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.setRole(VillagerRole.LUMBERJACK); // no trees around → torch chore kicks in
        essence.getExtraInventory().setItem(0, new ItemStack(Items.TORCH, 8));
        helper.succeedWhen(() -> {
            boolean anyTorch = false;
            for (int x = 0; x <= 4 && !anyTorch; x++) {
                for (int z = 0; z <= 4 && !anyTorch; z++) {
                    anyTorch = helper.getBlockState(new BlockPos(x, 2, z)).is(Blocks.TORCH);
                }
            }
            helper.assertTrue(anyTorch, "a torch should have been placed on the dark floor");
            helper.assertTrue(carriedCount(villager, Items.TORCH) < 8,
                    "the torch should have come from the villager's inventory");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 600, batch = "dvLumberjackIgnore")
    public static void lumberjack_ignores_leafless_log_piles(GameTestHelper helper) {
        helper.getLevel().setDayTime(1000);
        BlockPos lower = new BlockPos(1, 2, 1);
        BlockPos upper = new BlockPos(1, 3, 1);
        helper.setBlock(lower, Blocks.OAK_LOG);
        helper.setBlock(upper, Blocks.OAK_LOG);

        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.setRole(VillagerRole.LUMBERJACK);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.IRON_AXE));

        helper.runAfterDelay(400, () -> {
            helper.assertBlockPresent(Blocks.OAK_LOG, lower);
            helper.assertBlockPresent(Blocks.OAK_LOG, upper);
            helper.succeed();
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 600)
    public static void mines_obstructing_leaves_before_hidden_log(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        BlockPos soil = new BlockPos(1, 2, 1);
        BlockPos log = new BlockPos(1, 3, 1);
        helper.setBlock(soil, Blocks.DIRT);
        helper.setBlock(log, Blocks.OAK_LOG);
        // encase every exposed face: the log is invisible until a leaf is mined away
        List<BlockPos> leaves = List.of(new BlockPos(0, 3, 1), new BlockPos(2, 3, 1),
                new BlockPos(1, 3, 0), new BlockPos(1, 3, 2), new BlockPos(1, 4, 1));
        for (BlockPos leaf : leaves) {
            helper.setBlock(leaf, Blocks.OAK_LEAVES);
        }
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.IRON_AXE));
        essence.getTaskQueue().enqueue(new BreakBlockTask(helper.absolutePos(log)));
        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(Blocks.OAK_LOG, log);
            long remaining = leaves.stream()
                    .filter(leaf -> helper.getBlockState(leaf).is(Blocks.OAK_LEAVES)).count();
            helper.assertTrue(remaining < leaves.size(),
                    "at least one obstructing leaf must have been mined to reach the log");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 400)
    public static void skips_walled_off_chest_instead_of_tunneling(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        BlockPos chest = new BlockPos(4, 2, 2);
        helper.setBlock(chest, Blocks.CHEST);
        Container container = (Container) helper.getBlockEntity(chest);
        container.setItem(0, new ItemStack(Items.IRON_AXE));
        // glass on every face the villager could look through (the rest is arena boundary)
        List<BlockPos> wall = List.of(new BlockPos(3, 2, 2), new BlockPos(4, 2, 1),
                new BlockPos(4, 2, 3), new BlockPos(4, 3, 2));
        for (BlockPos pane : wall) {
            helper.setBlock(pane, Blocks.GLASS);
        }
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getMemory().rememberContainer(helper.absolutePos(chest), 0);
        essence.getTaskQueue().enqueue(new TakeItemsTask("axe", 1));
        helper.succeedWhen(() -> {
            helper.assertTrue(essence.getTaskQueue().isEmpty(),
                    "task should give up on the walled-off chest");
            helper.assertTrue(!essence.hasItem(villager, ItemFilter.parse("axe")),
                    "the axe must not be taken through a wall");
            for (BlockPos pane : wall) {
                helper.assertBlockPresent(Blocks.GLASS, pane);
            }
        });
    }

    /** Contained water so farmland stays hydrated without flooding the arena. */
    private static void buildWaterBox(GameTestHelper helper) {
        helper.setBlock(new BlockPos(2, 2, 1), Blocks.GLASS);
        helper.setBlock(new BlockPos(4, 2, 1), Blocks.GLASS);
        helper.setBlock(new BlockPos(3, 2, 0), Blocks.GLASS);
        helper.setBlock(new BlockPos(3, 2, 2), Blocks.GLASS);
        helper.setBlock(new BlockPos(3, 2, 1), Blocks.WATER);
    }

    @GameTest(template = "empty5x5", timeoutTicks = 600, batch = "dvFarmerPlant")
    public static void farmer_plants_carried_seeds_on_empty_farmland(GameTestHelper helper) {
        helper.getLevel().setDayTime(1000);
        buildWaterBox(helper);
        BlockPos farmland = new BlockPos(1, 2, 1);
        helper.setBlock(farmland, Blocks.FARMLAND);
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 3));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.setRole(VillagerRole.FARMER);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 4));
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.WHEAT, farmland.above());
            helper.assertTrue(carriedCount(villager, Items.WHEAT_SEEDS) == 3,
                    "one seed should have been planted from the inventory");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 600, batch = "dvFarmerTill")
    public static void farmer_tills_dirt_at_farm_edge_with_hoe(GameTestHelper helper) {
        helper.getLevel().setDayTime(1000);
        buildWaterBox(helper);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.FARMLAND);
        BlockPos dirt = new BlockPos(1, 2, 2);
        helper.setBlock(dirt, Blocks.DIRT);
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 3));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.setRole(VillagerRole.FARMER);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.IRON_HOE));
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.FARMLAND, dirt);
            helper.assertTrue(essence.getExtraInventory().getItem(0).getDamageValue() > 0,
                    "the hoe should have lost durability from tilling");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 600, batch = "dvMinerMine")
    public static void miner_mines_exposed_ore_it_can_harvest(GameTestHelper helper) {
        helper.getLevel().setDayTime(1000);
        BlockPos ore = new BlockPos(1, 2, 1);
        helper.setBlock(ore, Blocks.IRON_ORE);
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.GLOWSTONE); // lit site: no fear at play here
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.setRole(VillagerRole.MINER);
        // stone pick: harvests iron, and can't harvest any diamond-ore leftovers from
        // neighboring test structures (batches share the world)
        essence.getExtraInventory().setItem(0, new ItemStack(Items.STONE_PICKAXE));
        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(Blocks.IRON_ORE, ore);
            helper.assertTrue(carriedCount(villager, Items.RAW_IRON) >= 1,
                    "the mined raw iron should be carried");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 600, batch = "dvMinerSkip")
    public static void miner_skips_ore_its_pickaxe_cannot_harvest(GameTestHelper helper) {
        helper.getLevel().setDayTime(1000);
        BlockPos ore = new BlockPos(1, 2, 1);
        helper.setBlock(ore, Blocks.DIAMOND_ORE);
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.GLOWSTONE); // lit: the skip is tier-based
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.setRole(VillagerRole.MINER);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.STONE_PICKAXE));
        helper.runAfterDelay(400, () -> {
            helper.assertBlockPresent(Blocks.DIAMOND_ORE, ore);
            helper.succeed();
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 600, batch = "dvMinerFear")
    public static void torchless_miner_fears_dark_ore_site(GameTestHelper helper) {
        helper.getLevel().setDayTime(1000);
        // far corner: away from the harness's status beacon (a corner light source), so the
        // site is genuinely below the safe block-light threshold
        BlockPos ore = new BlockPos(3, 2, 3);
        helper.setBlock(ore, Blocks.IRON_ORE);
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 2));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.setRole(VillagerRole.MINER);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.STONE_PICKAXE));
        helper.runAfterDelay(400, () -> {
            helper.assertBlockPresent(Blocks.IRON_ORE, ore);
            helper.succeed();
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 800, batch = "dvMinerTorch")
    public static void miner_with_torches_lights_dark_site_and_mines(GameTestHelper helper) {
        helper.getLevel().setDayTime(1000);
        BlockPos ore = new BlockPos(3, 2, 3); // far corner: dark (see fear test)
        helper.setBlock(ore, Blocks.IRON_ORE);
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 2));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.setRole(VillagerRole.MINER);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.STONE_PICKAXE));
        essence.getExtraInventory().setItem(1, new ItemStack(Items.TORCH, 4));
        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(Blocks.IRON_ORE, ore);
            helper.assertTrue(carriedCount(villager, Items.RAW_IRON) >= 1,
                    "the mined raw iron should be carried");
            helper.assertTrue(anyTorchInArena(helper),
                    "the dark site should have been torched before mining");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 1200, batch = "dvMinerTunnel")
    public static void miner_digs_designated_strip_tunnel(GameTestHelper helper) {
        helper.getLevel().setDayTime(1000);
        // a 1×2 rock face two segments deep, heading north (-z)
        List<BlockPos> rock = List.of(new BlockPos(1, 2, 1), new BlockPos(1, 3, 1),
                new BlockPos(1, 2, 0), new BlockPos(1, 3, 0));
        for (BlockPos pos : rock) {
            helper.setBlock(pos, Blocks.STONE);
        }
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 3));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.setRole(VillagerRole.MINER);
        essence.setMineSite(new VillagerEssence.MineSite(
                helper.absolutePos(new BlockPos(1, 2, 1)), Direction.NORTH));
        essence.getExtraInventory().setItem(0, new ItemStack(Items.STONE_PICKAXE));
        essence.getExtraInventory().setItem(1, new ItemStack(Items.TORCH, 8)); // dark arena: needed
        helper.succeedWhen(() -> {
            for (BlockPos pos : rock) {
                helper.assertBlockNotPresent(Blocks.STONE, pos);
            }
            helper.assertTrue(carriedCount(villager, Items.COBBLESTONE) >= 4,
                    "the tunneled stone should be carried as cobblestone");
            helper.assertTrue(anyTorchInArena(helper),
                    "tunnel torches must survive the digging (not be re-mined as rock)");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 600, batch = "dvMinerWater")
    public static void miner_never_tunnels_into_water(GameTestHelper helper) {
        helper.getLevel().setDayTime(1000);
        BlockPos lower = new BlockPos(1, 2, 1);
        BlockPos upper = new BlockPos(1, 3, 1);
        helper.setBlock(lower, Blocks.STONE);
        helper.setBlock(upper, Blocks.STONE);
        // water lurking directly behind the face (contained by arena wall, glass, and stone)
        helper.setBlock(new BlockPos(0, 2, 0), Blocks.GLASS);
        helper.setBlock(new BlockPos(2, 2, 0), Blocks.GLASS);
        helper.setBlock(new BlockPos(1, 3, 0), Blocks.GLASS);
        helper.setBlock(new BlockPos(1, 2, 0), Blocks.WATER);
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 3));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.setRole(VillagerRole.MINER);
        essence.setMineSite(new VillagerEssence.MineSite(
                helper.absolutePos(lower), Direction.NORTH));
        essence.getExtraInventory().setItem(0, new ItemStack(Items.STONE_PICKAXE));
        essence.getExtraInventory().setItem(1, new ItemStack(Items.TORCH, 8));
        helper.runAfterDelay(400, () -> {
            helper.assertBlockPresent(Blocks.STONE, lower);
            helper.assertBlockPresent(Blocks.STONE, upper);
            helper.succeed();
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 3000, batch = "dvMinerQuarry")
    public static void miner_digs_quarry_leaving_walkout_ramp(GameTestHelper helper) {
        helper.getLevel().setDayTime(1000);
        // a 3×3, two-layer stone box; the ramp wedge along the z=1 wall must survive
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 3; z++) {
                helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, 3, z), Blocks.STONE);
            }
        }
        // the walk-out staircase: one step down per column along the z=1 wall
        List<BlockPos> stairs = List.of(new BlockPos(1, 3, 1), new BlockPos(2, 2, 1));
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 4));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.setRole(VillagerRole.MINER);
        essence.setQuarrySite(new VillagerEssence.QuarrySite(
                helper.absolutePos(new BlockPos(1, 3, 1)), helper.absolutePos(new BlockPos(3, 2, 3))));
        essence.getExtraInventory().setItem(0, new ItemStack(Items.STONE_PICKAXE));
        essence.getExtraInventory().setItem(1, new ItemStack(Items.TORCH, 8));
        helper.succeedWhen(() -> {
            for (int x = 1; x <= 3; x++) {
                for (int z = 1; z <= 3; z++) {
                    for (int y = 2; y <= 3; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (stairs.contains(pos)) {
                            helper.assertTrue(helper.getBlockState(pos).isFaceSturdy(
                                            helper.getLevel(), helper.absolutePos(pos), Direction.UP),
                                    "stair step at " + pos + " must stand (stone or rebuilt cobble)");
                        } else {
                            helper.assertTrue(!helper.getBlockState(pos).is(Blocks.STONE)
                                            && !helper.getBlockState(pos).is(Blocks.COBBLESTONE),
                                    "non-stair cell at " + pos + " should be dug out");
                        }
                    }
                }
            }
            helper.assertTrue(carriedCount(villager, Items.COBBLESTONE)
                            + groundCount(helper, Items.COBBLESTONE) >= 14,
                    "the dug blocks should exist as cobblestone (carried or on the ground)");
            helper.assertTrue(anyTorchInArena(helper),
                    "quarry torches must survive on rim, stairs, or dug floor");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 800, batch = "dvCanopy")
    public static void chopper_stuck_on_canopy_digs_down_through_leaves(GameTestHelper helper) {
        // a full-arena leaf platform: the villager on top cannot path anywhere (leaves are
        // blocked path nodes) and the log below is out of reach — it must dig down
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                helper.setBlock(new BlockPos(x, 3, z),
                        Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
            }
        }
        BlockPos log = new BlockPos(0, 2, 0);
        helper.setBlock(log, Blocks.OAK_LOG);
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 4, 2));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.IRON_AXE));
        essence.getTaskQueue().enqueue(new ChopTreeTask(List.of(helper.absolutePos(log))));
        helper.succeedWhen(() -> helper.assertBlockNotPresent(Blocks.OAK_LOG, log));
    }

    @GameTest(template = "empty5x5", timeoutTicks = 600, batch = "dvDeposit")
    public static void lumberjack_hauls_surplus_logs_to_storage(GameTestHelper helper) {
        helper.getLevel().setDayTime(1000);
        helper.setBlock(CHEST, Blocks.CHEST);
        Container chest = (Container) helper.getBlockEntity(CHEST);
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.setRole(VillagerRole.LUMBERJACK);
        essence.getMemory().rememberContainer(helper.absolutePos(CHEST), 0);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.OAK_LOG, 20)); // over the haul threshold
        helper.succeedWhen(() -> helper.assertTrue(countItem(chest, Items.OAK_LOG) == 20,
                "a villager carrying a haul should store it without waiting to be full"));
    }

    @GameTest(template = "empty5x5", timeoutTicks = 100)
    public static void mine_marker_designates_tunnel_for_bound_villager(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        ItemStack marker = new ItemStack(DVItems.MINE_MARKER.get());
        SiteMarkerItem.bind(marker, villager);
        player.setItemInHand(InteractionHand.MAIN_HAND, marker);
        BlockPos target = helper.absolutePos(new BlockPos(1, 2, 1));
        helper.runAfterDelay(5, () -> {
            marker.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false)));
            VillagerEssence essence = VillagerEssence.get(villager);
            helper.assertTrue(essence.getMineSite() != null
                            && essence.getMineSite().start().equals(target),
                    "the mine site should start at the clicked block");
            helper.assertTrue(essence.getRole() == VillagerRole.MINER,
                    "designating a mine site should give the villager the miner role");
            helper.succeed();
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 100)
    public static void quarry_marker_designates_pit_from_two_corners(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        ItemStack marker = new ItemStack(DVItems.QUARRY_MARKER.get());
        SiteMarkerItem.bind(marker, villager);
        player.setItemInHand(InteractionHand.MAIN_HAND, marker);
        BlockPos cornerA = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos cornerB = helper.absolutePos(new BlockPos(3, 2, 3));
        helper.runAfterDelay(5, () -> {
            marker.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(cornerA), Direction.UP, cornerA, false)));
            VillagerEssence essence = VillagerEssence.get(villager);
            helper.assertTrue(essence.getQuarrySite() == null,
                    "one corner alone must not designate a quarry yet");
            marker.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(cornerB), Direction.UP, cornerB, false)));
            helper.assertTrue(essence.getQuarrySite() != null
                            && essence.getQuarrySite().cornerA().equals(cornerA)
                            && essence.getQuarrySite().cornerB().equals(cornerB),
                    "both corners should designate the quarry");
            helper.assertTrue(essence.getRole() == VillagerRole.MINER,
                    "designating a quarry should give the villager the miner role");
            helper.succeed();
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 200)
    public static void killed_villager_drops_everything_carried(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        VillagerEssence essence = VillagerEssence.get(villager);
        villager.getInventory().setItem(0, new ItemStack(Items.BREAD, 3));
        essence.getExtraInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 7));
        essence.getExtraInventory().setItem(1, new ItemStack(Items.IRON_AXE));
        villager.hurt(helper.getLevel().damageSources().generic(), 9999.0F);
        helper.succeedWhen(() -> {
            helper.assertTrue(!villager.isAlive(), "villager should be dead");
            helper.assertTrue(groundCount(helper, Items.BREAD) == 3, "bread should drop on death");
            helper.assertTrue(groundCount(helper, Items.COBBLESTONE) == 7, "cobblestone should drop on death");
            helper.assertTrue(groundCount(helper, Items.IRON_AXE) == 1, "the axe should drop on death");
        });
    }

    @GameTest(template = "empty5x5")
    public static void role_and_spot_memory_survive_serialization(GameTestHelper helper) {
        VillagerEssence original = new VillagerEssence();
        original.setRole(VillagerRole.LUMBERJACK);
        original.getMemory().rememberSpot("tree", helper.absolutePos(new BlockPos(1, 2, 1)), 42);
        CompoundTag saved = original.serializeNBT(helper.getLevel().registryAccess());

        VillagerEssence restored = new VillagerEssence();
        restored.deserializeNBT(helper.getLevel().registryAccess(), saved);
        helper.assertTrue(restored.getRole() == VillagerRole.LUMBERJACK,
                "role should survive serialization");
        helper.assertTrue(restored.getMemory().knownSpots("tree").size() == 1,
                "spot memory should survive serialization");
        helper.succeed();
    }

    private static boolean anyTorchInArena(GameTestHelper helper) {
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                for (int y = 2; y <= 4; y++) {
                    if (helper.getBlockState(new BlockPos(x, y, z)).is(Blocks.TORCH)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int countItem(Container container, Item item) {
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int carriedCount(Villager villager, Item item) {
        return countItem(villager.getInventory(), item)
                + countItem(VillagerEssence.get(villager).getExtraInventory(), item);
    }

    private static int groundCount(GameTestHelper helper, Item item) {
        AABB bounds = new AABB(helper.absolutePos(CENTER)).inflate(5.0);
        return helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, bounds, entity -> entity.getItem().is(item))
                .stream().mapToInt(entity -> entity.getItem().getCount()).sum();
    }
}
