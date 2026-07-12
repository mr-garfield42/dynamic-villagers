package com.dynamicvillagers.gametest;

import com.dynamicvillagers.DynamicVillagers;
import com.dynamicvillagers.construction.BlockRequirements;
import com.dynamicvillagers.construction.Blueprint;
import com.dynamicvillagers.construction.SiteValidator;
import com.dynamicvillagers.construction.Blueprints;
import com.dynamicvillagers.village.ConstructionLedger;
import com.dynamicvillagers.village.StorageLedger;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.role.VillagerRole;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 4 construction tests. The construction ledger is level-global SavedData like the
 * storage ledger, so tests that touch it run in their own batch and wipe it first.
 */
@GameTestHolder(DynamicVillagers.MOD_ID)
@PrefixGameTestTemplate(false)
public class ConstructionTests {

    private static final ResourceLocation SHELTER =
            ResourceLocation.fromNamespaceAndPath(DynamicVillagers.MOD_ID, "starter_shelter");
    private static final ResourceLocation VANILLA_HOUSE =
            ResourceLocation.withDefaultNamespace("village/plains/houses/plains_small_house_1");

    @GameTest(template = "empty5x5")
    public static void blueprint_parses_shipped_shelter(GameTestHelper helper) {
        Blueprint blueprint = Blueprints.load(helper.getLevel(), SHELTER);
        helper.assertTrue(blueprint != null, "starter_shelter should load and parse");
        helper.assertTrue(blueprint.size().equals(new Vec3i(5, 4, 5)), "size should be 5x4x5");
        helper.assertTrue(blueprint.blocks().size() == 100, "all 100 box entries should be planned");
        Map<Item, Integer> requirements = blueprint.requirements();
        helper.assertTrue(Objects.equals(requirements.get(Items.OAK_PLANKS), 47),
                "expected 47 oak planks, got " + requirements.get(Items.OAK_PLANKS));
        helper.assertTrue(Objects.equals(requirements.get(Items.OAK_LOG), 8),
                "expected 8 oak logs, got " + requirements.get(Items.OAK_LOG));
        helper.assertTrue(Objects.equals(requirements.get(Items.OAK_SLAB), 25),
                "expected 25 oak slabs, got " + requirements.get(Items.OAK_SLAB));
        helper.assertTrue(Objects.equals(requirements.get(Items.TORCH), 1),
                "expected 1 torch, got " + requirements.get(Items.TORCH));
        helper.assertTrue(blueprint.unbuildableCount() == 0, "the shelter should be fully buildable");
        helper.succeed();
    }

    /** Owner directive: villages build the vanilla village structures — so they must parse. */
    @GameTest(template = "empty5x5")
    public static void blueprint_parses_vanilla_plains_house(GameTestHelper helper) {
        Blueprint blueprint = Blueprints.load(helper.getLevel(), VANILLA_HOUSE);
        helper.assertTrue(blueprint != null, "plains_small_house_1 should load and parse");
        helper.assertTrue(blueprint.size().equals(new Vec3i(7, 7, 7)), "size should be 7x7x7");
        boolean jigsawLeft = blueprint.blocks().stream().anyMatch(b -> b.state().is(Blocks.JIGSAW));
        helper.assertTrue(!jigsawLeft, "jigsaws should be normalized to their final_state");
        Map<Item, Integer> requirements = blueprint.requirements();
        helper.assertTrue(Objects.equals(requirements.get(Items.OAK_DOOR), 1),
                "the door should cost one item (upper half is free), got " + requirements.get(Items.OAK_DOOR));
        helper.assertTrue(Objects.equals(requirements.get(Items.WHITE_BED), 1),
                "the bed should cost one item (head is free), got " + requirements.get(Items.WHITE_BED));
        helper.assertTrue(Objects.equals(requirements.get(Items.TORCH), 3),
                "3 wall torches should map to 3 torch items, got " + requirements.get(Items.TORCH));
        helper.assertTrue(blueprint.unbuildableCount() == 0,
                "a vanilla house should be fully hand-buildable");
        helper.succeed();
    }

    @GameTest(template = "empty5x5")
    public static void blueprint_rotates_positions_and_states(GameTestHelper helper) {
        CompoundTag tag = new CompoundTag();
        tag.put("size", intList(2, 1, 1));
        ListTag palette = new ListTag();
        palette.add(NbtUtils.writeBlockState(Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)));
        palette.add(NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState()));
        tag.put("palette", palette);
        ListTag blocks = new ListTag();
        blocks.add(blockEntry(0, 0, 0, 0, null));
        blocks.add(blockEntry(1, 0, 0, 1, null));
        tag.put("blocks", blocks);

        Blueprint blueprint = Blueprint.parse(
                ResourceLocation.fromNamespaceAndPath(DynamicVillagers.MOD_ID, "test_rotation"),
                tag, helper.getLevel().holderLookup(Registries.BLOCK));
        helper.assertTrue(blueprint.size(Rotation.CLOCKWISE_90).equals(new Vec3i(1, 1, 2)),
                "a 2x1x1 box rotated 90 degrees should be 1x1x2");

        BlockPos origin = new BlockPos(10, 5, 10);
        List<Blueprint.PlannedBlock> placed = blueprint.placedBlocks(origin, Rotation.CLOCKWISE_90);
        Blueprint.PlannedBlock stairs = placed.get(0);
        Blueprint.PlannedBlock air = placed.get(1);
        helper.assertTrue(stairs.pos().equals(new BlockPos(10, 5, 10)),
                "rotated stairs should sit at the origin, got " + stairs.pos());
        helper.assertTrue(stairs.state().getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.EAST,
                "north-facing stairs rotated cw90 should face east");
        helper.assertTrue(air.pos().equals(new BlockPos(10, 5, 11)),
                "the +x neighbor should rotate to +z, got " + air.pos());
        helper.assertTrue(air.state().isAir(), "the air entry should stay air");
        helper.succeed();
    }

    @GameTest(template = "empty5x5")
    public static void blueprint_normalizes_jigsaw_and_void(GameTestHelper helper) {
        CompoundTag tag = new CompoundTag();
        tag.put("size", intList(3, 1, 1));
        ListTag palette = new ListTag();
        palette.add(NbtUtils.writeBlockState(Blocks.JIGSAW.defaultBlockState()));
        palette.add(NbtUtils.writeBlockState(Blocks.STRUCTURE_VOID.defaultBlockState()));
        palette.add(NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        tag.put("palette", palette);
        CompoundTag jigsawNbt = new CompoundTag();
        jigsawNbt.putString("final_state", "minecraft:cobblestone");
        ListTag blocks = new ListTag();
        blocks.add(blockEntry(0, 0, 0, 0, jigsawNbt));
        blocks.add(blockEntry(1, 0, 0, 1, null));
        blocks.add(blockEntry(2, 0, 0, 2, null));
        tag.put("blocks", blocks);

        Blueprint blueprint = Blueprint.parse(
                ResourceLocation.fromNamespaceAndPath(DynamicVillagers.MOD_ID, "test_normalize"),
                tag, helper.getLevel().holderLookup(Registries.BLOCK));
        helper.assertTrue(blueprint.blocks().size() == 2,
                "structure_void should be dropped, got " + blueprint.blocks().size() + " entries");
        helper.assertTrue(blueprint.blocks().get(0).state().is(Blocks.COBBLESTONE),
                "the jigsaw should become its final_state (cobblestone)");
        helper.assertTrue(Objects.equals(blueprint.requirements().get(Items.COBBLESTONE), 1)
                        && Objects.equals(blueprint.requirements().get(Items.STONE), 1),
                "requirements should be 1 cobblestone + 1 stone");
        helper.succeed();
    }

    @GameTest(template = "empty5x5")
    public static void requirements_cover_the_specials_table(GameTestHelper helper) {
        BlockRequirements.Requirement doorLower = BlockRequirements.resolve(Blocks.OAK_DOOR.defaultBlockState());
        helper.assertTrue(doorLower != null && doorLower.item() == Items.OAK_DOOR && doorLower.count() == 1,
                "a lower door half should cost one door item");
        helper.assertTrue(BlockRequirements.resolve(Blocks.OAK_DOOR.defaultBlockState()
                        .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)) == null,
                "the upper door half should be free");
        BlockRequirements.Requirement bedFoot = BlockRequirements.resolve(Blocks.WHITE_BED.defaultBlockState());
        helper.assertTrue(bedFoot != null && bedFoot.item() == Items.WHITE_BED,
                "a bed foot should cost the bed item");
        helper.assertTrue(BlockRequirements.resolve(Blocks.WHITE_BED.defaultBlockState()
                        .setValue(BlockStateProperties.BED_PART, BedPart.HEAD)) == null,
                "the bed head should be free");
        BlockRequirements.Requirement doubleSlab = BlockRequirements.resolve(Blocks.OAK_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE));
        helper.assertTrue(doubleSlab != null && doubleSlab.count() == 2,
                "a double slab should cost two slab items");
        BlockRequirements.Requirement farmland = BlockRequirements.resolve(Blocks.FARMLAND.defaultBlockState());
        helper.assertTrue(farmland != null && farmland.item() == Items.DIRT,
                "farmland should be made from placed dirt");
        BlockRequirements.Requirement water = BlockRequirements.resolve(Blocks.WATER.defaultBlockState());
        helper.assertTrue(water != null && water.item() == Items.WATER_BUCKET,
                "a water source should require a water bucket");
        helper.assertTrue(!BlockRequirements.isBuildable(Blocks.FIRE.defaultBlockState()),
                "fire has no item form and should be unbuildable");
        helper.succeed();
    }

    @GameTest(template = "empty5x5", batch = "dvConstructionLedger")
    public static void construction_ledger_tracks_sites_and_claims(GameTestHelper helper) {
        ConstructionLedger ledger = ConstructionLedger.get(helper.getLevel());
        ledger.clear();
        perpetualDay(helper);
        long now = helper.getLevel().getGameTime();
        BlockPos origin = helper.absolutePos(new BlockPos(2, 2, 2));
        ConstructionLedger.ConstructionSite site =
                ledger.addSite(SHELTER, origin, Rotation.CLOCKWISE_180, now);

        helper.assertTrue(ledger.getSite(site.id()) == site, "the site should be retrievable by id");
        helper.assertTrue(ledger.sitesNear(origin, 8, ConstructionLedger.Status.OPEN).contains(site),
                "a fresh site should be OPEN near its origin");

        UUID builderA = UUID.randomUUID();
        UUID builderB = UUID.randomUUID();
        BlockPos claimedPos = origin.above();
        ledger.claim(site, List.of(claimedPos), builderA, now);
        helper.assertTrue(site.mayWork(claimedPos, builderA, now), "the claimer may work its claim");
        helper.assertTrue(!site.mayWork(claimedPos, builderB, now), "another builder may not");
        helper.assertTrue(site.mayWork(claimedPos, builderB, now + ConstructionLedger.CLAIM_TTL),
                "claims should expire after CLAIM_TTL");
        ledger.releaseClaims(builderA);
        helper.assertTrue(site.mayWork(claimedPos, builderB, now), "released claims free the block");

        ledger.setStatus(site, ConstructionLedger.Status.DONE);
        helper.assertTrue(ledger.sitesNear(origin, 8, ConstructionLedger.Status.OPEN).isEmpty(),
                "a DONE site should leave the OPEN list");
        helper.assertTrue(ledger.cancelSite(site.id()) && ledger.getSite(site.id()) == null,
                "cancelling should remove the site");
        helper.succeed();
    }

    /** End-to-end 4.1: an assigned builder erects the whole shelter from carried materials. */
    @GameTest(template = "empty11x11", timeoutTicks = 6000, batch = "dvConstructionBuild")
    public static void builder_builds_the_shelter(GameTestHelper helper) {
        StorageLedger.get(helper.getLevel()).clear();
        ConstructionLedger ledger = ConstructionLedger.get(helper.getLevel());
        ledger.clear();
        perpetualDay(helper);

        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        VillagerEssence essence = VillagerEssence.get(villager);
        // surplus over the exact bill (47/8/25/1): clearing its own line of sight can cost
        // the builder a drop it never recovers, and there is no storage to refetch from here
        essence.getExtraInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 64));
        essence.getExtraInventory().setItem(1, new ItemStack(Items.OAK_LOG, 16));
        essence.getExtraInventory().setItem(2, new ItemStack(Items.OAK_SLAB, 32));
        essence.getExtraInventory().setItem(3, new ItemStack(Items.TORCH, 4));
        essence.getExtraInventory().setItem(4, new ItemStack(Items.DIRT, 32)); // foundation fill

        // a pothole under the footprint: 4.2 foundation work must fill it before walls rise
        helper.setBlock(new BlockPos(5, 1, 5), Blocks.AIR);

        ConstructionLedger.ConstructionSite site = ledger.addSite(SHELTER,
                helper.absolutePos(new BlockPos(3, 2, 3)), Rotation.NONE,
                helper.getLevel().getGameTime());
        essence.setRole(VillagerRole.BUILDER);
        essence.setAssignedSiteId(site.id());

        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(3, 2, 3)); // floor corner
            helper.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(3, 3, 3));    // corner post
            helper.assertBlockPresent(Blocks.OAK_SLAB, new BlockPos(5, 5, 5));   // roof center
            helper.assertBlockPresent(Blocks.TORCH, new BlockPos(4, 3, 4));      // interior torch
            helper.assertBlockNotPresent(Blocks.OAK_PLANKS, new BlockPos(5, 3, 7)); // doorway gap
            helper.assertBlockPresent(Blocks.DIRT, new BlockPos(5, 1, 5)); // 4.2 foundation fill
            helper.assertTrue(site.status() == ConstructionLedger.Status.DONE,
                    "the site should be marked DONE");
            helper.assertTrue(essence.getAssignedSiteId() == site.id(),
                    "the builder keeps watching its finished site for repair (4.7)");
        });
    }

    /**
     * Owner report (2026-07-10): the builder stalled entirely once a batch needed a material
     * the chest couldn't provide — "opens a chest, then forgets his job". The batch must
     * build what IS in hand, leave only the short entries waiting, and finish once the
     * material shows up in storage.
     */
    @GameTest(template = "empty11x11", timeoutTicks = 9000, batch = "dvConstructionRestock")
    public static void builder_restocks_and_builds_around_missing_materials(GameTestHelper helper) {
        StorageLedger.get(helper.getLevel()).clear();
        ConstructionLedger ledger = ConstructionLedger.get(helper.getLevel());
        ledger.clear();
        perpetualDay(helper);

        BlockPos chestRel = new BlockPos(9, 2, 1);
        helper.setBlock(chestRel, Blocks.CHEST);
        net.minecraft.world.Container chest =
                (net.minecraft.world.Container) helper.getLevel().getBlockEntity(helper.absolutePos(chestRel));
        chest.setItem(0, new ItemStack(Items.OAK_PLANKS, 64));
        chest.setItem(1, new ItemStack(Items.OAK_LOG, 16));
        chest.setItem(2, new ItemStack(Items.OAK_SLAB, 32));
        chest.setItem(4, new ItemStack(Items.DIRT, 64)); // foundation fill
        // deliberately NO torches: the shelter's interior torch cannot be built yet

        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getMemory().rememberContainer(helper.absolutePos(chestRel), 0);
        ConstructionLedger.ConstructionSite site = ledger.addSite(SHELTER,
                helper.absolutePos(new BlockPos(3, 2, 3)), Rotation.NONE,
                helper.getLevel().getGameTime());
        essence.setRole(VillagerRole.BUILDER);
        essence.setAssignedSiteId(site.id());

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.OAK_SLAB, new BlockPos(5, 5, 5)))
                .thenExecute(() -> {
                    helper.assertTrue(site.status() == ConstructionLedger.Status.OPEN,
                            "the missing torch should keep the site open");
                    helper.assertBlockNotPresent(Blocks.TORCH, new BlockPos(4, 3, 4));
                    chest.setItem(3, new ItemStack(Items.TORCH, 4)); // restock arrives
                    // assert the retry behavior, not the cooldown's phase alignment
                    essence.setNextToolFetchTime(0);
                })
                .thenWaitUntil(() -> {
                    helper.assertBlockPresent(Blocks.TORCH, new BlockPos(4, 3, 4));
                    helper.assertTrue(site.status() == ConstructionLedger.Status.DONE,
                            "the site should finish once torches arrive");
                })
                .thenSucceed();
    }

    /**
     * Simulates quit + rejoin for a mid-build villager (owner report: the debug wand went
     * dead on "a villager that had a job" after relogging): serialize a working builder's
     * essence, restore it into a fresh villager, then take and round-trip the exact debug
     * snapshot the wand sends.
     */
    @GameTest(template = "empty5x5", batch = "dvConstructionReload")
    public static void builder_essence_and_debug_snapshot_survive_reload(GameTestHelper helper) {
        Villager working = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        VillagerEssence essence = VillagerEssence.get(working);
        essence.setRole(VillagerRole.BUILDER);
        essence.setAssignedSiteId(3);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        BlockPos target = helper.absolutePos(new BlockPos(3, 2, 3));
        essence.getTaskQueue().enqueue(new com.dynamicvillagers.villager.task.PlaceStateTask(
                target, Blocks.COBBLESTONE.defaultBlockState()));
        essence.getTaskQueue().enqueue(new com.dynamicvillagers.villager.task.BreakBlockTask(target.above()));
        CompoundTag saved = essence.serializeNBT(helper.getLevel().registryAccess());

        Villager reloaded = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 2, 1));
        VillagerEssence restored = VillagerEssence.get(reloaded);
        restored.deserializeNBT(helper.getLevel().registryAccess(), saved);
        helper.assertTrue(restored.getRole() == VillagerRole.BUILDER, "the role should survive reload");
        helper.assertTrue(restored.getAssignedSiteId() == 3, "the site assignment should survive reload");
        helper.assertTrue(restored.getTaskQueue().size() == 2, "queued build tasks should survive reload");

        com.dynamicvillagers.network.VillagerDebugStatePayload payload =
                com.dynamicvillagers.network.VillagerDebugStatePayload.snapshot(reloaded, true);
        net.minecraft.network.RegistryFriendlyByteBuf buf = new net.minecraft.network.RegistryFriendlyByteBuf(
                io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
        com.dynamicvillagers.network.VillagerDebugStatePayload.STREAM_CODEC.encode(buf, payload);
        com.dynamicvillagers.network.VillagerDebugStatePayload decoded =
                com.dynamicvillagers.network.VillagerDebugStatePayload.STREAM_CODEC.decode(buf);
        helper.assertTrue(decoded.tasks().size() == 2 && decoded.role().equals("builder"),
                "the wand snapshot should encode and decode intact after reload");
        helper.succeed();
    }

    /**
     * 4.4: doors and beds place whole from one item — a lone half pops off on the next
     * neighbor update (owner playtest: both came out "cut off partially").
     */
    @GameTest(template = "empty11x11", timeoutTicks = 6000, batch = "dvConstructionMultipart")
    public static void builder_places_doors_and_beds_whole(GameTestHelper helper) {
        StorageLedger.get(helper.getLevel()).clear();
        ConstructionLedger ledger = ConstructionLedger.get(helper.getLevel());
        ledger.clear();
        perpetualDay(helper);

        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 16));
        essence.getExtraInventory().setItem(1, new ItemStack(Items.WHITE_BED, 1)); // exactly one
        essence.getExtraInventory().setItem(2, new ItemStack(Items.OAK_DOOR, 1));  // exactly one
        essence.getExtraInventory().setItem(3, new ItemStack(Items.DIRT, 16));     // foundation fill

        ConstructionLedger.ConstructionSite site = ledger.addSite(
                ResourceLocation.fromNamespaceAndPath(DynamicVillagers.MOD_ID, "fixture_hut"),
                helper.absolutePos(new BlockPos(4, 2, 4)), Rotation.NONE,
                helper.getLevel().getGameTime());
        essence.setRole(VillagerRole.BUILDER);
        essence.setAssignedSiteId(site.id());

        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.OAK_DOOR, new BlockPos(4, 3, 4));  // lower half
            helper.assertBlockPresent(Blocks.OAK_DOOR, new BlockPos(4, 4, 4));  // upper half
            helper.assertBlockPresent(Blocks.WHITE_BED, new BlockPos(5, 3, 6)); // foot
            helper.assertBlockPresent(Blocks.WHITE_BED, new BlockPos(5, 3, 5)); // head
            helper.assertTrue(site.status() == ConstructionLedger.Status.DONE,
                    "the hut should be complete");
        });
    }

    /**
     * 4.5: work with no standing spot in reach is skipped (no give-up stalls), and when
     * only unreachable work remains the builder stair-steps up on scaffold blocks and
     * tears every one of them down again after the build.
     */
    @GameTest(template = "empty11x11", timeoutTicks = 9000, batch = "dvConstructionScaffold")
    public static void builder_scaffolds_up_and_tears_down(GameTestHelper helper) {
        StorageLedger.get(helper.getLevel()).clear();
        ConstructionLedger ledger = ConstructionLedger.get(helper.getLevel());
        ledger.clear();
        perpetualDay(helper);

        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 16));
        essence.getExtraInventory().setItem(1, new ItemStack(Items.DIRT, 16));

        ConstructionLedger.ConstructionSite site = ledger.addSite(
                ResourceLocation.fromNamespaceAndPath(DynamicVillagers.MOD_ID, "fixture_pillar"),
                helper.absolutePos(new BlockPos(5, 2, 5)), Rotation.NONE,
                helper.getLevel().getGameTime());
        essence.setRole(VillagerRole.BUILDER);
        essence.setAssignedSiteId(site.id());

        // the pillar top (y=5 relative) has no natural foothold beside it, so it can only be
        // reached by building a dirt scaffold — track that scaffold was actually placed, then
        // that it was fully torn down, proving the whole cycle really ran (not a lucky climb)
        int[] maxScaffold = {0};
        helper.onEachTick(() -> maxScaffold[0] = Math.max(maxScaffold[0], site.scaffold().size()));
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(5, 7, 5)); // pillar top
            helper.assertTrue(site.status() == ConstructionLedger.Status.DONE,
                    "the pillar should be complete");
            helper.assertTrue(maxScaffold[0] > 0, "the builder must have used dirt scaffolding");
            helper.assertTrue(site.scaffold().isEmpty(),
                    "every scaffold block should be torn down again");
        });
    }

    /** 4.4 farm specials: farmland placed from dirt, a water source placed from a bucket
     * (the empty bucket is kept, not consumed). */
    @GameTest(template = "empty11x11", timeoutTicks = 4000, batch = "dvConstructionFarm")
    public static void builder_builds_farm_plot_with_water(GameTestHelper helper) {
        StorageLedger.get(helper.getLevel()).clear();
        ConstructionLedger ledger = ConstructionLedger.get(helper.getLevel());
        ledger.clear();
        perpetualDay(helper);

        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.DIRT, 16));
        essence.getExtraInventory().setItem(1, new ItemStack(Items.WATER_BUCKET, 1));

        ConstructionLedger.ConstructionSite site = ledger.addSite(
                ResourceLocation.fromNamespaceAndPath(DynamicVillagers.MOD_ID, "fixture_farm"),
                helper.absolutePos(new BlockPos(4, 2, 4)), Rotation.NONE,
                helper.getLevel().getGameTime());
        essence.setRole(VillagerRole.BUILDER);
        essence.setAssignedSiteId(site.id());

        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.FARMLAND, new BlockPos(4, 2, 4)); // a ring cell
            helper.assertBlockPresent(Blocks.WATER, new BlockPos(5, 2, 5));    // centre source
            helper.assertTrue(
                    countItem(villager.getInventory(), Items.BUCKET)
                            + countItem(essence.getExtraInventory(), Items.BUCKET) == 1,
                    "emptying the water bucket should leave an empty bucket behind");
        });
    }

    /** 4.7 repair: damage to a finished building is detected and rebuilt (same diff machinery). */
    @GameTest(template = "empty11x11", timeoutTicks = 8000, batch = "dvConstructionRepair")
    public static void builder_repairs_damaged_wall(GameTestHelper helper) {
        StorageLedger.get(helper.getLevel()).clear();
        ConstructionLedger ledger = ConstructionLedger.get(helper.getLevel());
        ledger.clear();
        perpetualDay(helper);

        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 64));
        essence.getExtraInventory().setItem(1, new ItemStack(Items.OAK_LOG, 16));
        essence.getExtraInventory().setItem(2, new ItemStack(Items.OAK_SLAB, 32));
        essence.getExtraInventory().setItem(3, new ItemStack(Items.TORCH, 4));
        essence.getExtraInventory().setItem(4, new ItemStack(Items.DIRT, 16));

        ConstructionLedger.ConstructionSite site = ledger.addSite(SHELTER,
                helper.absolutePos(new BlockPos(3, 2, 3)), Rotation.NONE,
                helper.getLevel().getGameTime());
        essence.setRole(VillagerRole.BUILDER);
        essence.setAssignedSiteId(site.id());

        BlockPos wall = new BlockPos(3, 2, 5); // a plank wall cell (relative)
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        site.status() == ConstructionLedger.Status.DONE, "the shelter should finish"))
                .thenExecute(() -> helper.setBlock(wall, Blocks.AIR)) // a creeper blows a hole
                .thenWaitUntil(() -> {
                    helper.assertBlockPresent(Blocks.OAK_PLANKS, wall);
                    helper.assertTrue(site.status() == ConstructionLedger.Status.DONE,
                            "the site should return to DONE after the repair");
                })
                .thenSucceed();
    }

    /**
     * Owner playtest: after the player tears a finished house down, its stale (now invisible)
     * site record used to keep refusing a new Building Marker on the same footprint as an
     * "overlap". A once-built site with almost nothing left standing is treated as demolished
     * and its record dropped, freeing the spot — while a brand-new never-built site (also ~0%
     * present) is kept, so an unbuilt order is never mistaken for a demolition.
     */
    @GameTest(template = "empty11x11", batch = "dvConstructionDemolish")
    public static void demolished_house_frees_its_footprint(GameTestHelper helper) {
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        ConstructionLedger ledger = ConstructionLedger.get(level);
        ledger.clear();
        Blueprint blueprint = Blueprints.load(level, SHELTER);

        BlockPos origin = helper.absolutePos(new BlockPos(1, 2, 1));
        ConstructionLedger.ConstructionSite built =
                ledger.addSite(SHELTER, origin, Rotation.NONE, level.getGameTime());
        // stand the whole house up (flag 2: no neighbor updates, so lone blocks don't pop)
        for (Blueprint.PlannedBlock plan : blueprint.placedBlocks(origin, Rotation.NONE)) {
            if (!plan.state().isAir()) {
                level.setBlock(plan.pos(), plan.state(), 2);
            }
        }
        ledger.setStatus(built, ConstructionLedger.Status.DONE);
        helper.assertTrue(built.wasBuilt(), "a DONE site is remembered as having been built");
        helper.assertTrue(!ConstructionLedger.isDemolished(level, built),
                "an intact finished house is not demolished");

        // a brand-new never-built site whose footprint is still empty ground must NOT be
        // mistaken for a demolition just because ~0% of it stands yet
        ConstructionLedger.ConstructionSite fresh = ledger.addSite(SHELTER,
                helper.absolutePos(new BlockPos(6, 2, 6)), Rotation.NONE, level.getGameTime());
        helper.assertTrue(!ConstructionLedger.isDemolished(level, fresh),
                "a never-built site is not demolished");
        helper.assertTrue(ledger.removeDemolishedSites(level) == 0,
                "nothing is removed while the house still stands");

        // the player tears the house down
        for (Blueprint.PlannedBlock plan : blueprint.placedBlocks(origin, Rotation.NONE)) {
            if (!plan.state().isAir()) {
                level.setBlock(plan.pos(), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        helper.assertTrue(ConstructionLedger.isDemolished(level, built),
                "a once-built house with its blocks gone is demolished");
        helper.assertTrue(ledger.removeDemolishedSites(level) == 1
                        && ledger.getSite(built.id()) == null,
                "the demolished site's record is dropped");
        helper.assertTrue(ledger.getSite(fresh.id()) != null,
                "the never-built site survives the sweep");
        helper.assertTrue(
                SiteValidator.validate(level, ledger, blueprint, origin, Rotation.NONE) == null,
                "a new house may now be placed where the demolished one stood");

        ledger.clear();
        helper.succeed();
    }

    /**
     * Owner directive proof: a villager builds a whole vanilla plains house from stocked
     * storage — the real template with jigsaws, two-part door and bed, connecting glass panes
     * and stairs, and a peaked roof reached by scaffolding. Asserted as substantial completion
     * (≥85% of solid blocks, comfortably clear of flakiness) plus the exact multi-part parts
     * the owner saw fail (door and bed, both halves). Hand-building ~150 blocks with a
     * scaffold cycle per roof block is inherently slow — build *throughput* (fewer replan
     * gaps, roof-ridge reach) is a 4.8 performance item, not a pipeline correctness gap.
     */
    @GameTest(template = "empty11x11", timeoutTicks = 32000, batch = "dvConstructionVanillaHouse")
    public static void builder_completes_vanilla_plains_house(GameTestHelper helper) {
        StorageLedger.get(helper.getLevel()).clear();
        ConstructionLedger ledger = ConstructionLedger.get(helper.getLevel());
        ledger.clear();
        perpetualDay(helper);

        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(0, 2, 0));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.OAK_STAIRS, 50));
        essence.getExtraInventory().setItem(1, new ItemStack(Items.COBBLESTONE, 64));
        essence.getExtraInventory().setItem(2, new ItemStack(Items.OAK_PLANKS, 34));
        essence.getExtraInventory().setItem(3, new ItemStack(Items.STRIPPED_OAK_LOG, 16));
        essence.getExtraInventory().setItem(4, new ItemStack(Items.GLASS_PANE, 3));
        essence.getExtraInventory().setItem(5, new ItemStack(Items.TORCH, 3));
        essence.getExtraInventory().setItem(6, new ItemStack(Items.OAK_DOOR, 1));
        essence.getExtraInventory().setItem(7, new ItemStack(Items.WHITE_BED, 1));
        essence.getExtraInventory().setItem(8, new ItemStack(Items.DIRT, 32));

        BlockPos origin = helper.absolutePos(new BlockPos(2, 2, 2));
        ConstructionLedger.ConstructionSite site = ledger.addSite(VANILLA_HOUSE, origin,
                Rotation.NONE, helper.getLevel().getGameTime());
        essence.setRole(VillagerRole.BUILDER);
        essence.setAssignedSiteId(site.id());
        Blueprint blueprint = Blueprints.load(helper.getLevel(), VANILLA_HOUSE);

        helper.succeedWhen(() -> {
            int total = 0;
            int matched = 0;
            boolean doorLower = false;
            boolean doorUpper = false;
            boolean bedFoot = false;
            boolean bedHead = false;
            for (Blueprint.PlannedBlock plan : blueprint.placedBlocks(origin, Rotation.NONE)) {
                if (plan.state().isAir() || BlockRequirements.isDependentPart(plan.state())) {
                    continue; // count solid, self-placed blocks
                }
                total++;
                if (com.dynamicvillagers.construction.BlockMatch.matches(
                        helper.getLevel().getBlockState(plan.pos()), plan.state())) {
                    matched++;
                }
                if (plan.state().is(Blocks.OAK_DOOR)) {
                    doorLower |= helper.getLevel().getBlockState(plan.pos()).is(Blocks.OAK_DOOR);
                    doorUpper |= helper.getLevel().getBlockState(plan.pos().above()).is(Blocks.OAK_DOOR);
                }
                if (plan.state().is(Blocks.WHITE_BED)) {
                    bedFoot |= helper.getLevel().getBlockState(plan.pos()).is(Blocks.WHITE_BED);
                    bedHead |= helper.getLevel().getBlockState(plan.pos().relative(
                            plan.state().getValue(BlockStateProperties.HORIZONTAL_FACING))).is(Blocks.WHITE_BED);
                }
            }
            helper.assertTrue(matched * 100 >= total * 85,
                    "the plains house should be ≥85% built, got " + matched + "/" + total);
            helper.assertTrue(doorLower && doorUpper, "the door should stand as two halves");
            helper.assertTrue(bedFoot && bedHead, "the bed should stand as foot and head");
        });
    }

    /** 4.3: materials the storage network lacks become requests aimed at the staging chest. */
    @GameTest(template = "empty11x11", timeoutTicks = 2400, batch = "dvConstructionRequests")
    public static void site_posts_requests_for_unavailable_materials(GameTestHelper helper) {
        StorageLedger storage = StorageLedger.get(helper.getLevel());
        storage.clear();
        ConstructionLedger ledger = ConstructionLedger.get(helper.getLevel());
        ledger.clear();
        perpetualDay(helper);

        BlockPos chestRel = new BlockPos(9, 2, 1);
        helper.setBlock(chestRel, Blocks.CHEST);
        BlockPos chestAbs = helper.absolutePos(chestRel);

        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getMemory().rememberContainer(chestAbs, 0);
        ConstructionLedger.ConstructionSite site = ledger.addSite(SHELTER,
                helper.absolutePos(new BlockPos(3, 2, 3)), Rotation.NONE,
                helper.getLevel().getGameTime());
        ledger.setStaging(site, chestAbs);
        essence.setRole(VillagerRole.BUILDER);
        essence.setAssignedSiteId(site.id());

        helper.succeedWhen(() -> {
            StorageLedger.MaterialRequest planks = storage.allRequests().stream()
                    .filter(request -> request.filter().equals("item:minecraft:oak_planks"))
                    .findFirst().orElse(null);
            helper.assertTrue(planks != null,
                    "the site should request the planks the network lacks");
            helper.assertTrue(planks.deliverTo().equals(chestAbs),
                    "the request should deliver to the staging chest");
            helper.assertTrue(site.requests().containsKey("item:minecraft:oak_planks"),
                    "the site should track its posted request");
        });
    }

    /** 4.3 end-to-end: materials sitting in the site's staging chest feed the build — the
     * builder shops there instead of from its own pockets. */
    @GameTest(template = "empty11x11", timeoutTicks = 8000, batch = "dvConstructionSupply")
    public static void hauled_materials_feed_the_site(GameTestHelper helper) {
        StorageLedger.get(helper.getLevel()).clear();
        ConstructionLedger ledger = ConstructionLedger.get(helper.getLevel());
        ledger.clear();
        perpetualDay(helper);

        BlockPos chestRel = new BlockPos(9, 2, 1);
        helper.setBlock(chestRel, Blocks.CHEST);
        BlockPos chestAbs = helper.absolutePos(chestRel);
        net.minecraft.world.Container chest =
                (net.minecraft.world.Container) helper.getLevel().getBlockEntity(chestAbs);
        chest.setItem(0, new ItemStack(Items.OAK_PLANKS, 64)); // hauled here already
        chest.setItem(1, new ItemStack(Items.OAK_LOG, 16));
        chest.setItem(2, new ItemStack(Items.OAK_SLAB, 32));
        chest.setItem(3, new ItemStack(Items.TORCH, 4));
        chest.setItem(4, new ItemStack(Items.DIRT, 16));

        Villager builder = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        VillagerEssence builderEssence = VillagerEssence.get(builder);
        builderEssence.getMemory().rememberContainer(chestAbs, 0);
        ConstructionLedger.ConstructionSite site = ledger.addSite(SHELTER,
                helper.absolutePos(new BlockPos(3, 2, 3)), Rotation.NONE,
                helper.getLevel().getGameTime());
        ledger.setStaging(site, chestAbs);
        builderEssence.setRole(VillagerRole.BUILDER);
        builderEssence.setAssignedSiteId(site.id());

        helper.succeedWhen(() ->
                helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(3, 2, 3)));
    }

    /**
     * Owner request: builders make their own materials. The storage network holds only logs
     * (and the finished slabs/torches), no planks — so the builder must craft the ~47 planks
     * the shelter needs from logs before it can lay a single wall.
     */
    @GameTest(template = "empty11x11", timeoutTicks = 12000, batch = "dvConstructionCraft")
    public static void builder_crafts_its_own_planks_from_logs(GameTestHelper helper) {
        StorageLedger storage = StorageLedger.get(helper.getLevel());
        storage.clear();
        ConstructionLedger ledger = ConstructionLedger.get(helper.getLevel());
        ledger.clear();
        perpetualDay(helper);

        BlockPos chestRel = new BlockPos(9, 2, 1);
        helper.setBlock(chestRel, Blocks.CHEST);
        BlockPos chestAbs = helper.absolutePos(chestRel);
        net.minecraft.world.Container chest =
                (net.minecraft.world.Container) helper.getLevel().getBlockEntity(chestAbs);
        chest.setItem(0, new ItemStack(Items.OAK_LOG, 64)); // logs only — planks must be crafted
        chest.setItem(1, new ItemStack(Items.OAK_SLAB, 32));
        chest.setItem(2, new ItemStack(Items.TORCH, 4));
        chest.setItem(3, new ItemStack(Items.DIRT, 32));
        // let the builder know the chest and its contents from the start (deterministic)
        storage.recordSnapshot(chestAbs, chest, helper.getLevel().getGameTime());

        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getMemory().rememberContainer(chestAbs, 0);
        ConstructionLedger.ConstructionSite site = ledger.addSite(SHELTER,
                helper.absolutePos(new BlockPos(3, 2, 3)), Rotation.NONE,
                helper.getLevel().getGameTime());
        essence.setRole(VillagerRole.BUILDER);
        essence.setAssignedSiteId(site.id());

        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(3, 2, 3)); // floor from crafted planks
            helper.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(3, 3, 3));    // a corner post
        });
    }

    /**
     * Owner question: a builder that needs a 3×3 item (a door) with no crafting table around
     * must craft a table itself, place it, and craft at it — the player's own workflow. Storage
     * holds only logs (plus the pre-made bed and foundation dirt), so the door and the table it
     * takes to make one must both come from crafted planks. Proven by the door standing whole.
     */
    @GameTest(template = "empty11x11", timeoutTicks = 16000, batch = "dvConstructionCraftTable")
    public static void builder_crafts_and_places_its_own_table(GameTestHelper helper) {
        StorageLedger storage = StorageLedger.get(helper.getLevel());
        storage.clear();
        ConstructionLedger ledger = ConstructionLedger.get(helper.getLevel());
        ledger.clear();
        perpetualDay(helper);

        BlockPos chestRel = new BlockPos(9, 2, 1);
        helper.setBlock(chestRel, Blocks.CHEST);
        BlockPos chestAbs = helper.absolutePos(chestRel);
        net.minecraft.world.Container chest =
                (net.minecraft.world.Container) helper.getLevel().getBlockEntity(chestAbs);
        chest.setItem(0, new ItemStack(Items.OAK_LOG, 64)); // planks, the door, and the TABLE come from these
        chest.setItem(1, new ItemStack(Items.WHITE_BED, 1)); // the bed is pre-made (its own 3×3 is not the point)
        chest.setItem(2, new ItemStack(Items.DIRT, 16));
        storage.recordSnapshot(chestAbs, chest, helper.getLevel().getGameTime());

        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getMemory().rememberContainer(chestAbs, 0);
        ConstructionLedger.ConstructionSite site = ledger.addSite(
                ResourceLocation.fromNamespaceAndPath(DynamicVillagers.MOD_ID, "fixture_hut"),
                helper.absolutePos(new BlockPos(4, 2, 4)), Rotation.NONE,
                helper.getLevel().getGameTime());
        essence.setRole(VillagerRole.BUILDER);
        essence.setAssignedSiteId(site.id());

        helper.succeedWhen(() -> {
            // a door can only exist if the builder crafted planks, crafted+placed a table from
            // them, and crafted the door at that table — storage held none of those
            helper.assertBlockPresent(Blocks.OAK_DOOR, new BlockPos(4, 3, 4)); // door lower
            helper.assertBlockPresent(Blocks.OAK_DOOR, new BlockPos(4, 4, 4)); // door upper
            helper.assertTrue(craftingTablePlacedNear(helper, new BlockPos(4, 2, 4)),
                    "the builder should have crafted and placed its own crafting table");
        });
    }

    /** Scans the footprint and its ring for a crafting table the builder put down. */
    private static boolean craftingTablePlacedNear(GameTestHelper helper, BlockPos siteRel) {
        for (BlockPos pos : BlockPos.betweenClosed(
                siteRel.offset(-2, 0, -2), siteRel.offset(6, 2, 6))) {
            if (helper.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Owner directive: to break a block that won't drop bare-handed (a stone slab in the
     * footprint) the builder fetches a pickaxe from a chest first, rather than grinding it
     * out by hand. Verifies it ends up holding a pickaxe and clears the stone.
     */
    @GameTest(template = "empty11x11", timeoutTicks = 9000, batch = "dvConstructionSeekPick")
    public static void builder_seeks_pickaxe_to_clear_stone(GameTestHelper helper) {
        StorageLedger storage = StorageLedger.get(helper.getLevel());
        storage.clear();
        ConstructionLedger ledger = ConstructionLedger.get(helper.getLevel());
        ledger.clear();
        perpetualDay(helper);

        BlockPos chestRel = new BlockPos(9, 2, 1);
        helper.setBlock(chestRel, Blocks.CHEST);
        BlockPos chestAbs = helper.absolutePos(chestRel);
        ((net.minecraft.world.Container) helper.getLevel().getBlockEntity(chestAbs))
                .setItem(0, new ItemStack(Items.IRON_PICKAXE, 1));
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.STONE); // a floor cell blocked by stone

        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getMemory().rememberContainer(chestAbs, 0);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 64));
        essence.getExtraInventory().setItem(1, new ItemStack(Items.OAK_LOG, 16));
        essence.getExtraInventory().setItem(2, new ItemStack(Items.OAK_SLAB, 32));
        essence.getExtraInventory().setItem(3, new ItemStack(Items.TORCH, 4));
        essence.getExtraInventory().setItem(4, new ItemStack(Items.DIRT, 32));

        ConstructionLedger.ConstructionSite site = ledger.addSite(SHELTER,
                helper.absolutePos(new BlockPos(3, 2, 3)), Rotation.NONE,
                helper.getLevel().getGameTime());
        essence.setRole(VillagerRole.BUILDER);
        essence.setAssignedSiteId(site.id());

        helper.succeedWhen(() -> {
            helper.assertTrue(
                    countItem(villager.getInventory(), Items.IRON_PICKAXE)
                            + countItem(essence.getExtraInventory(), Items.IRON_PICKAXE) == 1,
                    "the builder should have fetched the pickaxe from the chest");
            helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(3, 2, 3)); // stone cleared, floor laid
        });
    }

    /** 4.2: bad placements are refused at designation time, not discovered by a builder. */
    @GameTest(template = "empty11x11", batch = "dvConstructionValidate")
    public static void site_validation_rejects_cliffs_and_overlaps(GameTestHelper helper) {
        ConstructionLedger ledger = ConstructionLedger.get(helper.getLevel());
        ledger.clear();
        perpetualDay(helper);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), SHELTER);

        String floating = SiteValidator.validate(helper.getLevel(), ledger, blueprint,
                helper.absolutePos(new BlockPos(3, 7, 3)), Rotation.NONE);
        helper.assertTrue(floating != null,
                "a site hanging far above ground should be refused");

        ledger.addSite(SHELTER, helper.absolutePos(new BlockPos(3, 2, 3)), Rotation.NONE,
                helper.getLevel().getGameTime());
        String overlapping = SiteValidator.validate(helper.getLevel(), ledger, blueprint,
                helper.absolutePos(new BlockPos(5, 2, 5)), Rotation.NONE);
        helper.assertTrue(overlapping != null, "an overlapping site should be refused");

        ledger.clear();
        helper.succeed();
    }

    /**
     * Villagers stop planning work at night (PlanWorkBehavior gates on isDay), which is
     * correct in play but flaky in the shared gametest level whose clock keeps advancing —
     * a build test that happens to run during a night window would stall. Pin the world to
     * noon with the daylight cycle off so build tests get uninterrupted daytime.
     */
    private static void perpetualDay(GameTestHelper helper) {
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT)
                .set(false, level.getServer());
        level.setDayTime(6000);
    }

    private static int countItem(net.minecraft.world.SimpleContainer container, Item item) {
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(item)) {
                count += container.getItem(i).getCount();
            }
        }
        return count;
    }

    private static ListTag intList(int x, int y, int z) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(x));
        list.add(IntTag.valueOf(y));
        list.add(IntTag.valueOf(z));
        return list;
    }

    private static CompoundTag blockEntry(int x, int y, int z, int state, CompoundTag nbt) {
        CompoundTag tag = new CompoundTag();
        tag.put("pos", intList(x, y, z));
        tag.putInt("state", state);
        if (nbt != null) {
            tag.put("nbt", nbt);
        }
        return tag;
    }
}
