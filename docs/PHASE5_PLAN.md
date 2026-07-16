# Phase 5 Plan — Population

Goal (from CLAUDE.md): **villages expand naturally.** Children, housing capacity, population
limits, job assignment, and workforce balancing. This is the phase where "the village" stops
being a per-villager fiction (`VillageAnchor.resolve` = bell → bed → self) and becomes a real,
persistent, **named entity with a manager** — the central "Village Manager" box the roadmap's
Architecture Recommendation has been pointing at since Phase 1. Everything through Phase 4
decides *how* a villager does work (planners + tasks + WorkOrder physics). Phase 5 adds the
layer that decides *what work the village needs* and *who does it*: it posts construction
sites itself (replacing the Phase-4 player/command posting), assigns roles to balance the
workforce, and controls breeding against housing. Villagers still physically accomplish
everything — the manager only sets goals.

Status: **implementation in progress** (started 2026-07-13). The implementation now
contains the persistent village registry and membership, biome-weighted names, bed/population
tracking and breeding gate, automatic public storage ownership, the data-driven vanilla building
catalog and need-based site posting, autonomous staffing, bell inspector, commands/config, and
idle-only socializing while a worker has queued tasks. Natural villages receive a one-time initial
  population of 25, with five lumberjacks and two miners at that size; workers bootstrap wooden
  tools, the lead lumberjack builds public storage, tree searches persist locations, and guards are
  named and counted in the inspector. Farmers return to claimed composters, unemployed villagers
  distribute across homes, and storage placement rejects inaccessible roofs. Farmers collect and
  replant each harvested plot before moving on, crafting tables are shared village stations,
  lumberjacks share discovered groves, and full public storage is expanded. The full suite contains
  135 passing GameTests. Remaining
before the phase gate: broaden the two-village/persistence matrix, add direct
FARM/WAREHOUSE decision tests and the complete breed → house → build → breed end-to-end scenario,
then record the many-village performance benchmark and manual inspector playtest.

## Implementation notes (2026-07-13)

- `VillageManager` is the level `SavedData`; memberships persist the village id plus child/adult
  state, so unloaded villagers still count toward population and bed-limited breeding.
- A removed bell immediately dissolves its managed village, orphans loaded members, and removes
  that village's owned construction/storage records. This prevents invisible societal state from
  surviving after its physical anchor is gone.
- `VillageAnchor.resolve` now uses persistent home-village identity first and retains the original
  bell → bed → self fallback for unmanaged villagers.
- Vanilla social movement originates in the `MEET`/`IDLE` packages, while DV tasks execute in
  `CORE`. `WorkFocusBehavior` clears villager interaction/breeding targets and only villager-backed
  walk targets while the task queue is non-empty. Block/item task targets are untouched; an empty
  queue receives no suppression, so idle villagers socialize normally.
- The catalog ships 20 curated vanilla entries (HOUSE/FARM/WAREHOUSE across plains, desert,
  savanna, taiga, and snowy groups) at `data/dynamicvillagers/building_catalog/vanilla.json`.
- New server config: `showVillagerNameplates` and `autoStaffVillages`, both defaulting to true.
- Debug surfaces: `/dv village list`, `/dv village info <id>`, `/dv village autostaff <id> on|off`,
  plus Debug Wand right-click on a managed bell.
- Naturally generated villages are topped up once to 25 members, at most four successful
  physical structure spawns per manager decision. The persisted completion bit prevents later
  deaths from being replaced; beds remain the ongoing population limit.
- At 25 adults the manager targets five lumberjacks and two miners. New miners receive a starter
  quarry outside the center so wooden pickaxes obtain stone without excavating village terrain.
- Worker bootstrapping uses vanilla recipes: lumberjacks make wooden axes, miners wooden then
  stone pickaxes, farmers wooden hoes, and hunters wooden swords. Workers in the same 32-block
  village zone converge on one deterministic shared crafting station and reuse it; builders use
  the same lookup while keeping site-local fallback stations.
- The lead lumberjack crafts and places the first public chest near the bell and adds a separate,
  accessible public chest once every known public container is full. With no known tree,
  lumberjacks explore in 32-block directional steps out to 256 blocks, remember searched waypoints,
  and broadcast discovered tree bases so other lumberjacks immediately stop redundant searches.
- Farmers execute break → local drop collection → matching replant per mature crop, so a spread-out
  batch cannot lose later plots because their seed drops fell outside one early pickup radius.
- A managed miner that has a pickaxe but no mine/quarry assignment self-claims a distinct starter
  quarry before it plans an upgrade, so profession/command-driven miners cannot remain idle.
- Guard Villagers entities receive generated visible names; nearby guards are included in the
  inspector and `/dv village info` tally.

## Scope — includes the Phase 4 / Phase 3 deferrals the owner wants folded in

The owner asked to also pick up "planned features we did not add in Phase 4." These land here
because they all depend on a real village identity, which is exactly what 5.0 creates:

- **The village manager posts sites** (Phase 4 explicitly out of scope: "Deciding *what/where*
  to build (Phase 5 village manager — sites are player/command-posted this phase)"). → 5.3.
- **Curating the vanilla building catalog** per biome, with footprint/material metadata
  (Phase 4 open question: "becomes the Phase 5 expansion catalog"). → 5.3.
- **Procedural village + villager names**, biome-weighted (owner idea, "Natural home: Phase 5,
  where villages first need persistent identity beyond 'the nearest bell'"). → 5.0.
- **Village inspector UI** via the Debug Wand on a bell (owner idea; "Depends on there being an
  actual Village/VillageManager SavedData to query (Phase 5)"). → 5.5.
- **Auto-public storage near the bell** and **per-village record ownership** (Phase 3 deferred:
  "wait for village identity (Phase 5 Village Manager)"). → 5.1 (auto-public), 5.0 (ownership).
- **Site-preview ghost rendering** (Phase 4 client-polish deferral, "revisit with the Phase 5
  village inspector UI idea"). → optional 5.5 stretch, not a gate item.

Deliberately still deferred: **multi-builder crews** (dedicated hauler vs. placer division of
labor — "Phase 5/6 territory") stays out unless 5.4 needs it; **Improved Village Placement**
integration keeps its own research-first track (owner idea, not scheduled).

## What Phases 1–4 already give us

- **`VillageAnchor`** ([village/VillageAnchor.java](../src/main/java/com/dynamicvillagers/village/VillageAnchor.java)):
  the bell → bed → self anchor every village-scoped query already uses. Phase 5 promotes this
  from a static helper into a persistent, registered `Village` while keeping the same call
  sites working (a `Village` still exposes an anchor + range).
- **SavedData pattern, twice proven**: `StorageLedger` and `ConstructionLedger` are both
  per-level `SavedData` with distance-gated queries, codec/NBT persistence, `clear()` for
  gametest isolation, and a `.get(ServerLevel)` accessor. `VillageManager` is a third one
  in exactly this mould.
- **Roles + the planner bridge**: `VillagerRole`, `RolePlanners`, `PlanWorkBehavior` (plan
  only when the queue is empty), and the **role→profession bridge** (`SeekJobSiteBehavior`
  claims a jobsite; the `setVillagerData` mirror keeps role and profession in sync). The
  manager assigns work simply by **setting a villager's role** — the existing bridge then
  makes it seek a jobsite, gain the skin, and start planning. Zero new "make it work" code.
- **Construction is site-driven and idempotent**: `ConstructionLedger.addSite(...)` posts a
  building; `BuilderPlanner` diffs the blueprint against the world, so a manager-posted site
  builds identically to a marker-posted one. `SiteValidator` already refuses overlapping /
  cliff-edge / underwater / groundless footprints — the manager reuses it for spot selection.
  `removeDemolishedSites` already prunes razed footprints.
- **The request board** feeds any site's shortfall through gatherers — a manager-posted house
  pulls materials the day it exists.
- **Per-villager state** (`VillagerEssence`, one data attachment) is the natural home for
  `homeVillageId` and a generated `name`. Task-queue/role/site-assignment persistence is
  already solved there.
- **Debug UI plumbing**: `VillagerDebugScreen` + the three `VillagerDebug*Payload`s + the
  Debug Wand (`DVItems.DEBUG_WAND`) are a working request→snapshot→screen round trip. The
  village inspector is a sibling flow (bell target instead of villager target).

## Gaps Phase 5 must fill

- **No village identity.** Nothing persists "this is a village, here is its center, boundary,
  name, and members." Everything is recomputed per villager from the nearest bell. Children,
  population caps, and per-village storage ownership all need a stable identity.
- **No central decision-maker.** No system decides "the village needs another house" or "this
  new adult should farm." Sites and roles are posted/assigned only by the player.
- **No breeding control.** Vanilla villagers breed whenever willing (12 food points) and a
  reachable unclaimed bed exists; nothing ties this to a village-level population policy or
  registers the newborn as a member.
- **No buildable catalog.** Phase 4 can build *any one* named vanilla piece on command, but
  there is no menu of "houses this biome's village may build," their footprints, or their
  bills — so the manager has nothing to choose from and no way to size a spot.
- **No spot selection.** Nothing scans terrain for "a flat, unobstructed, non-overlapping
  place to put a 7×7 house near the village."

## Verified engine mechanics (Phase 5 research, 2026-07-12)

Checked against the decompiled `neoforge-21.1.235-sources.jar` in `build/moddev/artifacts`
(same method RESEARCH.md used for structure templates). Appended to RESEARCH.md.

- **Villager breeding does NOT fire `BabyEntitySpawnEvent`.** That event is on the
  `Animal.spawnChildFromBreeding` path; villagers breed through the brain behavior
  `VillagerMakeLove.breed`, which calls `parent.getBreedOffspring(...)` →
  `villager.finalizeSpawn(..., MobSpawnType.BREEDING, ...)` → `level.addFreshEntityWithPassengers`.
- **The clean capacity gate is `FinalizeSpawnEvent`.** `VillagerMakeLove.breed` has an explicit
  NeoForge patch — *"If villager is blocked from spawning (e.g., FinalizeSpawnEvent), then breed
  should be unsuccessful"* — and bails when `!villager.isAddedToLevel()`. So **cancelling
  `FinalizeSpawnEvent` for `spawnType == BREEDING` cleanly aborts a birth** and the parents just
  stay unbred; no half-states. This is our village population cap hook.
- **Vanilla already enforces "a free bed exists" to breed.** `VillagerMakeLove` only breeds when
  it `canReach` an unclaimed `PoiTypes.HOME` within the POI's `validRange` (≈48). So bed count is
  already a soft population ceiling; Phase 5 formalizes and reads it, and `giveBedToChild` sets
  the newborn's `HOME` memory for us.
- **Beds are countable via the POI manager.** `ServerLevel.getPoiManager()` +
  `PoiTypes.HOME`: `getInRange(...)` / `getCountInRange(...)` with an occupancy predicate gives
  total vs. free beds in a boundary — no block scanning. Meeting point (`PoiTypes.MEETING` =
  bell) is how a village center is found. (Consistent with RESEARCH.md's POI notes.)
- **Newborn registration hook is `EntityJoinLevelEvent`** (baby villager added to the level) —
  assign `homeVillageId`, generate a name, leave role NONE until it matures.
- **Aging is vanilla.** `AgeableMob` handles baby → adult (`setAge(-24000)` newborn, grows to
  0). We only need to notice the transition to trigger a job assignment (5.4) — poll age in the
  manager tick rather than hunting for an aging event.
- **Our hunger ≠ vanilla breeding food.** `HungerSystem` stores hunger in our attachment;
  vanilla breeding willingness reads the separate hidden `Villager.foodLevel`. They don't
  interfere. Unifying them (breeding costs village food) is a Phase 6/economy question, flagged
  in Open Questions — not Phase 5.

## Architectural decisions

1. **`VillageManager extends SavedData`, one per level, is the registry of villages** (sibling
   of `StorageLedger`/`ConstructionLedger`, name `dynamicvillagers_villages`). It holds
   `Village` records and runs the budgeted decision tick. Cross-dimension villages are not a
   thing (a village is one level's); this matches both ledgers being per-level.

2. **A `Village` is anchored on a bell (`MEETING` POI), with a fixed boundary radius.** Center =
   the bell's `BlockPos`; boundary = center + `VILLAGE_RADIUS` (start with the storage
   `NETWORK_RANGE` = 64 so all three systems agree on "in the village"). A village *forms* when a
   bell is discovered with villagers nearby and *dissolves* when its bell is gone and it has no
   members. This deliberately reuses vanilla's own definition of a village center — no custom
   boundary math, and it adopts **naturally generated vanilla villages** for free (they have
   bells). Merging/splitting of overlapping bells is out of scope (nearest-center wins); the
   64-block radius makes double-bell villages rare.

3. **Membership is a persistent `homeVillageId` on the villager, reconciled by the manager**
   (hybrid, not pure proximity). Stored in `VillagerEssence` so a villager that wanders to mine
   keeps its home, and children inherit their parents' village. The manager reconciles each
   tick: an unassigned adult/child inside a village's boundary is adopted; a member whose
   village dissolved is orphaned (id cleared) and re-adoptable. Pure per-tick proximity was
   considered and rejected — it loses identity the moment a villager leaves the radius, which
   breaks children and (later) per-villager wealth/employment.

4. **The manager decides; villagers execute — nothing new in the execution layer.** The manager
   only ever (a) posts a `ConstructionSite` (existing ledger call), (b) sets a villager's
   `VillagerRole` and/or `assignedSiteId` (existing essence fields + bridge), or (c) cancels a
   `FinalizeSpawnEvent`. It never moves items, places blocks, or drives pathfinding. This keeps
   the design-philosophy invariants (physical work only, no cheating AI) untouched and means
   Phase 5 adds *goal-setting*, not new *physics*.

5. **The manager tick is budgeted and spread, like a colony tick.** Villages tick on a coarse
   interval (e.g. one village per N game ticks, round-robin), and only when their center chunk
   is loaded. Each tick is a cheap needs assessment (count members/beds/roles/open sites via POI
   + ledger queries) → at most one decision (post one site, or assign one role, or nothing). No
   village re-plans every block. This is the roadmap's explicit guard against "every villager
   solving global planning every tick," applied at the village level.

6. **Names are generated, never stored as a list** (owner directive). Small biome-weighted
   prefix/suffix pools (~200 strings → thousands of names). Village name is generated once at
   formation and persisted on the `Village`; a villager name is generated at join/birth and
   persisted in `VillagerEssence`. **Villager names show as in-world nameplates by default**
   (owner decision 2026-07-12): apply the generated name as the villager's `CustomName`, with a
   config option to switch to inspector/debug-only (no floating tags) for players who want a
   clean world. The name is generated and persisted either way — the config only governs the
   `CustomName` nameplate.

7. **The buildable catalog is data, curated per biome group.** A registry of vanilla village
   pieces (`minecraft:village/<biome>/houses/...`) tagged by *type* (house / farm / …),
   *footprint size* (from the template), and derived *material bill* (Blueprint already computes
   requirements). Ships as a JSON data file (data-pack-overridable) rather than hardcoded, so
   the menu is tunable and mod/datapack-extendable — consistent with the "use vanilla structures,
   don't design our own" directive. The manager picks from the entries whose biome group matches
   the village center's biome.

## Milestones

### 5.0 Village identity & the manager registry (foundation)
- `village/Village`: id, name, center (bell `BlockPos`), radius, created time, and cached
  tallies (population, role histogram, open-site ids) refreshed on tick. `village/VillageManager
  extends SavedData` (`dynamicvillagers_villages`): CRUD, `villageAt(pos)` / `villageFor(uuid)`
  / `nearestVillage(pos, range)`, `clear()` for gametests, codec/NBT persistence.
- Formation & dissolution: discover a village around each `MEETING` POI (bell) that has ≥1
  villager within radius; dissolve one whose bell POI is gone and whose member set is empty.
- Membership: `homeVillageId` (int) added to `VillagerEssence` (serialized). Manager reconciles
  adoption/orphaning per decision 3.
- Procedural names (decision 6): `village/Names` with biome-weighted pools; village named at
  formation; villager named at `EntityJoinLevelEvent` if it has none. Names are applied as the
  villager's `CustomName` (visible nameplate) by default; a config flag switches to
  inspector/debug-only. Village name is shown only in the command/inspector.
- Promote `VillageAnchor` callers: add `Village.anchor()`/`.range()` and have the manager be
  the source of truth; keep `VillageAnchor.resolve` working for villagers not yet in a village
  (fallback). No behavior change for existing systems.
- Budgeted tick scaffold (decision 5) that only *tracks* for now (no decisions yet).
- `/dv village list` and `/dv village info <id>` (name, center, radius, population, roles,
  beds, open sites) — the debugging surface for everything that follows.
- GameTests: a bell + nearby villagers forms one village; two distant bells → two villages;
  members get `homeVillageId`; a wandered member keeps its id; dissolving a bell orphans
  members; persistence round-trip; name generation is deterministic per seed/center.

### 5.1 Housing & population tracking
- Manager tallies per village each tick: **population** (adult + child members), **beds** total
  vs. free (POI `HOME` count in boundary, decision-per-research), **houses** (DONE house sites +
  a bed cluster heuristic for vanilla-generated houses), and **housing pressure** = population
  vs. free beds.
- **Auto-public storage near the bell** (Phase 3 deferral): chests/barrels within a small
  radius of the center auto-designate `PUBLIC` on the storage ledger for that village, and new
  container records inside a boundary record their `villageId` (per-village record ownership,
  decision 3) — so a future two-village world keeps storage separate. Existing manual
  designations are never overwritten.
- GameTests: beds counted total/free; placing a bed raises capacity; a chest by the bell
  becomes PUBLIC; housing pressure crosses its threshold when members exceed free beds.

### 5.2 Children & breeding control
- Register newborns: on `EntityJoinLevelEvent` for a `BREEDING`-spawned baby villager inside a
  boundary, set `homeVillageId` to the parents' village and generate a name; role stays NONE.
- **Population cap = free beds only** (owner decision 2026-07-12: bed-limited, no hard config
  ceiling). Vanilla `VillagerMakeLove` already refuses to breed without a reachable unclaimed
  `HOME` (bed), so this is largely automatic — the village grows exactly as fast as it houses
  itself, which is what drives 5.3 to build. We add the `FinalizeSpawnEvent` (`spawnType ==
  BREEDING`) gate only to **scope the free-bed check to the village's own boundary** (vanilla's
  ~48-block "any nearby bed" is looser than our 64 radius, so a bed in a neighbouring village
  shouldn't authorise a birth here). No flat population number.
- Maturation → job trigger: the tick notices a member whose age crossed to adult and flags it
  for assignment (consumed by 5.4).
- GameTests: a baby born with a free bed registers to the village with a name; breeding is
  blocked (event cancelled, parents unbred) when the village's own beds are all claimed; a
  matured child is flagged and gets a role.

### 5.3 Village build decisions — the manager posts sites (replaces command-posting)
- **Buildable catalog** (decision 7): `construction/BuildingCatalog` loaded from a JSON data
  file, entries `{templateId, type, biomeGroup, footprint, bill}` for the main biome groups
  (plains/desert/savanna/taiga/snowy), reusing the vanilla `minecraft:village` templates Phase 4
  already parses. **Broader menu** (owner decision 2026-07-12): three `type`s at launch —
  `HOUSE` (vanilla houses, raises bed capacity), `FARM` (vanilla farm pieces; the existing
  `FarmerPlanner` takes over finished farmland → food with no new code), and `WAREHOUSE` (a
  vanilla village piece that contains containers, whose chests the manager auto-designates
  `PUBLIC` per 5.1 → village storage). Note: vanilla has no dedicated "warehouse" structure, so
  the catalog curates which container-bearing vanilla pieces play that role — a curation task,
  not a new template. Further types (defensive/decorative) stay for later phases.
- **Need → build**: each tick assess the top unmet need and post one site for it —
  **housing pressure** (population vs. free beds) → HOUSE; **food shortage** (low food in
  village storage / few farmers) → FARM; **storage pressure** (public containers near-full) →
  WAREHOUSE. One decision per tick keeps it budgeted (decision 5).
- **Spot selection**: pick the catalog entry for the need + biome, scan the boundary for a
  flat-enough, unobstructed footprint that passes `SiteValidator` (existing overlap/ground/depth
  checks), and post a `ConstructionSite` there with a sensible rotation (face the nearest
  path/center). Mark the site village-owned. Concurrency-capped (≤ N open village sites) and
  rate-limited so it never spams.
- GameTests: a housing-pressured village posts exactly one valid, non-overlapping HOUSE at a
  flat spot and no more than the cap; a food-short village posts a FARM (and a farmer finishes
  it → planted); a storage-full village posts a WAREHOUSE whose chests come out PUBLIC; a
  satisfied village posts nothing; all-unsuitable terrain fails gracefully without spamming; a
  posted site builds to DONE via the existing builder path (end-to-end with an assigned builder
  from 5.4).

### 5.4 Job assignment & workforce balancing
- The manager assigns `VillagerRole` to NONE-profession adult members to meet village needs
  against target ratios: guarantee food (a farmer, and/or a hunter), staff open construction
  sites with builders, and keep feeders (lumberjack for wood, miner for stone) proportional to
  active building. Newly-matured children (5.2) are the primary intake.
- **Assign builders to open village sites** (`assignedSiteId`) — the autonomous replacement for
  the Building Marker / `/dv build assign`. Respects the existing rule that an assignment
  outranks the profession→role mirror.
- **Respect manual control**: a role the player set by command/marker is never auto-reassigned;
  the manager only touches members it "owns" (assigned by it, or NONE). The
  can't-drop-a-traded-profession constraint (`SeekJobSiteBehavior`) is honoured — the manager
  re-roles only NONE-profession villagers.
- Auto-assignment is **on by default** (owner decision 2026-07-12), with `/dv village autostaff
  <id> on|off` and a config default — a hands-off player gets a self-running village, a
  micromanager can disable it per village or globally.
- Rebalance on change: a finished site releases its builder back to the pool for reassignment.
- GameTests: a village with an open site + idle adults gets a builder assigned and the house
  reaches DONE; a food shortage yields a farmer; a manually-set MINER is not stomped by
  auto-staffing; a matured child with no need is left NONE (no thrashing).

### 5.5 Village inspector UI
- Debug Wand right-click on a **bell** → server assembles a village snapshot (name, population
  adults/children, role histogram, beds total/free, houses, open sites + progress, storage
  summary from the ledger) → new payload → client screen (sibling of `VillagerDebugScreen`).
- Optional stretch (not a gate item): **site-preview ghost** outline for pending/under-
  construction sites, client-only render.
- GameTests: server-side snapshot assembly is correct for a known village (the client screen
  itself is a manual playtest, as with the villager debug screen).

### 5.6 Phase gate
- Perf: extend `PerformanceBenchmarks` with a many-villages / dense-population scenario; confirm
  the manager tick stays well under the tick-time guard and there's no regression vs. Phase 4
  (the budgeted round-robin tick should be near-free).
- End-to-end playtest (the "villages expand naturally" proof): a small starter village with a
  bell, a few beds, and food breeds → hits bed capacity → the manager posts a house → an
  auto-assigned builder builds it → capacity rises → breeding resumes → the new adult is given a
  job. Names visible in `/dv village info` and the inspector.
- Full gametest suite green with Guard Villagers + Thief loaded.
- Docs updated with findings; CLAUDE.md/AGENTS.md pointer moved to Phase 6.

## Resolved decisions (owner, 2026-07-12)

1. **Population cap → free beds only.** No hard config ceiling; the village grows exactly as
   fast as it houses itself (vanilla's free-bed breeding requirement, scoped to the village
   boundary). See 5.2.
2. **Job auto-assignment → on by default**, with a `/dv village autostaff` per-village toggle
   and a config default. See 5.4.
3. **Villager names → floating nameplate by default** (`CustomName`), with a config option to
   switch to inspector/debug-only. Names generated + persisted regardless. See 5.0 / decision 6.
4. **Build scope → broader menu**: HOUSE, FARM, and WAREHOUSE at launch (need-driven), further
   types later. See 5.3 / decision 7.

## Still-open questions

5. **Breeding & village food unification.** Leave our hunger and vanilla breeding-food
   independent for Phase 5 (recommended) vs. make breeding draw on village food stores now
   (pulls Phase 6 economy forward).
6. **Vanilla-village adoption depth.** Adopting bells means naturally-generated villages become
   managed for free; confirm that's desired (recommended yes) and that we should *not* start
   auto-remodeling an existing generated village's layout (recommended: only add new sites in
   open space, never overwrite what generated).
7. **Village boundary model.** Fixed 64-block radius from the bell (recommended, agrees with
   storage `NETWORK_RANGE`) vs. a POI-spread boundary vs. deferring to Improved Village Placement
   if/when that integration happens.
8. **`WAREHOUSE` template sourcing (5.3).** Vanilla has no dedicated warehouse structure — which
   container-bearing vanilla village pieces should play that role per biome is a catalog-curation
   call to make when 5.3 is built.

## Explicitly out of scope for Phase 5

Wages / employment / wealth / buying-selling / paying builders (Phase 6 — 5.4 balances by
*need*, never by money), governments and ownership models (Phase 7 — this manager is
apolitical; government becomes a modifier on top of it), guard patrols / walls / gates /
watchtowers / chest defense (Phase 8 — the blueprint system already serves defensive
structures), inter-village merchants / trade routes / diplomacy / territory (Phase 9),
colony founding and multi-village competition (Phase 10), dedicated hauler-vs-placer build
crews, remodeling or demolishing existing buildings, and Improved Village Placement worldgen
integration (its own research-first track).
