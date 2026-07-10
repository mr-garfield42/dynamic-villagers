package com.dynamicvillagers.gametest;

import com.dynamicvillagers.DynamicVillagers;
import com.dynamicvillagers.village.StorageLedger;
import com.dynamicvillagers.villager.PerceptionSystem;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.role.VillagerRole;
import com.dynamicvillagers.villager.task.DepositToContainerTask;
import com.dynamicvillagers.villager.task.TakeItemsTask;
import com.dynamicvillagers.villager.work.ItemFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Phase 3 storage network tests. Every test runs in its own batch AND clears the ledger
 * first: the ledger is level-global SavedData, so records written by one test would
 * otherwise leak into the next (batches share the test server's level), and NETWORK_RANGE
 * (64) spans many neighboring arenas.
 */
@GameTestHolder(DynamicVillagers.MOD_ID)
@PrefixGameTestTemplate(false)
public class StorageNetworkTests {

    private static final BlockPos CENTER = new BlockPos(2, 2, 2);
    private static final BlockPos CHEST = new BlockPos(4, 2, 4);
    private static final BlockPos CHEST_NEAR = new BlockPos(2, 2, 4);
    private static final String LOGS = "item:minecraft:oak_log";

    @GameTest(template = "empty5x5", timeoutTicks = 400, batch = "dvStorageSnapshot")
    public static void deposit_updates_ledger_snapshot(GameTestHelper helper) {
        StorageLedger ledger = freshLedger(helper);
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        helper.setBlock(CHEST, Blocks.CHEST);
        BlockPos chestPos = helper.absolutePos(CHEST);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getMemory().rememberContainer(chestPos, 0);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 5));
        essence.getTaskQueue().enqueue(new DepositToContainerTask());
        helper.succeedWhen(() -> {
            StorageLedger.ContainerRecord record = ledger.getRecord(chestPos);
            helper.assertTrue(record != null, "the visit should create a ledger record");
            helper.assertTrue(record.lastInspected() >= 0, "the record should be marked inspected");
            helper.assertTrue(record.count(ItemFilter.parse("item:minecraft:cobblestone")) == 5,
                    "the ledger should know the chest holds 5 cobblestone");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 400, batch = "dvStorageLedgerTake")
    public static void take_items_uses_ledger_source_it_never_saw(GameTestHelper helper) {
        StorageLedger ledger = freshLedger(helper);
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        helper.setBlock(CHEST, Blocks.CHEST);
        BlockPos chestPos = helper.absolutePos(CHEST);
        Container chest = (Container) helper.getBlockEntity(CHEST);
        chest.setItem(0, new ItemStack(Items.OAK_LOG, 8));
        // village knowledge only: another villager saw the contents; this one never has
        ledger.recordSnapshot(chestPos, chest, 0);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getTaskQueue().enqueue(new TakeItemsTask(LOGS, 4));
        helper.succeedWhen(() -> {
            helper.assertTrue(carriedCount(villager, Items.OAK_LOG) == 4,
                    "villager should fetch logs from a chest it only knows via the ledger");
            helper.assertTrue(essence.getTaskQueue().isEmpty(), "take task should complete");
            StorageLedger.ContainerRecord record = ledger.getRecord(chestPos);
            helper.assertTrue(record != null && record.reservations().isEmpty(),
                    "the reservation should be released on completion");
            helper.assertTrue(record.count(ItemFilter.parse(LOGS)) == 4,
                    "the snapshot should reflect the withdrawal");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 400, batch = "dvStoragePrivateTake")
    public static void private_chest_is_not_looted_by_others(GameTestHelper helper) {
        StorageLedger ledger = freshLedger(helper);
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        helper.setBlock(CHEST, Blocks.CHEST);
        BlockPos chestPos = helper.absolutePos(CHEST);
        Container chest = (Container) helper.getBlockEntity(CHEST);
        chest.setItem(0, new ItemStack(Items.OAK_LOG, 8));
        ledger.recordSnapshot(chestPos, chest, 0);
        ledger.setDesignation(chestPos, StorageLedger.Designation.PRIVATE, UUID.randomUUID());
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getMemory().rememberContainer(chestPos, 0); // it even knows the chest personally
        essence.getTaskQueue().enqueue(new TakeItemsTask(LOGS, 1));
        helper.succeedWhen(() -> {
            helper.assertTrue(essence.getTaskQueue().isEmpty(),
                    "take task should give up rather than open someone's private chest");
            helper.assertTrue(carriedCount(villager, Items.OAK_LOG) == 0,
                    "no logs should have been taken");
            helper.assertTrue(countItem(chest, Items.OAK_LOG) == 8, "the chest must be untouched");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 400, batch = "dvStoragePrivateDeposit")
    public static void private_chest_is_not_a_deposit_target_for_others(GameTestHelper helper) {
        StorageLedger ledger = freshLedger(helper);
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        helper.setBlock(CHEST, Blocks.CHEST);
        BlockPos chestPos = helper.absolutePos(CHEST);
        ledger.setDesignation(chestPos, StorageLedger.Designation.PRIVATE, UUID.randomUUID());
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getMemory().rememberContainer(chestPos, 0);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 5));
        essence.getTaskQueue().enqueue(new DepositToContainerTask());
        helper.succeedWhen(() -> {
            helper.assertTrue(essence.getTaskQueue().isEmpty(), "deposit task should give up");
            helper.assertTrue(carriedCount(villager, Items.COBBLESTONE) == 5,
                    "the cobblestone must stay carried");
            Container chest = (Container) helper.getBlockEntity(CHEST);
            helper.assertTrue(countItem(chest, Items.COBBLESTONE) == 0,
                    "nothing may be stuffed into someone's private chest");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 400, batch = "dvStorageReserve")
    public static void reservation_blocks_second_claim(GameTestHelper helper) {
        StorageLedger ledger = freshLedger(helper);
        helper.setBlock(CHEST, Blocks.CHEST);
        BlockPos chestPos = helper.absolutePos(CHEST);
        Container chest = (Container) helper.getBlockEntity(CHEST);
        chest.setItem(0, new ItemStack(Items.OAK_LOG, 4));
        long now = helper.getLevel().getGameTime();
        ledger.recordSnapshot(chestPos, chest, now);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Predicate<ItemStack> logs = ItemFilter.parse(LOGS);
        helper.assertTrue(ledger.reserve(chestPos, first, logs, 4, now) == 4,
                "the first claim should reserve all 4 logs");
        helper.assertTrue(ledger.findSource(chestPos, chestPos, second, logs, now, Set.of()) == null,
                "a second villager should see nothing available");
        helper.assertTrue(ledger.reserve(chestPos, second, logs, 4, now) == 0,
                "a second claim should reserve nothing");
        helper.assertTrue(ledger.availableTo(chestPos, first, logs, now) == 4,
                "the holder's own claim must not hide the items from itself");
        ledger.release(chestPos, first);
        helper.assertTrue(ledger.findSource(chestPos, chestPos, second, logs, now, Set.of()) != null,
                "after release the logs should be available again");
        helper.succeed();
    }

    @GameTest(template = "empty5x5", timeoutTicks = 400, batch = "dvStoragePublicSort")
    public static void deposit_prefers_public_chest_with_matching_contents(GameTestHelper helper) {
        StorageLedger ledger = freshLedger(helper);
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        helper.setBlock(CHEST_NEAR, Blocks.CHEST);
        helper.setBlock(CHEST, Blocks.CHEST);
        BlockPos nearPos = helper.absolutePos(CHEST_NEAR);
        BlockPos logChestPos = helper.absolutePos(CHEST);
        Container nearChest = (Container) helper.getBlockEntity(CHEST_NEAR);
        Container logChest = (Container) helper.getBlockEntity(CHEST);
        logChest.setItem(0, new ItemStack(Items.OAK_LOG, 10));
        ledger.recordSnapshot(nearPos, nearChest, 0);
        ledger.recordSnapshot(logChestPos, logChest, 0);
        ledger.setDesignation(nearPos, StorageLedger.Designation.PUBLIC, null);
        ledger.setDesignation(logChestPos, StorageLedger.Designation.PUBLIC, null);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.OAK_LOG, 5));
        essence.getTaskQueue().enqueue(new DepositToContainerTask());
        helper.succeedWhen(() -> {
            helper.assertTrue(countItem(logChest, Items.OAK_LOG) == 15,
                    "logs should land in the public chest that already holds logs, not the nearer empty one");
            helper.assertTrue(countItem(nearChest, Items.OAK_LOG) == 0,
                    "the nearer public chest should stay empty");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 400, batch = "dvStorageStale")
    public static void stale_ledger_is_corrected_on_visit(GameTestHelper helper) {
        StorageLedger ledger = freshLedger(helper);
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        helper.setBlock(CHEST, Blocks.CHEST);
        BlockPos chestPos = helper.absolutePos(CHEST);
        Container chest = (Container) helper.getBlockEntity(CHEST);
        chest.setItem(0, new ItemStack(Items.OAK_LOG, 8));
        ledger.recordSnapshot(chestPos, chest, 0);
        chest.clearContent(); // a player empties the chest behind the village's back
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getTaskQueue().enqueue(new TakeItemsTask(LOGS, 4));
        helper.succeedWhen(() -> {
            helper.assertTrue(essence.getTaskQueue().isEmpty(),
                    "the task should fail gracefully after finding the chest empty");
            helper.assertTrue(carriedCount(villager, Items.OAK_LOG) == 0,
                    "no logs can come from an empty chest");
            StorageLedger.ContainerRecord record = ledger.getRecord(chestPos);
            helper.assertTrue(record != null && record.count(ItemFilter.parse(LOGS)) == 0,
                    "the visit should correct the stale snapshot");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 200, batch = "dvStoragePerception")
    public static void furnaces_are_not_storage(GameTestHelper helper) {
        freshLedger(helper);
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.FURNACE);
        helper.setBlock(CHEST, Blocks.CHEST);
        helper.runAfterDelay(5, () -> {
            PerceptionSystem.scan(helper.getLevel(), villager);
            VillagerEssence essence = VillagerEssence.get(villager);
            helper.assertTrue(essence.getMemory().knownContainers().contains(helper.absolutePos(CHEST)),
                    "the chest should be remembered");
            helper.assertTrue(!essence.getMemory().knownContainers().contains(helper.absolutePos(new BlockPos(1, 2, 1))),
                    "the furnace must not be remembered as storage");
            helper.succeed();
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 1200, batch = "dvStorageRequestHaul")
    public static void request_is_hauled_from_known_storage(GameTestHelper helper) {
        StorageLedger ledger = freshLedger(helper);
        helper.getLevel().setDayTime(1000); // planner only works in daylight
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        helper.setBlock(CHEST, Blocks.CHEST);
        BlockPos destSpot = new BlockPos(0, 2, 4);
        helper.setBlock(destSpot, Blocks.CHEST);
        BlockPos sourcePos = helper.absolutePos(CHEST);
        BlockPos destPos = helper.absolutePos(destSpot);
        Container source = (Container) helper.getBlockEntity(CHEST);
        Container destination = (Container) helper.getBlockEntity(destSpot);
        source.setItem(0, new ItemStack(Items.OAK_LOG, 8));
        ledger.recordSnapshot(sourcePos, source, 0);
        ledger.setDesignation(sourcePos, StorageLedger.Designation.PUBLIC, null);
        ledger.addRequest(LOGS, 4, destPos, 0);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.setRole(VillagerRole.LUMBERJACK);
        helper.succeedWhen(() -> {
            helper.assertTrue(countItem(destination, Items.OAK_LOG) == 4,
                    "4 logs should be hauled to the requesting chest");
            helper.assertTrue(countItem(source, Items.OAK_LOG) == 4,
                    "the other 4 logs should stay in the source chest");
            helper.assertTrue(ledger.allRequests().isEmpty(),
                    "the request should be satisfied and removed");
        });
    }

    @GameTest(template = "empty5x5", timeoutTicks = 1200, batch = "dvStorageRequestProduce")
    public static void gatherer_delivers_produce_directly_to_requester(GameTestHelper helper) {
        StorageLedger ledger = freshLedger(helper);
        helper.getLevel().setDayTime(1000);
        Villager villager = helper.spawn(EntityType.VILLAGER, CENTER);
        helper.setBlock(CHEST, Blocks.CHEST);
        BlockPos destPos = helper.absolutePos(CHEST);
        Container destination = (Container) helper.getBlockEntity(CHEST);
        ledger.addRequest(LOGS, 16, destPos, 0);
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.setRole(VillagerRole.LUMBERJACK);
        essence.getMemory().rememberContainer(destPos, 0);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.OAK_LOG, 20)); // haul-ready
        helper.succeedWhen(() -> {
            helper.assertTrue(countItem(destination, Items.OAK_LOG) == 16,
                    "exactly the requested 16 logs should be delivered (not the whole armful)");
            helper.assertTrue(ledger.allRequests().isEmpty(),
                    "the request should be satisfied and removed");
            helper.assertTrue(carriedCount(villager, Items.OAK_LOG) == 4,
                    "the surplus logs should stay carried");
        });
    }

    /**
     * Regression (owner crash, 2026-07-10): snapshots merge same-kind stacks, so a chest
     * holding several stacks of one item produces a record count above the vanilla stack
     * size — which ItemStack's codec refuses ([1;99]), crashing the world save.
     */
    @GameTest(template = "empty5x5", batch = "dvStorageOversized")
    public static void ledger_saves_merged_stacks_beyond_max_size(GameTestHelper helper) {
        StorageLedger ledger = freshLedger(helper);
        net.minecraft.world.SimpleContainer chest = new net.minecraft.world.SimpleContainer(27);
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        chest.setItem(1, new ItemStack(Items.COBBLESTONE, 64));
        chest.setItem(2, new ItemStack(Items.COBBLESTONE, 64));
        BlockPos pos = helper.absolutePos(CHEST);
        ledger.recordSnapshot(pos, chest, helper.getLevel().getGameTime());

        // before the fix this line threw IllegalStateException and killed the save
        ledger.save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess());

        StorageLedger.ContainerRecord record = ledger.getRecord(pos);
        helper.assertTrue(record != null
                        && record.count(stack -> stack.is(Items.COBBLESTONE)) == 192,
                "the merged record should still know all 192 cobblestone");
        helper.succeed();
    }

    private static StorageLedger freshLedger(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StorageLedger ledger = StorageLedger.get(level);
        ledger.clear();
        return ledger;
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
}
