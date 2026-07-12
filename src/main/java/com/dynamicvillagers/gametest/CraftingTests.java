package com.dynamicvillagers.gametest;

import com.dynamicvillagers.DynamicVillagers;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.role.Crafting;
import com.dynamicvillagers.villager.task.CraftTask;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Villager crafting (owner request): small recipes are made in the inventory, 3×3 recipes need
 * a crafting table — the same split a player faces. Covers a single craft, the table gate, and
 * the multi-step supply chain a builder uses to make finished goods (logs → planks → door).
 */
@GameTestHolder(DynamicVillagers.MOD_ID)
@PrefixGameTestTemplate(false)
public class CraftingTests {

    private static final BlockPos CENTER = new BlockPos(1, 2, 1);

    /** 2×2, no table: one oak log becomes four planks right in the villager's hands. */
    @GameTest(template = "empty5x5", timeoutTicks = 200)
    public static void crafts_planks_in_inventory(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.OAK_LOG, 1));
        essence.getTaskQueue().enqueue(new CraftTask(Items.OAK_PLANKS, 4, false));
        helper.succeedWhen(() -> {
            helper.assertTrue(count(villager, essence, Items.OAK_PLANKS) >= 4,
                    "one log should craft into four planks without a table");
            helper.assertTrue(count(villager, essence, Items.OAK_LOG) == 0, "the log should be consumed");
        });
    }

    /** 2×2, no table: coal + stick make four torches in the inventory. */
    @GameTest(template = "empty5x5", timeoutTicks = 200)
    public static void crafts_torches_in_inventory(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.COAL, 1));
        essence.getExtraInventory().setItem(1, new ItemStack(Items.STICK, 1));
        essence.getTaskQueue().enqueue(new CraftTask(Items.TORCH, 4, false));
        helper.succeedWhen(() -> helper.assertTrue(count(villager, essence, Items.TORCH) >= 4,
                "coal over a stick should craft four torches without a table"));
    }

    /** A door is a 3×3 recipe: with a table forbidden, the craft fails and nothing is consumed. */
    @GameTest(template = "empty5x5", timeoutTicks = 120)
    public static void door_without_a_table_fails(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 6));
        essence.getTaskQueue().enqueue(new CraftTask(Items.OAK_DOOR, 3, false));
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(count(villager, essence, Items.OAK_DOOR) == 0,
                    "a door cannot be hand-crafted without a table");
            helper.assertTrue(count(villager, essence, Items.OAK_PLANKS) == 6, "the planks should be untouched");
            helper.assertTrue(essence.getTaskQueue().isEmpty(), "the failed craft should leave the queue");
            helper.succeed();
        });
    }

    /** With a crafting table in reach, the villager walks to it and crafts the 3×3 door. */
    @GameTest(template = "empty5x5", timeoutTicks = 400)
    public static void crafts_door_at_a_table(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        helper.setBlock(new BlockPos(2, 2, 1), Blocks.CRAFTING_TABLE);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 6));
        essence.getTaskQueue().enqueue(new CraftTask(Items.OAK_DOOR, 3, true));
        helper.succeedWhen(() -> helper.assertTrue(count(villager, essence, Items.OAK_DOOR) >= 3,
                "six planks at a table should craft three doors"));
    }

    /**
     * The supply chain a builder runs: {@link Crafting#ensureItem} turns carried logs into a
     * door by crafting planks first (2×2, no table) and then the door at a table — enqueued in
     * dependency order so it just works when the queue drains.
     */
    @GameTest(template = "empty5x5", timeoutTicks = 600)
    public static void ensure_item_chains_logs_into_a_door(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        helper.setBlock(new BlockPos(2, 2, 1), Blocks.CRAFTING_TABLE);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.OAK_LOG, 8));
        Crafting.Provision provision = Crafting.ensureItem(
                helper.getLevel(), villager, essence, Items.OAK_DOOR, 3, 4);
        helper.assertTrue(provision == Crafting.Provision.ENQUEUED,
                "ensureItem should enqueue a craft chain for a door it can make from logs");
        helper.succeedWhen(() -> helper.assertTrue(count(villager, essence, Items.OAK_DOOR) >= 3,
                "logs should become planks and then a door at the table"));
    }

    private static int count(Villager villager, VillagerEssence essence, Item item) {
        return essence.countItems(villager, stack -> stack.is(item));
    }
}
