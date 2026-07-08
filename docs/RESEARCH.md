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
- **Key technique for us — `VillagerGoalPackagesMixin`**: mixin into vanilla
  `VillagerGoalPackages.getCorePackage/getMeetPackage/getIdlePackage` `@At("RETURN")` to append
  extra Brain behaviors (e.g. `RepairGolem`, `ShareGossipWithGuard`) to every villager. This is
  the clean way to add Brain behaviors that survive brain rebuilds (profession change,
  `refreshBrain`), because vanilla re-calls these package factories every rebuild.
- Uses NeoForge **data attachments** (`GuardDataAttachments`) for extra per-entity state.

### Villager Overhaul (`villageroverhaul` v3.10.x, custom source-available license, by z2six)

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

## Build/dev environment notes (this machine)

- Portable JDK 21 (Temurin 21.0.11) at `%USERPROFILE%\.jdks\jdk-21.0.11+10` — **not on PATH**;
  set `JAVA_HOME` to it before running `gradlew` (system-wide MSI install was declined by UAC).
- GitHub CLI at `C:\Program Files\GitHub CLI\gh.exe` (new shells get it on PATH).
- Windows path-length limit bites deep mod source trees: `git config core.longpaths true`
  if cloning mods for study.
