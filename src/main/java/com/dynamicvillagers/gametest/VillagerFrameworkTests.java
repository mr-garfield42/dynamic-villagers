package com.dynamicvillagers.gametest;

import com.dynamicvillagers.DynamicVillagers;
import com.dynamicvillagers.villager.HungerSystem;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.work.BreakBlockOrder;
import com.dynamicvillagers.villager.work.PlaceBlockOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(DynamicVillagers.MOD_ID)
@PrefixGameTestTemplate(false)
public class VillagerFrameworkTests {

    // Template content sits one above helper-relative y=0 (the structure block's layer):
    // the stone floor is at relative y=1, entities stand at y=2.
    private static final BlockPos CENTER = new BlockPos(2, 2, 2);

    @GameTest(template = "empty5x5")
    public static void arena_sanity(GameTestHelper helper) {
        helper.succeedWhen(() -> {
            helper.assertBlock(new BlockPos(2, 1, 2), b -> b == Blocks.STONE, "y1 should be stone floor");
            helper.assertBlock(new BlockPos(2, 2, 2), b -> b == Blocks.AIR, "y2 center should be air");
            helper.assertBlock(new BlockPos(3, 2, 3), b -> b == Blocks.AIR, "place target should be air");
            helper.assertBlock(new BlockPos(1, 2, 1), b -> b == Blocks.AIR, "break target should be air");
        });
    }

    @GameTest(template = "empty5x5")
    public static void harness_smoke(GameTestHelper helper) {
        helper.spawn(EntityType.VILLAGER, CENTER);
        helper.succeedWhen(() -> helper.assertEntityPresent(EntityType.VILLAGER));
    }

    @GameTest(template = "empty5x5")
    public static void essence_defaults(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        VillagerEssence essence = VillagerEssence.get(villager);
        helper.assertTrue(essence.getHunger() == VillagerEssence.MAX_HUNGER,
                "new villager should start with full hunger");
        helper.assertTrue(essence.getExtraInventory().getContainerSize() == VillagerEssence.EXTRA_SLOTS,
                "extra inventory should have " + VillagerEssence.EXTRA_SLOTS + " slots");
        helper.succeed();
    }

    @GameTest(template = "empty5x5")
    public static void hunger_decay_clamps_at_zero(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        for (int i = 0; i < VillagerEssence.MAX_HUNGER + 5; i++) {
            HungerSystem.applyDecay(villager);
        }
        helper.assertTrue(VillagerEssence.get(villager).getHunger() == 0,
                "hunger should clamp at 0, never negative");
        helper.succeed();
    }

    @GameTest(template = "empty5x5", timeoutTicks = 200)
    public static void eats_from_extra_inventory_when_hungry(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.setHunger(5);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.BREAD, 2));
        helper.succeedWhen(() -> {
            helper.assertTrue(VillagerEssence.get(villager).getHunger() > 5,
                    "villager should have eaten and restored hunger");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 200)
    public static void eats_from_vanilla_inventory_first(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.setHunger(5);
        villager.getInventory().setItem(0, new ItemStack(Items.BREAD, 1));
        helper.succeedWhen(() -> {
            helper.assertTrue(villager.getInventory().getItem(0).isEmpty(),
                    "bread in the vanilla inventory should be consumed");
            helper.assertTrue(VillagerEssence.get(villager).getHunger() > 5,
                    "villager should have eaten and restored hunger");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 400)
    public static void seeks_picks_up_and_eats_dropped_food(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        VillagerEssence.get(villager).setHunger(5);
        helper.spawnItem(Items.BREAD, 0.5F, 2.5F, 0.5F);
        helper.succeedWhen(() -> helper.assertTrue(VillagerEssence.get(villager).getHunger() > 5,
                "villager should seek, pick up, and eat the dropped bread"));
    }

    @GameTest(template = "empty5x5", timeoutTicks = 400)
    public static void breaks_block_with_tool_durability_and_drops(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        BlockPos target = new BlockPos(1, 2, 1);
        helper.setBlock(target, Blocks.STONE);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        essence.setCurrentWork(new BreakBlockOrder(helper.absolutePos(target)));
        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(Blocks.STONE, target);
            helper.assertItemEntityPresent(Items.COBBLESTONE, target, 3.0);
            helper.assertTrue(essence.getExtraInventory().getItem(0).getDamageValue() > 0,
                    "pickaxe should have lost durability");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 400)
    public static void places_block_from_inventory(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        BlockPos target = new BlockPos(3, 2, 3);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 4));
        essence.setCurrentWork(new PlaceBlockOrder(helper.absolutePos(target)));
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.COBBLESTONE, target);
            helper.assertTrue(essence.getExtraInventory().getItem(0).getCount() == 3,
                    "one cobblestone should be consumed from the inventory");
        });
    }

    @GameTest(template = "empty5x5")
    public static void full_villager_does_not_eat(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.BREAD, 1));
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(essence.getExtraInventory().getItem(0).getCount() == 1,
                    "a full villager must not eat");
            helper.succeed();
        });
    }
}
