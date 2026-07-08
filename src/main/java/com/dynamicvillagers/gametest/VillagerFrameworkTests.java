package com.dynamicvillagers.gametest;

import com.dynamicvillagers.DynamicVillagers;
import com.dynamicvillagers.villager.HungerSystem;
import com.dynamicvillagers.villager.VillagerEssence;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(DynamicVillagers.MOD_ID)
@PrefixGameTestTemplate(false)
public class VillagerFrameworkTests {

    private static final BlockPos CENTER = new BlockPos(2, 1, 2);

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
