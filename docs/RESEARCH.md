# Research Notes

Collected 2026-07-08 from the companion mods' source (1.21.1 branches), NeoForge docs, and
vanilla (mojmap) internals. Source clones studied locally; none of their code is copied here.

## Target platform

| Component | Version |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.235 (latest 21.1.x at time of writing) |
| Java | 21 (Temurin) |
| Gradle plugin | ModDevGradle 2.0.141 (official MDK template) |
| Mappings | Mojang official + Parchment 1.21/2024.11.10 |

All three companion mods ship NeoForge 1.21.1 builds. For dev-time dependencies use
Modrinth Maven (`https://api.modrinth.com/maven`, coordinates `maven.modrinth:<slug>:<version>`)
or CurseMaven.

## Companion mod facts

### Thief (`thief` v1.2.3, GPL-3.0, by mortuusars)

Multiloader layout (`common/` + `neoforge/`). The source of truth for theft detection.

- **Crime model**: `Crime` enum — `LIGHT`, `MEDIUM`, `HEAVY` — implements vanilla
  `ReputationEventType`. Crimes are detected from **block tags**
  (`thief:break_protected_{light,medium,heavy}`, `thief:interact_protected_*`) and
  **entity-type tags** (`thief:killing_protected_*`). We can add our own blocks/cases by tag.
- **Witnesses**: `Witness.getWitnesses(criminal)` — line-of-sight + stealth model (darkness,
  sneaking, invisibility modifiers). No witnesses → no crime.
- **Reputation**: entirely built on the **vanilla gossip system**
  (`villager.getGossips().getReputation(uuid)`), bucketed into 7 named levels
  (HATED … HONORED). High reputation lets entities ignore lesser crimes.
- **NeoForge bus events we consume** (in `io.github.mortuusars.thief.neoforge.api.event`):
  - `CrimeCommitedEvent(criminal, crime, witnesses)` — fired after a punished crime
  - `GiftGivenEvent`, `ReputationLevelChangedEvent`
- **`WitnessReaction.register(handler)`** — pluggable witness reactions. Caveat: the built-in
  villager handler is registered first and *consumes* villager witnesses (returns true), so
  custom handlers only see non-villager witnesses. For village-level consequences, prefer the
  bus events.
- **Guard hook**: entity tag `thief:guards` (contains `minecraft:iron_golem` and, optionally,
  `guardvillagers:guard`). Any `NeutralMob` in that tag auto-targets criminals when the crime
  exceeds the configured threshold. Guard Villagers integration therefore already exists.
- License caution: GPL-3.0. Integrate at runtime via its published events (`compileOnly`
  dependency); never copy code into this MIT repo.

### Guard Villagers (`guardvillagers` v2.4.11, MIT code / ARR assets, by TallestEgg)

Study branch: `1.21.1` (the `main` branch targets newer MC). Single-project NeoGradle build,
neo 21.1.215.

- `Guard` is a **Goal-based** `PathfinderMob` (goal selector AI, not Brain), a `NeutralMob`,
  with its own `GossipContainer`, 8-ish slot `GuardContainer` inventory, patrol position,
  follow mode, equipment slots players can edit.
- Registered as `guardvillagers:guard` via `DeferredRegister` (`GuardEntityType.GUARD`).
- Villagers can be converted to guards (interaction with sword); guards use crossbow/melee AI.
- **`VillagerGoalPackagesMixin`**: mixin into vanilla
  `VillagerGoalPackages.getCorePackage/getMeetPackage/getIdlePackage` `@At("RETURN")` to append
  extra Brain behaviors (e.g. `RepairGolem`, `ShareGossipWithGuard`) to every villager.
  **Interop warning (verified empirically 2026-07-09)**: GV's handler is a *cancellable*
  `@Inject` that unconditionally calls `setReturnValue`. A cancel at a RETURN point jumps to a
  synthetic return that other handlers at the same point never intercept — so any mod whose
  mixin lands after GV's (mod load order) silently loses its additions there, whether it uses
  `@Inject` or `@ModifyReturnValue`. **Do not add behaviors via `VillagerGoalPackages` when
  GV is present.** Our solution: `@Inject(at = @At("TAIL"))` into `Villager.registerBrainGoals`
  and call `Brain.addActivity(Activity.CORE, priority, behaviors)` — appends without touching
  the contested factory methods, runs on every brain rebuild, preserves everyone's behaviors.
- Uses NeoForge **data attachments** (`GuardDataAttachments`) for extra per-entity state.

### Villager Overhaul (`villageroverhaul` v3.10.x, custom source-available license, by z2six)

> **Removed as a companion mod 2026-07-09 (owner decision).** License forbids code reuse, no
> integration API, and its rendering mixins crash dev-environment clients. Section kept for
> reference; the coexistence facts below are no longer maintained.

NeoForge-only in practice (1.21.1+). **Much larger than its store description**: ~400 source
files. Already implements (player-directed): villager inventory + inventory screen, recruiting,
combat modes with equipment, patrol routes, manual farming with registered workstation/deposit
chests, storage-deposit goal, teachable "custom commands", family trees, stats/XP, naming.

- **Key technique — brain gating**: `VillagerBrainGateMixin` uses MixinExtras
  `@WrapWithCondition` on `Brain.tick` inside `Villager.customServerAiStep` to suspend the
  vanilla Brain while VO's Goal-based AI drives the villager
  (`VillagerBrain.shouldTickVanillaBrain`).
- **Critical for coexistence**: the gate returns `true` (vanilla brain runs) unless the villager
  **is recruited by a player** (`RecruitService.isRecruited`). VO's deep control is opt-in
  per villager. Unrecruited villagers are pure vanilla.
- VO installs its goals on `villager.goalSelector` (priorities 0–10) — vanilla villagers have a
  goal selector that vanilla itself barely uses, so mods can use it.
- License: source-available, learn-only for us. **Do not copy code** (attribution + ARR assets).
  No useful public API for our purposes (its `api` package is render-related).
- **Runtime dependencies of VO itself**: requires `ezactions` (Modrinth slug `ez-actions`,
  also by Z2SIX) `[2.0.1.7,)` on the **client side only** — a headless server/gametest run
  loads VO fine without it, but a client refuses to start. Optional: `ezemeraldpouch`.
  Any instance running VO needs EzActions installed alongside it.
- **VO is incompatible with dev-environment clients** (as of 3.10.17.16): its
  `villagerRendering.VillagerModelVisibilityMixin` throws `InvalidInjectionException`
  ("Invalid descriptor") against the recompiled Minecraft classes ModDevGradle uses, crashing
  `runClient` on launch. Production instances are unaffected. Upstream bug — worth reporting
  to z2six. Our dev layout therefore: client runs (working dir `run/`) load GV + Thief via
  `localRuntime`; server-type runs (working dir `run/server/`) additionally load VO from
  `run/server/mods`, synced by the `syncServerCoexistenceMods` Gradle task. Note that dev-run
  mod jars must be *discovered* by FML — jars added to a run's JVM classpath via
  `<run>AdditionalRuntimeClasspath` are NOT picked up as mods (verified: mod listed as
  MISSING); use `localRuntime` (all runs) or a run-dir `mods/` folder (per-run).

### Coexistence rules derived from the above

1. **Dynamic Villagers drives unrecruited villagers; VO drives recruited ones.** Detect VO's
   recruited/controlled state and stand down for those villagers (at minimum: config-gated
   check via their attachment/saved data, or simply pause our behaviors when the vanilla brain
   is gated — if our behaviors live in the Brain, VO's gate pauses us automatically).
2. **Add our villager AI as Brain behaviors** via a `VillagerGoalPackages` mixin
   (Guard Villagers pattern), not by replacing the brain. This composes with both mods and
   with the vanilla schedule (work hours, sleep, meet).
3. **Never re-implement**: theft detection (Thief), guard combat (Guard Villagers),
   player-directed villager control / villager rendering & equipment visuals (VO).

## Vanilla villager internals (1.21.1, mojmap)

- Class chain: `Villager extends AbstractVillager extends AgeableMob extends PathfinderMob`.
  Villagers have **both** a `Brain<Villager>` (used by vanilla) and a `goalSelector`
  (unused by vanilla — free for mods).
- **Brain**: activities `CORE, WORK, PLAY, REST, MEET, IDLE, PANIC, PRE_RAID, RAID, HIDE`;
  behavior lists built by static `VillagerGoalPackages` factories; schedule
  (`Schedule.VILLAGER_DEFAULT`) switches activity by time of day. Brain is rebuilt on
  profession change via `refreshBrain` — never cache behavior instances per entity.
- **Memories/Sensors**: `MemoryModuleType.{HOME, JOB_SITE, MEETING_POINT, WALK_TARGET,
  LOOK_TARGET, INTERACTION_TARGET, NEAREST_VISIBLE_LIVING_ENTITIES, ...}`; sensors like
  `SensorType.NEAREST_ITEMS`, `VILLAGER_HOSTILES`. Custom `MemoryModuleType` and `SensorType`
  are registerable (registries exist; NeoForge `DeferredRegister` works).
- **POI system**: beds/bell/workstations are Points of Interest (`PoiManager` via
  `ServerLevel.getPoiManager()`, `PoiTypes`). Villager "knowledge" of home/job comes from
  claimed POIs stored as brain memories. Our village boundary/structure awareness should build
  on POI queries, not block scanning.
- **Gossip/reputation**: per-villager `GossipContainer` (`getGossips()`), `GossipType` with
  decay + transfer on villager meet. Thief piggybacks on this; so should our social
  consequences (it syncs for free with golem aggression, trade prices).
- **Existing inventory**: `AbstractVillager` already has an 8-slot `SimpleContainer`
  (`getInventory()`); vanilla farmers pick up crops/seeds into it (`wantsToPickUp`,
  `pickUpItem`) and share food. Phase 1's "real inventory" should extend/wrap this rather
  than bolt on a second disconnected container. Vanilla also has a hidden `foodLevel` used
  for breeding willingness — a seed for our hunger system.
- **Block breaking/placing by mobs**: not a vanilla capability — we implement it:
  look via `getLookControl().setLookAt(...)`, mining progress via
  `level.destroyBlockProgress(id, pos, progress)`, completion via `level.destroyBlock(pos, drop)`,
  speed from `state.getDestroySpeed`/`stack.getDestroySpeed`, durability via
  `stack.hurtAndBreak`. This is the core of "villagers obey player rules".
- **Pathfinding**: `GroundPathNavigation`; Brain movement via `WalkTarget` memory
  (`BehaviorUtils.setWalkAndLookTargetMemories`). Path range is limited by follow-range
  attribute; village-scale trips need waypointing or attribute increases — benchmark before
  deciding.

## NeoForge 1.21.1 systems we will use

- **Data attachments** (per-entity extra state): register `AttachmentType` in
  `NeoForgeRegistries.ATTACHMENT_TYPES` via `DeferredRegister`; serialize with a `Codec`
  (`AttachmentType.builder(...).serialize(codec)`); access with `hasData`/`getData`/`setData`.
  Entity attachments need manual network sync (custom payload) when the client cares.
- **SavedData** (per-level/village state): extend `SavedData`, `SavedData.Factory`, get via
  `serverLevel.getDataStorage().computeIfAbsent(factory, "dynamicvillagers_...")`; call
  `setDirty()` after mutation. Village registry lives here (overworld storage if cross-dim).
- **Events** (NeoForge bus): `EntityJoinLevelEvent` (wire up villagers on load),
  `ServerTickEvent.Pre/Post`, `LevelTickEvent` (village manager tick — keep budgeted),
  `RegisterCommandsEvent` (debug commands). Mod bus: `RegisterPayloadHandlersEvent`
  (networking, versioned `PayloadRegistrar`).
- **GameTest**: template ships a `gameTestServer` run config — usable for automated villager
  behavior tests later.

## Structure templates (Phase 4 research, 2026-07-10)

Verified against the recompiled 1.21.1/neoforge-21.1.235 sources in the Gradle cache
(`StructureTemplate`, `StructureTemplateManager`, `JigsawBlockEntity`, `NbtUtils`).

- **Loading**: `MinecraftServer.getStructureManager()` → `StructureTemplateManager`;
  `get(ResourceLocation)` returns `Optional<StructureTemplate>` from
  `data/<ns>/structure/*.nbt` (directory renamed to **singular** `structure` in 1.21;
  our gametest arena `empty5x5.nbt` already ships there). Also `readStructure(CompoundTag)`
  (parse raw NBT — handy for gametests) and `listTemplates()`.
- **Reading contents**: `StructureTemplate.palettes` is **private with no accessor**, and
  `filterBlocks(...)` only filters by a single `Block` type. The sanctioned read path is the
  public NBT round-trip: `template.save(new CompoundTag())` → parse `size` (3 ints),
  `palette` (list of `{Name, Properties}` — decode with
  `NbtUtils.readBlockState(HolderGetter<Block>, CompoundTag)`, getter from
  `level.holderLookup(Registries.BLOCK)`), `blocks` (list of `{pos: [x,y,z], state:
  paletteIndex, nbt?}`). Multi-palette templates ("palettes" list, e.g. shipwrecks) — use
  the first. This avoids an access transformer and the format is savegame-stable.
- **Transforms**: `StructureTemplate.transform(BlockPos, Mirror, Rotation, BlockPos pivot)`
  is public static for positions; `BlockState.rotate(Rotation)` for states;
  `getSize(Rotation)` / `getBoundingBox(...)` public.
- **Jigsaw normalization**: jigsaw block entities carry `final_state` as a string
  (`JigsawBlockEntity.FINAL_STATE`); parse with `BlockStateParser.parseForBlock`. Skip
  `minecraft:structure_void` (don't-care positions).
- **Authoring**: templates saved by in-game structure blocks land in
  `<world>/generated/<ns>/structures/`; copy into mod resources. Include `DataVersion`
  (1.21.1 = 3955) so DFU leaves the file alone.

## Population & village mechanics (Phase 5 research, 2026-07-12)

Verified against the decompiled `build/moddev/artifacts/neoforge-21.1.235-sources.jar`
(`Villager`, `VillagerMakeLove`) and the vanilla POI system. Drives docs/PHASE5_PLAN.md.

- **Villager breeding does NOT fire `BabyEntitySpawnEvent`** — that event sits on the
  `Animal.spawnChildFromBreeding` path. Villagers breed through the brain behavior
  `VillagerMakeLove.breed`: `parent.getBreedOffspring(...)` builds the child and calls
  `villager.finalizeSpawn(level, ..., MobSpawnType.BREEDING, null)`, then
  `level.addFreshEntityWithPassengers(villager)`.
- **Capacity gate = `FinalizeSpawnEvent`.** `VillagerMakeLove.breed` carries a NeoForge patch —
  *"If villager is blocked from spawning (e.g., FinalizeSpawnEvent), then breed should be
  unsuccessful"* — and returns empty when `!villager.isAddedToLevel()`. Cancelling
  `FinalizeSpawnEvent` where `getSpawnType() == BREEDING` for an over-capacity village cleanly
  aborts the birth (parents just stay unbred, no half-states). This is the population-cap hook.
- **Vanilla already gates breeding on a free bed.** `VillagerMakeLove` only breeds when it
  `canReach` an unclaimed `PoiTypes.HOME` within the POI's `validRange` (≈48). So bed count is
  a natural soft cap; `VillagerMakeLove.giveBedToChild` sets the newborn's `HOME` memory.
- **Bed/village-center counting via the POI manager** (no block scanning):
  `ServerLevel.getPoiManager()` + `PoiTypes.HOME` (`getInRange`/`getCountInRange` with an
  occupancy predicate) = total vs. free beds; `PoiTypes.MEETING` (bell) = village center.
- **Newborn registration** hooks `EntityJoinLevelEvent` (baby villager added to level).
  **Aging** is vanilla `AgeableMob` (`setAge(-24000)` newborn → grows to 0); notice the baby→
  adult transition by polling age in the manager tick, not via an event.
- **Our hunger is independent of vanilla breeding food.** `HungerSystem` stores hunger in the
  DV attachment; vanilla breeding willingness reads the separate hidden `Villager.foodLevel`.
  No interference — unifying them is a Phase 6 (economy) question.

### Phase 5 implementation follow-up (2026-07-13)

Verified against `VillagerGoalPackages`, `Brain`, `SocializeAtBell`, `InteractWith`,
`FinalizeSpawnEvent`, `PoiManager`, and `PoiRecord` from the pinned source jar, plus the official
NeoForge documentation for [SavedData](https://docs.neoforged.net/docs/datastorage/saveddata/),
[attachments](https://docs.neoforged.net/docs/1.21.1/datastorage/attachments/),
[events](https://docs.neoforged.net/docs/1.21.1/concepts/events/), and
[networking](https://docs.neoforged.net/docs/1.21.1/networking/).

- **Social movement and DV work can overlap.** Vanilla puts `SocializeAtBell` in `MEET` and
  villager-following `InteractWith` in `IDLE`; DV plans and executes tasks in `CORE`, which runs
  alongside the active non-core activity. Changing the whole schedule or replacing the vanilla
  packages would be unnecessarily invasive. A CORE behavior can instead clear only villager
  interaction/breeding memories and `WalkTarget`s backed by a villager `EntityTracker` while a
  real DV task is queued. Item- and block-backed targets remain intact. With an empty queue the
  behavior does not start, preserving vanilla socializing.
- **Use `FinalizeSpawnEvent#setSpawnCancelled`, not event cancellation.** NeoForge's event source
  explicitly distinguishes cancelling `finalizeSpawn` from preventing the entity spawn. The
  latter is what the patched `VillagerMakeLove` checks through `isAddedToLevel()`.
- **Population must not be derived only from loaded entities.** Persistent membership records now
  retain child/adult state. Loaded members refresh that bit on the manager tick; unloaded members
  remain in the tally, and deaths remove their membership through `LivingDeathEvent`.
- **Validate POI records against the current block state when counting capacity.** POI updates are
  normally automatic, but rapid structure teardown/replacement (especially GameTest arenas) can
  briefly expose records whose world block has already changed. Bed capacity therefore accepts
  only records whose current block is a bed head.
- **Datapack catalog loading uses the vanilla reload pipeline.** A
  `SimpleJsonResourceReloadListener` reads `data/<namespace>/building_catalog/*.json`; templates
  still load from the public structure manager and their footprint/material bill remains derived
  by `Blueprint` rather than duplicated in JSON.

### Population bootstrap, guards, and worker tools (2026-07-13)

Verified against the pinned NeoForge/Minecraft sources and Guard Villagers' `1.21.1` branch.

- **Use vanilla `SpawnUtil.trySpawnMob` for the one-time population bootstrap.** It finds a valid
  surface, creates the villager with `MobSpawnType.STRUCTURE`, runs normal finalization, and adds it
  to the level. The manager only enables this inside a real `StructureTags.VILLAGE` piece, so a
  player-placed bell does not manufacture 25 villagers. A persisted completion flag makes this
  initial seeding rather than permanent death replacement.
- **Guard Villagers needs no duplicate name store.** Its guard is the registered
  `guardvillagers:guard` entity and preserves vanilla `CustomName`; converted guards already carry
  their villager name. Naming unnamed guards on `EntityJoinLevelEvent` covers natural guard spawns,
  and counting by registry id avoids copying or replacing Guard Villagers internals.
- **Worker tools stay inside the existing vanilla-recipe crafting system.** Wooden tools and
  chests require the same carried ingredients a player uses. Tool recipes need a 3×3 table, so a
  worker with logs first crafts and physically places a table when none is nearby.
- **Stone gathering must be assignment-scoped.** A broad scan for exposed base stone caused miners
  to dig ordinary terrain and damaged the quarry walk-out ramp test. Starter miners now receive a
  manager-designated quarry and obtain cobblestone there before upgrading and mining exposed iron.

## Build/dev environment notes (this machine)

- Portable JDK 21 (Temurin 21.0.11) at `%USERPROFILE%\.jdks\jdk-21.0.11+10` — **not on PATH**;
  set `JAVA_HOME` to it before running `gradlew` (system-wide MSI install was declined by UAC).
- GitHub CLI at `C:\Program Files\GitHub CLI\gh.exe` (new shells get it on PATH).
- Windows path-length limit bites deep mod source trees: `git config core.longpaths true`
  if cloning mods for study.
