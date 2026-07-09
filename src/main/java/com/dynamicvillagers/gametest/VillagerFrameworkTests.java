package com.dynamicvillagers.gametest;

import com.dynamicvillagers.DynamicVillagers;
import com.dynamicvillagers.villager.HungerSystem;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.behavior.SeekFoodItemBehavior;
import com.dynamicvillagers.villager.task.BreakBlockTask;
import com.dynamicvillagers.villager.task.DepositToContainerTask;
import com.dynamicvillagers.villager.task.PickUpItemsTask;
import com.dynamicvillagers.villager.task.PlaceBlockTask;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
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
        essence.getTaskQueue().enqueue(new BreakBlockTask(helper.absolutePos(target)));
        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(Blocks.STONE, target);
            // villagers pick up nearby drops, so the cobble may be on the ground or carried
            helper.assertTrue(totalItemCount(helper, villager, target, Items.COBBLESTONE) == 1,
                    "mining should have produced one cobblestone (ground or carried)");
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
        essence.getTaskQueue().enqueue(new PlaceBlockTask(helper.absolutePos(target)));
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.COBBLESTONE, target);
            helper.assertTrue(essence.getExtraInventory().getItem(0).getCount() == 3,
                    "one cobblestone should be consumed from the inventory");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 400)
    public static void learns_nearby_container_by_seeing_it(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        BlockPos chest = new BlockPos(4, 2, 4);
        helper.setBlock(chest, Blocks.CHEST);
        helper.succeedWhen(() -> helper.assertTrue(
                VillagerEssence.get(villager).getMemory().knownContainers().contains(helper.absolutePos(chest)),
                "villager should remember the chest it can see"));
    }

    @GameTest(template = "empty5x5", timeoutTicks = 400)
    public static void forgets_container_that_is_gone(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        BlockPos ghost = helper.absolutePos(new BlockPos(1, 2, 3)); // remembered, but nothing is there
        VillagerEssence.get(villager).getMemory().rememberContainer(ghost, 0);
        helper.succeedWhen(() -> helper.assertTrue(
                VillagerEssence.get(villager).getMemory().knownContainers().isEmpty(),
                "villager should forget a container that no longer exists"));
    }

    @GameTest(template = "empty5x5", timeoutTicks = 400)
    public static void runs_queued_tasks_sequentially(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        BlockPos target = new BlockPos(3, 2, 3);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 1));
        essence.getExtraInventory().setItem(1, new ItemStack(Items.IRON_PICKAXE));
        essence.getTaskQueue().enqueue(new PlaceBlockTask(helper.absolutePos(target)));
        essence.getTaskQueue().enqueue(new BreakBlockTask(helper.absolutePos(target)));
        helper.succeedWhen(() -> {
            helper.assertTrue(essence.getTaskQueue().isEmpty(), "both tasks should have completed");
            helper.assertBlockNotPresent(Blocks.COBBLESTONE, target);
            // placed, then mined back off; the drop may have been auto-picked-up
            helper.assertTrue(totalItemCount(helper, villager, target, Items.COBBLESTONE) == 1,
                    "the placed-then-mined cobblestone should exist (ground or carried)");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 400)
    public static void deposits_extra_inventory_into_known_container(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        BlockPos chestPos = new BlockPos(4, 2, 4);
        helper.setBlock(chestPos, Blocks.CHEST);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getMemory().rememberContainer(helper.absolutePos(chestPos), 0);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 5));
        essence.getTaskQueue().enqueue(new DepositToContainerTask());
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getBlockEntity(chestPos) instanceof Container chest
                            && countItem(chest, Items.COBBLESTONE) == 5,
                    "chest should contain the deposited cobblestone");
            helper.assertTrue(essence.getExtraInventory().isEmpty(),
                    "extra inventory should be empty after depositing");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 600)
    public static void collects_items_with_overflow_into_extra_inventory(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        for (int i = 0; i < villager.getInventory().getContainerSize(); i++) {
            villager.getInventory().setItem(i, new ItemStack(Items.STICK, 64)); // vanilla slots full
        }
        helper.spawnItem(Items.COBBLESTONE, 1.5F, 2.5F, 1.5F);
        helper.spawnItem(Items.COBBLESTONE, 3.5F, 2.5F, 2.5F);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getTaskQueue().enqueue(new PickUpItemsTask(helper.absolutePos(CENTER), 8.0));
        helper.succeedWhen(() -> {
            helper.assertTrue(countItem(essence.getExtraInventory(), Items.COBBLESTONE) == 2,
                    "cobblestone should overflow into the extra inventory");
            helper.assertTrue(essence.getTaskQueue().isEmpty(), "pickup task should complete");
        });
    }

    @GameTest(template = "empty5x5")
    public static void essence_persists_through_serialization(GameTestHelper helper) {
        VillagerEssence original = new VillagerEssence();
        original.getTaskQueue().enqueue(new BreakBlockTask(helper.absolutePos(new BlockPos(1, 2, 1))), 3);
        original.getMemory().rememberContainer(helper.absolutePos(new BlockPos(4, 2, 4)), 42);
        original.setHunger(7);
        CompoundTag saved = original.serializeNBT(helper.getLevel().registryAccess());

        VillagerEssence restored = new VillagerEssence();
        restored.deserializeNBT(helper.getLevel().registryAccess(), saved);
        helper.assertTrue(restored.getHunger() == 7, "hunger should survive serialization");
        helper.assertTrue(restored.getTaskQueue().size() == 1, "task should survive serialization");
        helper.assertTrue(restored.getTaskQueue().current() instanceof BreakBlockTask,
                "restored task should keep its type");
        helper.assertTrue(restored.getMemory().knownContainers().size() == 1,
                "memory should survive serialization");
        helper.succeed();
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

    /** Item total across ground drops near a position and the villager's combined inventory. */
    private static int totalItemCount(GameTestHelper helper, Villager villager, BlockPos aroundRelative, Item item) {
        int onGround = helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(aroundRelative)).inflate(4.0),
                        entity -> entity.getItem().is(item))
                .stream().mapToInt(entity -> entity.getItem().getCount()).sum();
        return onGround
                + countItem(villager.getInventory(), item)
                + countItem(VillagerEssence.get(villager).getExtraInventory(), item);
    }

    @GameTest(template = "empty5x5", timeoutTicks = 200)
    public static void picks_up_any_dropped_item_nearby(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        helper.spawnItem(Items.IRON_PICKAXE, 2.5F, 2.5F, 2.5F);
        helper.succeedWhen(() -> helper.assertTrue(
                countItem(villager.getInventory(), Items.IRON_PICKAXE) == 1,
                "villager should pick up any item dropped at its feet"));
    }

    @GameTest(template = "empty5x5")
    public static void does_not_seek_non_food_items(GameTestHelper helper) {
        // Tests the seek gate directly: asserting on emergent movement was racy, because a
        // random idle stroll can walk the villager onto the item (auto-pickup is allowed).
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 2, 3));
        VillagerEssence.get(villager).setHunger(5); // hungry — but pickaxes are not food
        helper.spawnItem(Items.IRON_PICKAXE, 0.5F, 2.5F, 0.5F);
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(
                    SeekFoodItemBehavior.findNearestFood(helper.getLevel(), villager) == null,
                    "a pickaxe must not register as seekable food");
            helper.spawnItem(Items.BREAD, 1.5F, 2.5F, 1.5F);
            helper.runAfterDelay(5, () -> {
                helper.assertTrue(
                        SeekFoodItemBehavior.findNearestFood(helper.getLevel(), villager) != null,
                        "dropped bread must register as seekable food");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 200)
    public static void tops_off_hunger_when_food_available(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.setHunger(15); // above the old "only when starving" threshold
        essence.getExtraInventory().setItem(0, new ItemStack(Items.BREAD, 3));
        helper.succeedWhen(() -> {
            helper.assertTrue(VillagerEssence.get(villager).getHunger() == VillagerEssence.MAX_HUNGER,
                    "villager should eat itself back to full");
            helper.assertTrue(essence.getExtraInventory().getItem(0).getCount() == 2,
                    "exactly one bread should have been eaten (15 + 5 = 20)");
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
