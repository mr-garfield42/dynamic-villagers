package com.dynamicvillagers.gametest;

import com.dynamicvillagers.DynamicVillagers;
import com.dynamicvillagers.village.ConstructionLedger;
import com.dynamicvillagers.village.Names;
import com.dynamicvillagers.village.StorageLedger;
import com.dynamicvillagers.village.Village;
import com.dynamicvillagers.village.VillageEvents;
import com.dynamicvillagers.village.VillageManager;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.behavior.WorkFocusBehavior;
import com.dynamicvillagers.villager.role.VillagerRole;
import com.dynamicvillagers.villager.task.GoToTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

@GameTestHolder(DynamicVillagers.MOD_ID)
@PrefixGameTestTemplate(false)
public class PopulationTests {
    private static final BlockPos BELL = new BlockPos(5, 2, 5);
    private static final BlockPos VILLAGER = new BlockPos(4, 2, 5);

    @GameTest(template = "empty11x11", timeoutTicks = 400, batch = "dvPopulationFormation")
    public static void bell_and_villager_form_named_village(GameTestHelper helper) {
        reset(helper);
        helper.setBlock(BELL, Blocks.BELL);
        Villager villager = helper.spawn(EntityType.VILLAGER, VILLAGER);
        helper.succeedWhen(() -> {
            Village village = VillageManager.get(helper.getLevel()).villageFor(villager.getUUID());
            helper.assertTrue(village != null, "villager beside a bell should form a village");
            helper.assertTrue(VillagerEssence.get(villager).getHomeVillageId() == village.id(),
                    "member attachment should hold the village id");
            helper.assertTrue(VillagerEssence.get(villager).getGeneratedName() != null,
                    "member should have a generated name");
            helper.assertTrue(villager.hasCustomName(), "generated name should be visible by default");
            cleanupState(helper);
        });
    }

    @GameTest(template = "empty11x11", timeoutTicks = 500, batch = "dvPopulationWander")
    public static void member_keeps_home_after_wandering(GameTestHelper helper) {
        reset(helper);
        helper.setBlock(BELL, Blocks.BELL);
        Villager villager = helper.spawn(EntityType.VILLAGER, VILLAGER);
        int[] id = {-1};
        helper.onEachTick(() -> {
            Village home = VillageManager.get(helper.getLevel()).villageFor(villager.getUUID());
            if (home != null && id[0] == -1) {
                id[0] = home.id();
                villager.setPos(villager.getX() + 80, villager.getY(), villager.getZ());
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(id[0] != -1 && VillagerEssence.get(villager).getHomeVillageId() == id[0],
                    "a worker outside the boundary should retain its home village");
            cleanupState(helper);
        });
    }

    @GameTest(template = "empty11x11", timeoutTicks = 500, batch = "dvPopulationDissolve")
    public static void broken_bell_dissolves_village_and_orphans_member(GameTestHelper helper) {
        reset(helper);
        helper.setBlock(BELL, Blocks.BELL);
        Villager villager = helper.spawn(EntityType.VILLAGER, VILLAGER);
        boolean[] removed = {false};
        helper.onEachTick(() -> {
            if (!removed[0] && VillagerEssence.get(villager).getHomeVillageId() != -1) {
                removed[0] = true;
                helper.setBlock(BELL, Blocks.AIR);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(removed[0] && VillagerEssence.get(villager).getHomeVillageId() == -1,
                    "removing the village bell should orphan loaded members");
            cleanupState(helper);
        });
    }

    @GameTest(template = "empty11x11", timeoutTicks = 500, batch = "dvPopulationStorage")
    public static void beds_and_public_storage_are_tracked(GameTestHelper helper) {
        reset(helper);
        helper.setBlock(BELL, Blocks.BELL);
        BlockPos chest = new BlockPos(6, 2, 5);
        helper.setBlock(chest, Blocks.CHEST);
        Villager villager = helper.spawn(EntityType.VILLAGER, VILLAGER);
        int[] baseline = {-1};
        helper.onEachTick(() -> {
            Village village = VillageManager.get(helper.getLevel()).villageFor(villager.getUUID());
            if (village == null) return;
            VillageManager.get(helper.getLevel()).refreshTallies(helper.getLevel(), village);
            if (baseline[0] == -1) {
                baseline[0] = village.beds();
                placeBed(helper, new BlockPos(2, 2, 2));
                return;
            }
            if (village.beds() != baseline[0] + 1) return;
            StorageLedger.ContainerRecord record = StorageLedger.get(helper.getLevel())
                    .getRecord(helper.absolutePos(chest));
            if (record != null && record.designation() == StorageLedger.Designation.PUBLIC
                    && record.villageId() == village.id()) {
                cleanupState(helper);
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty11x11", timeoutTicks = 500, batch = "dvPopulationBreeding")
    public static void breeding_gate_uses_own_village_beds(GameTestHelper helper) {
        reset(helper);
        helper.setBlock(BELL, Blocks.BELL);
        Villager parent = helper.spawn(EntityType.VILLAGER, VILLAGER);
        boolean[] scheduled = {false};
        helper.succeedWhen(() -> {
            Village village = VillageManager.get(helper.getLevel()).villageFor(parent.getUUID());
            helper.assertTrue(village != null, "village should form before checking breeding");
            Villager child = EntityType.VILLAGER.create(helper.getLevel());
            FinalizeSpawnEvent blocked = breedingEvent(helper, child);
            VillageEvents.onFinalizeSpawn(blocked);
            helper.assertTrue(blocked.isSpawnCancelled(), "birth without a village bed should be blocked");
            if (scheduled[0]) return;
            scheduled[0] = true;
            placeBed(helper, new BlockPos(2, 2, 2));
            helper.runAfterDelay(5, () -> {
                FinalizeSpawnEvent allowed = breedingEvent(helper, EntityType.VILLAGER.create(helper.getLevel()));
                VillageEvents.onFinalizeSpawn(allowed);
                helper.assertTrue(!allowed.isSpawnCancelled(), "one bed above population should allow a birth");
                cleanupState(helper);
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty11x11", timeoutTicks = 600, batch = "dvPopulationBuilding")
    public static void manager_posts_house_and_assigns_builder(GameTestHelper helper) {
        reset(helper);
        for (int x = -24; x <= 34; x++) {
            for (int z = -24; z <= 34; z++) {
                helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 1, z)),
                        Blocks.STONE.defaultBlockState(), 2);
            }
        }
        helper.setBlock(BELL, Blocks.BELL);
        java.util.List<Villager> villagers = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            villagers.add(helper.spawn(EntityType.VILLAGER,
                    new BlockPos(3 + i % 5, 2, 3 + i / 5)));
        }
        helper.succeedWhen(() -> {
            Village village = VillageManager.get(helper.getLevel()).villageFor(villagers.getFirst().getUUID());
            helper.assertTrue(village != null, "village should form");
            ConstructionLedger.ConstructionSite site = ConstructionLedger.get(helper.getLevel()).allSites().stream()
                    .filter(candidate -> candidate.villageId() == village.id())
                    .filter(candidate -> candidate.type() == ConstructionLedger.SiteType.HOUSE)
                    .findFirst().orElse(null);
            helper.assertTrue(site != null, "bed pressure should post a vanilla house site");
            boolean assigned = villagers.stream()
                    .anyMatch(villager -> VillagerEssence.get(villager).getAssignedSiteId() == site.id());
            helper.assertTrue(assigned, "an idle adult should be assigned as the site's builder");
            for (int x = -24; x <= 34; x++) {
                for (int z = -24; z <= 34; z++) {
                    if (x < 0 || x > 10 || z < 0 || z > 10) {
                        helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 1, z)),
                                Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
            cleanupState(helper);
        });
    }

    @GameTest(template = "empty5x5", batch = "dvPopulationWorkFocus")
    public static void active_worker_drops_social_follow_target(GameTestHelper helper) {
        Villager worker = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 2));
        Villager other = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 2, 2));
        VillagerEssence.get(worker).getTaskQueue().enqueue(new GoToTask(
                helper.absolutePos(new BlockPos(20, 2, 2)), 1));
        worker.getBrain().setMemory(MemoryModuleType.INTERACTION_TARGET, other);
        worker.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(other, 0.3F, 1));
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(worker.getBrain().getMemory(MemoryModuleType.INTERACTION_TARGET).isEmpty(),
                    "a working villager should stop social interaction");
            WalkTarget walk = worker.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
            helper.assertTrue(walk == null || !(walk.getTarget() instanceof net.minecraft.world.entity.ai.behavior.EntityTracker),
                    "a working villager should stop following another villager");
            helper.succeed();
        });
    }

    @GameTest(template = "empty5x5", batch = "dvPopulationIdleSocial")
    public static void idle_villager_keeps_social_target(GameTestHelper helper) {
        Villager idle = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 2));
        Villager other = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 2, 2));
        idle.getBrain().setMemory(MemoryModuleType.INTERACTION_TARGET, other);
        boolean started = new WorkFocusBehavior().tryStart(helper.getLevel(), idle, helper.getLevel().getGameTime());
        helper.assertTrue(!started, "work focus should not start for an empty task queue");
        helper.assertTrue(idle.getBrain().getMemory(MemoryModuleType.INTERACTION_TARGET).orElse(null) == other,
                "idle villagers should remain free to socialize");
        helper.succeed();
    }

    @GameTest(template = "empty11x11", timeoutTicks = 500, batch = "dvPopulationShelters")
    public static void unhomed_idle_villagers_spread_across_available_houses(GameTestHelper helper) {
        reset(helper);
        helper.getLevel().setDayTime(13000);
        helper.setBlock(BELL, Blocks.BELL);
        BlockPos houseA = new BlockPos(1, 2, 1);
        BlockPos houseB = new BlockPos(8, 2, 8);
        placeBed(helper, houseA);
        placeBed(helper, houseB);
        VillageManager manager = VillageManager.get(helper.getLevel());
        Village village = manager.create(helper.getLevel(), helper.absolutePos(BELL));
        manager.setAutoStaff(village, false);
        List<Villager> villagers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(4 + i % 2, 2, 4 + i / 2));
            manager.adopt(helper.getLevel(), villager, village);
            villagers.add(villager);
        }
        helper.onEachTick(() -> villagers.forEach(villager ->
                villager.getBrain().setActiveActivityIfPossible(Activity.IDLE)));
        long start = helper.getLevel().getGameTime();
        helper.succeedWhen(() -> {
            BlockPos a = helper.absolutePos(houseA.south());
            BlockPos b = helper.absolutePos(houseB.south());
            long atA = villagers.stream().filter(v -> v.blockPosition().closerThan(a, 3.0)).count();
            long atB = villagers.stream().filter(v -> v.blockPosition().closerThan(b, 3.0)).count();
            helper.assertTrue(atA >= 2 && atB >= 2,
                    "unemployed villagers should distribute between houses even outside the rest schedule");
            helper.assertTrue(helper.getLevel().getGameTime() - start <= 300,
                    "idle villagers should settle into separate houses within fifteen seconds");
            cleanupState(helper);
        });
    }

    @GameTest(template = "empty5x5", batch = "dvPopulationEssence")
    public static void phase_five_essence_fields_survive_round_trip(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        VillagerEssence original = VillagerEssence.get(villager);
        original.setHomeVillageId(42);
        original.setGeneratedName("Toren");
        original.setManagerManagedRole(true);
        original.setRole(VillagerRole.FARMER);
        CompoundTag saved = original.serializeNBT(helper.getLevel().registryAccess());
        VillagerEssence restored = new VillagerEssence();
        restored.deserializeNBT(helper.getLevel().registryAccess(), saved);
        helper.assertTrue(restored.getHomeVillageId() == 42, "home village should persist");
        helper.assertTrue("Toren".equals(restored.getGeneratedName()), "generated name should persist");
        helper.assertTrue(restored.isManagerManagedRole(), "manager role ownership should persist");
        helper.succeed();
    }

    @GameTest(template = "empty5x5", batch = "dvPopulationNames")
    public static void village_names_are_deterministic(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
        helper.assertTrue(Names.village(helper.getLevel(), pos).equals(Names.village(helper.getLevel(), pos)),
                "same seed and center should generate the same village name");
        helper.succeed();
    }

    @GameTest(template = "empty11x11", timeoutTicks = 500, batch = "dvPopulationMaturation")
    public static void matured_child_is_available_for_staffing(GameTestHelper helper) {
        reset(helper);
        helper.setBlock(BELL, Blocks.BELL);
        Villager child = helper.spawn(EntityType.VILLAGER, VILLAGER);
        child.setAge(-20);
        helper.succeedWhen(() -> {
            helper.assertTrue(!child.isBaby(), "child should mature using vanilla aging");
            helper.assertTrue(VillagerEssence.get(child).getRole() != VillagerRole.NONE,
                    "matured member should receive a needed role");
            cleanupState(helper);
        });
    }

    @GameTest(template = "empty11x11", timeoutTicks = 400, batch = "dvPopulationUnloaded")
    public static void unloaded_member_still_counts_toward_population(GameTestHelper helper) {
        reset(helper);
        helper.setBlock(BELL, Blocks.BELL);
        Villager villager = helper.spawn(EntityType.VILLAGER, VILLAGER);
        boolean[] removed = {false};
        helper.onEachTick(() -> {
            Village village = VillageManager.get(helper.getLevel()).villageFor(villager.getUUID());
            if (village == null) return;
            if (!removed[0]) {
                removed[0] = true;
                villager.remove(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);
            }
            VillageManager.get(helper.getLevel()).refreshTallies(helper.getLevel(), village);
            if (village.population() == 1) {
                cleanupState(helper);
                helper.succeed();
            }
        });
    }

    private static FinalizeSpawnEvent breedingEvent(GameTestHelper helper, Villager child) {
        BlockPos pos = helper.absolutePos(VILLAGER);
        child.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return new FinalizeSpawnEvent(child, helper.getLevel(), child.getX(), child.getY(), child.getZ(),
                helper.getLevel().getCurrentDifficultyAt(pos), MobSpawnType.BREEDING, null, null);
    }

    private static void placeBed(GameTestHelper helper, BlockPos foot) {
        helper.getLevel().setBlock(helper.absolutePos(foot), Blocks.WHITE_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.SOUTH)
                .setValue(BedBlock.PART, BedPart.FOOT), 2);
        helper.getLevel().setBlock(helper.absolutePos(foot.south()), Blocks.WHITE_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.SOUTH)
                .setValue(BedBlock.PART, BedPart.HEAD), 2);
    }

    private static void reset(GameTestHelper helper) {
        VillageManager.get(helper.getLevel()).clear();
        ConstructionLedger.get(helper.getLevel()).clear();
        StorageLedger.get(helper.getLevel()).clear();
    }

    private static void cleanupState(GameTestHelper helper) {
        helper.setBlock(BELL, Blocks.AIR);
        helper.setBlock(new BlockPos(2, 2, 2), Blocks.AIR);
        helper.setBlock(new BlockPos(2, 2, 3), Blocks.AIR);
        helper.setBlock(new BlockPos(6, 2, 5), Blocks.AIR);
        reset(helper);
    }
}
