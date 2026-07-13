package com.dynamicvillagers.village;

import com.dynamicvillagers.VillageConfig;
import com.dynamicvillagers.construction.Blueprint;
import com.dynamicvillagers.construction.Blueprints;
import com.dynamicvillagers.construction.BuildingCatalog;
import com.dynamicvillagers.construction.SiteValidator;
import com.dynamicvillagers.registry.DVTags;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.role.VillagerRole;
import com.dynamicvillagers.villager.work.ItemFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.util.SpawnUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class VillageManager extends SavedData {
    private record Membership(int villageId, boolean child) {
    }
    public static final String DATA_NAME = "dynamicvillagers_villages";
    private static final int DISCOVERY_INTERVAL = 40;
    private static final int DECISION_INTERVAL = 20;
    private static final int STORAGE_SCAN_RADIUS = 8;
    private static final int MAX_OPEN_SITES = 2;
    private static final int MAX_SPOT_CHECKS = 128;
    private static final int INITIAL_POPULATION = 25;
    private static final int INITIAL_SPAWNS_PER_TICK = 4;
    private static final ResourceLocation GUARD_TYPE = ResourceLocation.fromNamespaceAndPath("guardvillagers", "guard");
    private static final SavedData.Factory<VillageManager> FACTORY =
            new SavedData.Factory<>(VillageManager::new, VillageManager::load, null);

    private final Map<Integer, Village> villages = new HashMap<>();
    private final Map<UUID, Membership> memberships = new HashMap<>();
    private int nextVillageId = 1;
    private int cursor;

    public static VillageManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public Collection<Village> all() {
        return Collections.unmodifiableCollection(villages.values());
    }

    @Nullable
    public Village getVillage(int id) {
        return villages.get(id);
    }

    @Nullable
    public Village villageFor(UUID villager) {
        Membership membership = memberships.get(villager);
        return membership == null ? null : villages.get(membership.villageId());
    }

    @Nullable
    public Village villageAt(BlockPos pos) {
        Village best = null;
        double distance = Double.MAX_VALUE;
        for (Village village : villages.values()) {
            double candidate = village.center().distSqr(pos);
            if (candidate <= (double) village.radius() * village.radius() && candidate < distance) {
                best = village;
                distance = candidate;
            }
        }
        return best;
    }

    @Nullable
    public Village nearestVillage(BlockPos pos, int range) {
        Village best = null;
        double distance = (double) range * range;
        for (Village village : villages.values()) {
            double candidate = village.center().distSqr(pos);
            if (candidate <= distance) {
                best = village;
                distance = candidate;
            }
        }
        return best;
    }

    public void tick(ServerLevel level) {
        long now = level.getGameTime();
        removeBelllessVillages(level);
        if (now % DISCOVERY_INTERVAL == 0) {
            discoverAndReconcile(level);
        }
        if (now % DECISION_INTERVAL != 0 || villages.isEmpty()) {
            return;
        }
        List<Village> ordered = new ArrayList<>(villages.values());
        ordered.sort(java.util.Comparator.comparingInt(Village::id));
        Village village = ordered.get(Math.floorMod(cursor++, ordered.size()));
        if (level.hasChunkAt(village.center())) {
            refreshTallies(level, village);
            if (!village.initialPopulationSeeded() && isNaturalVillage(level, village)
                    && seedInitialPopulation(level, village)) {
                refreshTallies(level, village);
            }
            maintainStorage(level, village);
            alertMinersToStoredWood(level, village);
            spreadIdleVillagers(level, village);
            if (village.autoStaff() && staffOne(level, village)) {
                refreshTallies(level, village);
                return;
            }
            postNeededSite(level, village);
        }
    }

    public Village create(ServerLevel level, BlockPos center) {
        Village existing = villageCenteredAt(center);
        if (existing != null) {
            return existing;
        }
        Village village = new Village(nextVillageId++, Names.village(level, center), center,
                Village.DEFAULT_RADIUS, level.getGameTime(), VillageConfig.AUTO_STAFF.get());
        villages.put(village.id(), village);
        setDirty();
        return village;
    }

    public void adopt(ServerLevel level, Villager villager, Village village) {
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.setHomeVillageId(village.id());
        memberships.put(villager.getUUID(), new Membership(village.id(), villager.isBaby()));
        if (essence.getGeneratedName() == null) {
            String name = Names.villager(villager.getUUID());
            essence.setGeneratedName(name);
            if (VillageConfig.SHOW_NAMEPLATES.get()) {
                villager.setCustomName(net.minecraft.network.chat.Component.literal(name));
                villager.setCustomNameVisible(true);
            }
        }
        setDirty();
    }

    public void removeMember(UUID villager) {
        Membership removed = memberships.remove(villager);
        if (removed != null) {
            setDirty();
        }
    }

    public void setAutoStaff(Village village, boolean enabled) {
        village.setAutoStaff(enabled);
        setDirty();
    }

    public void refreshTallies(ServerLevel level, Village village) {
        int adults = 0;
        int children = 0;
        int guards = 0;
        EnumMap<VillagerRole, Integer> roles = new EnumMap<>(VillagerRole.class);
        for (Entity entity : level.getAllEntities()) {
            if (isGuard(entity) && entity.blockPosition().closerThan(village.center(), village.radius())) {
                guards++;
            }
            if (!(entity instanceof Villager villager)) continue;
            if (VillagerEssence.get(villager).getHomeVillageId() != village.id()) continue;
            memberships.put(villager.getUUID(), new Membership(village.id(), villager.isBaby()));
            roles.merge(VillagerEssence.get(villager).getRole(), 1, Integer::sum);
        }
        for (Membership membership : memberships.values()) {
            if (membership.villageId() != village.id()) continue;
            if (membership.child()) children++; else adults++;
        }
        PoiManager pois = level.getPoiManager();
        int beds = (int) pois.getInRange(holder -> holder.is(PoiTypes.HOME), village.center(),
                village.radius(), PoiManager.Occupancy.ANY)
                .filter(record -> isBedHead(level, record.getPos())).count();
        int freeBeds = (int) pois.getInRange(holder -> holder.is(PoiTypes.HOME), village.center(),
                village.radius(), PoiManager.Occupancy.HAS_SPACE)
                .filter(record -> isBedHead(level, record.getPos())).count();
        int houses = (int) ConstructionLedger.get(level).allSites().stream()
                .filter(site -> site.villageId() == village.id())
                .filter(site -> site.type() == ConstructionLedger.SiteType.HOUSE)
                .filter(site -> site.status() == ConstructionLedger.Status.DONE).count();
        int openSites = (int) ConstructionLedger.get(level).allSites().stream()
                .filter(site -> site.villageId() == village.id())
                .filter(site -> site.status() == ConstructionLedger.Status.OPEN).count();
        village.setTallies(adults, children, beds, freeBeds, houses, openSites, guards, roles);
    }

    private void maintainStorage(ServerLevel level, Village village) {
        StorageLedger storage = StorageLedger.get(level);
        for (Map.Entry<BlockPos, StorageLedger.ContainerRecord> entry
                : storage.recordsNear(village.center(), village.radius())) {
            if (entry.getValue().villageId() == -1) storage.setVillageId(entry.getKey(), village.id());
        }
        BlockPos center = village.center();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-STORAGE_SCAN_RADIUS, -4, -STORAGE_SCAN_RADIUS),
                center.offset(STORAGE_SCAN_RADIUS, 4, STORAGE_SCAN_RADIUS))) {
            if (!level.getBlockState(pos).is(DVTags.STORAGE_CONTAINERS)) continue;
            StorageLedger.ContainerRecord record = storage.getRecord(pos);
            if (record == null || record.designation() == StorageLedger.Designation.UNCLAIMED) {
                storage.setDesignation(pos, StorageLedger.Designation.PUBLIC, null, village.id());
            } else if (record.villageId() == -1) {
                storage.setVillageId(pos, village.id());
            }
        }
    }

    private static void alertMinersToStoredWood(ServerLevel level, Village village) {
        boolean woodAvailable = StorageLedger.get(level).recordsNear(village.center(), village.radius()).stream()
                .filter(entry -> entry.getValue().villageId() == village.id())
                .filter(entry -> entry.getValue().designation() == StorageLedger.Designation.PUBLIC)
                .anyMatch(entry -> entry.getValue().count(stack -> stack.is(ItemTags.LOGS)
                        || stack.is(ItemTags.PLANKS) || stack.is(Items.STICK)) > 0);
        if (!woodAvailable) return;
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof Villager villager)) continue;
            VillagerEssence essence = VillagerEssence.get(villager);
            if (essence.getHomeVillageId() == village.id()
                    && essence.getRole() == VillagerRole.MINER
                    && essence.getTaskQueue().isEmpty()
                    && !essence.hasItem(villager, ItemFilter.parse("pickaxe"))) {
                essence.setNextPlanTime(0);
            }
        }
    }

    private static void spreadIdleVillagers(ServerLevel level, Village village) {
        List<BlockPos> beds = level.getPoiManager().getInRange(holder -> holder.is(PoiTypes.HOME),
                        village.center(), village.radius(), PoiManager.Occupancy.ANY)
                .map(record -> record.getPos().immutable())
                .filter(pos -> isBedHead(level, pos))
                .sorted(java.util.Comparator.comparingLong(BlockPos::asLong))
                .toList();
        if (beds.size() < 2) return;
        Map<BlockPos, Integer> load = new HashMap<>();
        for (BlockPos bed : beds) load.put(bed, 0);
        List<Villager> idleVillagers = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof Villager villager)) continue;
            VillagerEssence essence = VillagerEssence.get(villager);
            if (essence.getHomeVillageId() != village.id()) continue;
            GlobalPos home = villager.getBrain().getMemory(MemoryModuleType.HOME).orElse(null);
            if (home != null && load.containsKey(home.pos())) {
                load.merge(home.pos(), 1, Integer::sum);
            }
            if (essence.getRole() == VillagerRole.NONE
                    && villager.getVillagerData().getProfession() == VillagerProfession.NONE
                    && essence.getTaskQueue().isEmpty()
                    && !villager.isSleeping()) {
                idleVillagers.add(villager);
            }
        }
        idleVillagers.sort(java.util.Comparator.comparing(Villager::getUUID));
        for (Villager villager : idleVillagers) {
            GlobalPos home = villager.getBrain().getMemory(MemoryModuleType.HOME).orElse(null);
            BlockPos shelter = home != null && load.containsKey(home.pos()) ? home.pos() : beds.stream()
                    .min(java.util.Comparator.comparingInt((BlockPos pos) -> load.getOrDefault(pos, 0))
                            .thenComparingDouble(pos -> pos.distSqr(villager.blockPosition())))
                    .orElse(null);
            if (shelter != null) {
                if (home == null || !shelter.equals(home.pos())) load.merge(shelter, 1, Integer::sum);
                if (!shelter.closerThan(villager.blockPosition(), 2.0)) {
                    villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                            new WalkTarget(shelter, 0.5F, 1));
                }
            }
        }
    }

    private boolean staffOne(ServerLevel level, Village village) {
        ConstructionLedger construction = ConstructionLedger.get(level);
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof Villager villager) || villager.isBaby()) continue;
            VillagerEssence essence = VillagerEssence.get(villager);
            if (essence.getHomeVillageId() != village.id() || !essence.isManagerManagedRole()
                    || essence.getAssignedSiteId() == -1) continue;
            ConstructionLedger.ConstructionSite site = construction.getSite(essence.getAssignedSiteId());
            if (site == null || site.status() == ConstructionLedger.Status.DONE) {
                essence.setAssignedSiteId(-1);
                essence.setRole(VillagerRole.NONE);
                essence.setManagerManagedRole(false);
                return true;
            }
        }
        ConstructionLedger.ConstructionSite openSite = construction.allSites().stream()
                .filter(site -> site.villageId() == village.id())
                .filter(site -> site.status() == ConstructionLedger.Status.OPEN)
                .filter(site -> !hasAssignedBuilder(level, site.id()))
                .findFirst().orElse(null);
        VillagerRole needed = openSite != null ? VillagerRole.BUILDER : neededGatherer(village);
        if (needed == VillagerRole.NONE) return false;
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof Villager villager) || villager.isBaby()) continue;
            VillagerEssence essence = VillagerEssence.get(villager);
            if (essence.getHomeVillageId() != village.id() || essence.getRole() != VillagerRole.NONE
                    || villager.getVillagerData().getProfession() != VillagerProfession.NONE
                    || essence.hasBuildAssignment()) continue;
            essence.setRole(needed);
            essence.setManagerManagedRole(true);
            if (openSite != null) essence.setAssignedSiteId(openSite.id());
            if (needed == VillagerRole.MINER && essence.getQuarrySite() == null) {
                essence.setQuarrySite(starterQuarry(level, village));
            }
            return true;
        }
        return false;
    }

    private static VillagerRole neededGatherer(Village village) {
        int lumberjacks = Math.max(2, (village.adults() + 4) / 5);
        if (village.roles().getOrDefault(VillagerRole.LUMBERJACK, 0) < lumberjacks) {
            return VillagerRole.LUMBERJACK;
        }
        if (village.roles().getOrDefault(VillagerRole.FARMER, 0) == 0) return VillagerRole.FARMER;
        int miners = Math.max(1, (village.adults() + 14) / 15);
        if (village.roles().getOrDefault(VillagerRole.MINER, 0) < miners) {
            return VillagerRole.MINER;
        }
        if (village.population() >= 8 && village.roles().getOrDefault(VillagerRole.HUNTER, 0) == 0) {
            return VillagerRole.HUNTER;
        }
        return VillagerRole.NONE;
    }

    @Nullable
    private static VillagerEssence.QuarrySite starterQuarry(ServerLevel level, Village village) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos edge = village.center().relative(direction, 24);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, edge.getX(), edge.getZ()) - 1;
            BlockPos top = new BlockPos(edge.getX(), y, edge.getZ());
            if (level.getFluidState(top).isEmpty() && level.getFluidState(top.above()).isEmpty()) {
                return new VillagerEssence.QuarrySite(top, top.offset(3, -3, 3));
            }
        }
        return null;
    }

    public boolean seedInitialPopulation(ServerLevel level, Village village) {
        if (village.initialPopulationSeeded()) return false;
        int needed = INITIAL_POPULATION - village.population();
        for (int i = 0; i < Math.min(needed, INITIAL_SPAWNS_PER_TICK); i++) {
            Villager villager = SpawnUtil.trySpawnMob(EntityType.VILLAGER, MobSpawnType.STRUCTURE,
                    level, village.center(), 16, 12, 8, SpawnUtil.Strategy.ON_TOP_OF_COLLIDER).orElse(null);
            if (villager == null) break;
            adopt(level, villager, village);
        }
        refreshTallies(level, village);
        if (village.population() >= INITIAL_POPULATION) {
            village.setInitialPopulationSeeded(true);
            setDirty();
        }
        return needed > 0;
    }

    private static boolean isNaturalVillage(ServerLevel level, Village village) {
        return level.structureManager().getStructureWithPieceAt(village.center(), StructureTags.VILLAGE).isValid();
    }

    public static boolean isGuard(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).equals(GUARD_TYPE);
    }

    private static boolean hasAssignedBuilder(ServerLevel level, int siteId) {
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Villager villager
                    && VillagerEssence.get(villager).getAssignedSiteId() == siteId) return true;
        }
        return false;
    }

    private void postNeededSite(ServerLevel level, Village village) {
        if (village.openSites() >= MAX_OPEN_SITES) return;
        ConstructionLedger.SiteType need = buildingNeed(level, village);
        if (need == null) return;
        ConstructionLedger construction = ConstructionLedger.get(level);
        boolean alreadyOpen = construction.allSites().stream()
                .anyMatch(site -> site.villageId() == village.id() && site.type() == need
                        && site.status() == ConstructionLedger.Status.OPEN);
        if (alreadyOpen) return;
        List<BuildingCatalog.Entry> choices = BuildingCatalog.INSTANCE.entries(Names.biomeGroup(level, village.center()), need);
        for (BuildingCatalog.Entry entry : choices) {
            Blueprint blueprint = Blueprints.load(level, entry.templateId());
            BlockPos origin = blueprint == null ? null : findSpot(level, village, construction, blueprint);
            if (origin != null) {
                construction.addSite(entry.templateId(), origin, rotationToward(origin, village.center()),
                        level.getGameTime(), village.id(), need);
                return;
            }
        }
    }

    @Nullable
    private static ConstructionLedger.SiteType buildingNeed(ServerLevel level, Village village) {
        if (village.population() >= village.beds()) return ConstructionLedger.SiteType.HOUSE;
        if (village.roles().getOrDefault(VillagerRole.FARMER, 0) == 0
                || knownFood(level, village) < Math.max(24, village.population() * 6)) {
            return ConstructionLedger.SiteType.FARM;
        }
        List<Map.Entry<BlockPos, StorageLedger.ContainerRecord>> publicStorage = StorageLedger.get(level)
                .recordsNear(village.center(), village.radius()).stream()
                .filter(entry -> entry.getValue().villageId() == village.id())
                .filter(entry -> entry.getValue().designation() == StorageLedger.Designation.PUBLIC).toList();
        if (publicStorage.isEmpty() || publicStorage.stream().allMatch(entry -> entry.getValue().freeSlots() == 0)) {
            return ConstructionLedger.SiteType.WAREHOUSE;
        }
        return null;
    }

    private static int knownFood(ServerLevel level, Village village) {
        int count = 0;
        for (Map.Entry<BlockPos, StorageLedger.ContainerRecord> entry
                : StorageLedger.get(level).recordsNear(village.center(), village.radius())) {
            if (entry.getValue().villageId() == village.id()) {
                count += entry.getValue().count(stack -> stack.has(net.minecraft.core.component.DataComponents.FOOD));
            }
        }
        return count;
    }

    @Nullable
    private static BlockPos findSpot(ServerLevel level, Village village, ConstructionLedger ledger,
                                     Blueprint blueprint) {
        int checked = 0;
        int width = blueprint.size().getX();
        int depth = blueprint.size().getZ();
        int firstRadius = 16;
        for (int radius = firstRadius; radius <= village.radius() - Math.max(width, depth); radius += 4) {
            for (int dx = -radius; dx <= radius; dx += 4) {
                for (int dz : new int[]{-radius, radius}) {
                    BlockPos spot = candidate(level, village, blueprint, dx, dz);
                    if (spot != null && SiteValidator.validate(level, ledger, blueprint, spot,
                            rotationToward(spot, village.center())) == null) return spot;
                    if (++checked >= MAX_SPOT_CHECKS) return null;
                }
            }
            for (int dz = -radius + 4; dz < radius; dz += 4) {
                for (int dx : new int[]{-radius, radius}) {
                    BlockPos spot = candidate(level, village, blueprint, dx, dz);
                    if (spot != null && SiteValidator.validate(level, ledger, blueprint, spot,
                            rotationToward(spot, village.center())) == null) return spot;
                    if (++checked >= MAX_SPOT_CHECKS) return null;
                }
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos candidate(ServerLevel level, Village village, Blueprint blueprint, int dx, int dz) {
        BlockPos base = village.center().offset(dx, 0, dz);
        Rotation rotation = rotationToward(base, village.center());
        int width = blueprint.size(rotation).getX();
        int depth = blueprint.size(rotation).getZ();
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base.getX() + x, base.getZ() + z);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                BlockPos surface = new BlockPos(base.getX() + x, y - 1, base.getZ() + z);
                if (!level.getFluidState(surface).isEmpty()) return null;
            }
        }
        if (maxY - minY > 1) return null;
        BlockPos origin = new BlockPos(base.getX(), maxY, base.getZ());
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < blueprint.size(rotation).getY(); y++) {
                for (int z = 0; z < depth; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (!level.getBlockState(pos).isAir() && !level.getBlockState(pos).canBeReplaced()) return null;
                }
            }
        }
        return origin;
    }

    private static Rotation rotationToward(BlockPos origin, BlockPos center) {
        int dx = center.getX() - origin.getX();
        int dz = center.getZ() - origin.getZ();
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90;
        return dz > 0 ? Rotation.NONE : Rotation.CLOCKWISE_180;
    }

    public void clear() {
        villages.clear();
        memberships.clear();
        nextVillageId = 1;
        cursor = 0;
        setDirty();
    }

    private void discoverAndReconcile(ServerLevel level) {
        List<Villager> loaded = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Villager villager && villager.isAlive()) loaded.add(villager);
        }
        for (Villager villager : loaded) {
            VillagerEssence essence = VillagerEssence.get(villager);
            Village home = villages.get(essence.getHomeVillageId());
            if (home != null) {
                memberships.put(villager.getUUID(), new Membership(home.id(), villager.isBaby()));
                ensureName(villager, essence);
                continue;
            }
            essence.setHomeVillageId(-1);
            BlockPos center = level.getPoiManager().findClosest(holder -> holder.is(PoiTypes.MEETING),
                    villager.blockPosition(), Village.DEFAULT_RADIUS, PoiManager.Occupancy.ANY).orElse(null);
            if (center != null) adopt(level, villager, create(level, center));
        }
    }

    private void removeBelllessVillages(ServerLevel level) {
        List<Village> removed = villages.values().stream()
                .filter(village -> !level.getBlockState(village.center()).is(net.minecraft.world.level.block.Blocks.BELL))
                .toList();
        for (Village village : removed) {
            villages.remove(village.id());
            memberships.entrySet().removeIf(entry -> entry.getValue().villageId() == village.id());
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Villager villager) {
                    VillagerEssence essence = VillagerEssence.get(villager);
                    if (essence.getHomeVillageId() == village.id()) essence.setHomeVillageId(-1);
                }
            }
            ConstructionLedger construction = ConstructionLedger.get(level);
            for (ConstructionLedger.ConstructionSite site : List.copyOf(construction.allSites())) {
                if (site.villageId() == village.id()) construction.cancelSite(site.id());
            }
            StorageLedger.get(level).removeVillageRecords(village.id());
            setDirty();
        }
    }

    private static boolean isBedHead(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof BedBlock
                && level.getBlockState(pos).getValue(BedBlock.PART) == BedPart.HEAD;
    }

    private static void ensureName(Villager villager, VillagerEssence essence) {
        if (essence.getGeneratedName() == null) essence.setGeneratedName(Names.villager(villager.getUUID()));
        if (VillageConfig.SHOW_NAMEPLATES.get() && !villager.hasCustomName()) {
            villager.setCustomName(net.minecraft.network.chat.Component.literal(essence.getGeneratedName()));
            villager.setCustomNameVisible(true);
        } else if (!VillageConfig.SHOW_NAMEPLATES.get() && villager.hasCustomName()
                && villager.getCustomName().getString().equals(essence.getGeneratedName())) {
            villager.setCustomName(null);
        }
    }

    @Nullable
    private Village villageCenteredAt(BlockPos center) {
        for (Village village : villages.values()) {
            if (village.center().equals(center)) return village;
        }
        return null;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag villageList = new ListTag();
        for (Village village : villages.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("id", village.id());
            entry.putString("name", village.name());
            entry.putLong("center", village.center().asLong());
            entry.putInt("radius", village.radius());
            entry.putLong("created", village.created());
            entry.putBoolean("auto_staff", village.autoStaff());
            entry.putBoolean("initial_population_seeded", village.initialPopulationSeeded());
            villageList.add(entry);
        }
        tag.put("villages", villageList);
        ListTag memberList = new ListTag();
        for (Map.Entry<UUID, Membership> membership : memberships.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("villager", membership.getKey());
            entry.putInt("village", membership.getValue().villageId());
            entry.putBoolean("child", membership.getValue().child());
            memberList.add(entry);
        }
        tag.put("memberships", memberList);
        tag.putInt("next_village_id", nextVillageId);
        return tag;
    }

    private static VillageManager load(CompoundTag tag, HolderLookup.Provider provider) {
        VillageManager manager = new VillageManager();
        for (Tag value : tag.getList("villages", Tag.TAG_COMPOUND)) {
            if (!(value instanceof CompoundTag entry)) continue;
            Village village = new Village(entry.getInt("id"), entry.getString("name"),
                    BlockPos.of(entry.getLong("center")), entry.getInt("radius"),
                    entry.getLong("created"), !entry.contains("auto_staff") || entry.getBoolean("auto_staff"));
            village.setInitialPopulationSeeded(entry.getBoolean("initial_population_seeded"));
            manager.villages.put(village.id(), village);
        }
        for (Tag value : tag.getList("memberships", Tag.TAG_COMPOUND)) {
            if (value instanceof CompoundTag entry && entry.hasUUID("villager")) {
                manager.memberships.put(entry.getUUID("villager"),
                        new Membership(entry.getInt("village"), entry.getBoolean("child")));
            }
        }
        manager.nextVillageId = Math.max(1, tag.getInt("next_village_id"));
        return manager;
    }
}
