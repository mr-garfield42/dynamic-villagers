# Phase 3 Plan — Storage Network

Goal (from CLAUDE.md): **villagers stop carrying everything forever.** Public and private
storage, item reservation, material requests, and shared knowledge of where supplies are —
so a villager that needs oak logs walks straight to the right chest instead of rummaging
through every container it has ever seen.

Status: **milestones 3.0–3.3 complete** (2026-07-09); 3.4 partially done — benchmark and
gametest coexistence are green, the manual playtest is pending.
- 10 new gametests (`StorageNetworkTests`); full suite **55/55 green** with Guard Villagers +
  Thief loaded.
- Perf (3.4): benchmark suite re-run with the ledger wired into every planner — 50 plain
  villagers 1.518 ms/tick, 50 idle lumberjacks 1.761 ms/tick → ~0.24 ms/tick mod overhead
  for 50 workers (Phase 2 measured 0.32 ms/tick on the same scenario). No regression.
- Implementation decisions made along the way:
  - Requests **require** a deliver-to container (model and `/dv request add`). The "optional
    deliver-to" in decision 6 turned out to have no actionable meaning before Phase 4
    construction; relax it when something needs it. Same for a requester field — dropped
    until something consumes it.
  - Keep-lists ride along into `DeliverItemsTask`: the effective filter is (request filter
    AND NOT keep list), so a lumberjack serving an "axe" request never hands over its own
    axe — and in fact cannot serve that request at all, while a farmer (keeps hoes, not
    axes) can. Emergent specialization with zero special cases.
  - Storage Marker designations go through a cancelled `PlayerInteractEvent.RightClickBlock`,
    not `Item#useOn` — a chest opens its own GUI before `useOn` would ever run.
  - Gametest isolation finding: the ledger is level-global SavedData and **outlives test
    arenas** (batches share the test server's level, and NETWORK_RANGE 64 spans many
    arenas). Every storage test therefore runs in its own batch and wipes the ledger first
    (`StorageLedger.clear()`).
- Owner playtest, first round (2026-07-10): deposits work; Storage Marker works. Fixes from
  the feedback, same day:
  - **Canopy babysitting**: a lumberjack stood under its felled tree for up to a minute
    "waiting for items" — leaf decay drips saplings for a minute-plus and drops can land on
    top of the canopy where no path exists. `PickUpItemsTask` now only chases items present
    in its first ~5 seconds (later decay drips are gleaned opportunistically by passing
    villagers, as before) and skips any item it can't get close to within ~5 seconds.
  - **Chest lid animation** (owner request): `ContainerAnimator` — chest lids visibly swing
    open when a villager deposits/withdraws/delivers (block event, lid only, no trapped-chest
    redstone; barrels toggle their OPEN state) and fall shut ~0.75 s later on the level tick.
    Fire-and-forget, so no task exit path can leave a lid hanging open.
  - **"No Thief effects" solved — test-environment, not a bug**: Thief's default config only
    detects crimes inside generated village structures (`crime_only_in_protected_structures`),
    and the steal test ran on a creative test pad. Chest opening is a MEDIUM crime and guards
    attack at MEDIUM+, so it does work inside real villages. The dev instance config
    (gitignored) now sets the gate to false so test pads behave; see CLAUDE.md for the
    Phase 8 implication (dynamically grown villages are also outside generated structures).
- Still open for 3.4: redo the steal test with the gate off (or in a generated village) —
  witnesses in range, no Hero of the Village buff.

## What Phase 2 already gives us

- `VillagerMemory.knownContainers` — personal, perception-fed (8-block radius + line of
  sight) knowledge that a container *exists*.
- `DepositToContainerTask` — walk to nearest remembered container, empty combined inventory
  minus keep-filters, spillover stays carried.
- `TakeItemsTask` — walk to remembered containers nearest-first and look inside each
  ("knowing a chest exists is not knowing its contents").
- Eager deposits (≥ 16 non-kept items) from all three gatherer planners.

What's missing: nobody remembers chest *contents* after a visit, two villagers can plan
around the same items, all chests are equal (no ownership, no preference), and there is no
way for anything to *ask* for materials.

## Architectural decisions

1. **A shared ledger is village knowledge, not wallhacks.** New `StorageLedger` SavedData
   holds per-container records: a contents snapshot, last-inspected time, designation, and
   reservations. A snapshot is written **only when a villager actually opens the container**
   (deposit/withdraw/search) — never from world scanning. CLAUDE.md explicitly carves this
   out: "Builders know where supplies are (only to prevent time wasted searching through
   chests)". The ledger is deliberately stale-able: players moving items behind the village's
   back are not observed; the record is corrected the next time a villager opens the chest
   (arrive → verify → update → re-plan if the goods are gone). Personal `VillagerMemory`
   stays what it is (how a villager *discovers* containers); the ledger is what the village
   collectively *remembers about* them.

2. **One SavedData per level, distance-gated at query time — no village identity yet.**
   Real village membership arrives with the Village Manager (Phase 5). Until then the ledger
   is `level.getDataStorage().computeIfAbsent(..., "dynamicvillagers_storage")`, keyed by
   `BlockPos`, and every query is limited to `NETWORK_RANGE` (64) around the villager's
   anchor (bell → bed → self — extract `TorchChore.resolveAnchor` into a shared
   `VillageAnchor` helper). Two villages 200 blocks apart therefore never see each other's
   ledger records, without needing a village registry.

3. **The storage network is chests and barrels, not furnaces.** `PerceptionSystem` and the
   ledger recognize storage by the new block tag `dynamicvillagers:storage_containers`
   (default: chest, trapped chest, barrel; data packs and mod compat can extend it). Phase 2
   remembered *any* `Container` block entity, which technically includes furnaces, hoppers,
   and droppers — villagers stuffing logs into a furnace's fuel slot is not "storage".
   Double chests remain two records (deferred, as in Phase 2).

4. **Designations are explicit; unclaimed containers keep Phase 2 behavior.** Three states:
   `UNCLAIMED` (default — anyone may use, exactly as today), `PUBLIC` (village storage —
   preferred deposit target), `PRIVATE(owner)` (one villager's chest — nobody else opens
   it, in either direction). Set by the `/dv storage` command or the new **Storage Marker**
   creative item (same bind-flow as the mine/quarry markers: bound to a villager = mark
   private for them; unbound = toggle public). Auto-designating chests near the bell is
   deferred to the village-manager phase. A world with no designations behaves exactly like
   Phase 2 — no migration needed.

5. **Reservations resolve filters to concrete items, persist, and expire.** When a task
   targets ledger-known items it reserves them: `(holder UUID, item prototype, count,
   expiry)` on the container record. Availability for any query = snapshot − *other*
   holders' reservations. Released explicitly when the reserving task finishes or fails;
   the expiry (default 6000 ticks ≈ 5 min) is the backstop for cleared task queues, chunk
   weirdness, and death (plus a cheap release-all in the existing death hook). Persisting
   them matches task-queue persistence: a reload resumes the task *and* its claim.

6. **Requests direct work; they never teleport items.** A `MaterialRequest` on the ledger:
   `(id, filter, count remaining, optional deliver-to container, requester, created time)`.
   Fulfillment is physical: if matching items sit unreserved in the network, an idle roled
   villager runs the new `DeliverItemsTask` (reserve → take from source → walk → deposit at
   the deliver-to chest). If not, gatherers whose produce matches (lumberjack → logs, miner
   → stone/ores, farmer → crops) deliver their haul directly to the deliver-to chest instead
   of general storage. Requests come from a debug command now; Phase 4 construction sites
   become the real requester. Villager tool "wants" (an axe-less lumberjack) do **not** post
   requests in Phase 3 — nothing can craft yet, so they'd pile up; the ledger already makes
   their next fetch attempt cheap.

## Milestones

### 3.0 Storage ledger foundation — *done*
- `StorageLedger extends SavedData` + `ContainerRecord` (snapshot as merged `ItemStack`
  copies — preserves components so `ItemFilter` semantics carry over unchanged; last
  inspected game time; designation; reservations). Codec/NBT serialization, `setDirty()`
  discipline.
- Snapshot hooks: `TakeItemsTask` and `DepositToContainerTask` record contents after every
  container they open; the existing forget-on-invalid paths also drop the ledger record.
- `dynamicvillagers:storage_containers` block tag; `PerceptionSystem` + both container
  tasks gated on it.
- `VillageAnchor` helper extracted from `TorchChore`.
- `/dv storage list` — dump nearby records (pos, designation, contents summary, age) for
  debugging.
- GameTests: deposit writes a snapshot; withdrawal updates it; broken chest's record dies
  on next visit; furnaces are not remembered.

### 3.1 Public & private storage — *done*
- Designation on the record + `/dv storage public|private|unclaim <pos> [villager]`.
- **Storage Marker** item (Tools tab, permission 2, `SiteMarkerItem` subclass).
- Respect rules in both tasks and all planner search paths: never open another villager's
  private container; deposits prefer PUBLIC (nearest public first, unclaimed fallback);
  `TakeItemsTask` searches own-private → public → unclaimed.
- GameTests: non-owner can't take from or deposit into a private chest; deposit chooses a
  public chest over a nearer unclaimed one; owner still uses their own private chest.

### 3.2 Smart logistics & reservations — *done*
- `TakeItemsTask` ledger path: query nearest container with enough unreserved matching
  items → reserve → walk straight there → verify on arrival (stale: update snapshot,
  release, re-query) → withdraw → release. Falls back to the Phase 2 walk-and-search when
  the ledger knows no source (and each searched chest tops up the ledger, so searches get
  rarer over time).
- `DepositToContainerTask` destination choice via ledger: public containers already holding
  a matching item type first (emergent sorting), then public with free space, then the
  Phase 2 nearest-remembered fallback; full chest → next candidate instead of "done with
  leftovers".
- Reservation lifecycle per decision 5, including release-all on villager death.
- GameTests: villager B walks directly to the chest villager A filled (no search of decoy
  chests); two villagers planning against one chest don't double-claim the same items;
  stale ledger (test empties the chest directly) is corrected and the task recovers.

### 3.3 Material requests — *done*
- Request board on the ledger + `/dv request add <filter> <count> [deliver-to pos]`,
  `/dv request list|cancel`.
- `DeliverItemsTask(filter, count, destination)` — reservation-backed take + targeted
  deposit; registered in `TaskTypes`.
- Planner integration: before falling back to `TorchChore`, an idle roled villager checks
  for a deliverable request (items available in-network) and hauls; gatherers with an open
  request matching their produce deposit that produce at the deliver-to chest instead of
  general storage.
- Shown in `/dv inspect` (open requests a villager is serving); debug GUI panel is polish,
  not required.
- GameTests: a request drains public storage into the deliver-to chest; a lumberjack's
  logs land at the requesting chest while the request is open.

### 3.4 Phase gate — *benchmark + gametests done; manual playtest pending*
- Perf: rerun `PerformanceBenchmarks` with ledger-enabled planners (50 villagers) — the
  ledger should *reduce* work (fewer walk-and-search cycles); confirm no regression from
  record queries.
- Coexistence: full gametest suite with Guard Villagers + Thief loaded; manual dev-client
  session including the still-pending Thief steal test from 2.7 (steal from a chest a
  villager has ledger knowledge of — confirm Thief crime fires and our systems don't
  interfere).
- Docs updated with findings; CLAUDE.md pointer moved to Phase 4.

## Open questions / deferred

- **Double chests** are still two ledger records; the seam is now visible (a deposit into
  the left half updates one record only). Merge via `ChestBlock.getConnectedDirection` is
  a contained fix if the owner hits it in play.
- **Space reservation for deposits** (two haulers racing one nearly-full chest) is not
  modeled; the spillover-to-next-candidate rule absorbs it. Revisit if quarry-scale hauling
  thrashes.
- **Auto-public storage near the bell** and per-village record ownership wait for village
  identity (Phase 5 Village Manager).
- **Thief integration for villager-owned chests**: Thief's protection tags govern *player*
  crimes; whether marking villager-private chests with `thief:break_protected_*` tags is
  desirable (players stealing from villager chests = crime) is an owner call — flag it
  during the 3.4 playtest.
- **Ledger records in unloaded chunks**: targeting one makes the villager walk there (the
  give-up timers from 2.6 bound the cost). No eager pruning; records die on visit like
  everything else.

## Explicitly out of scope for Phase 3

Blueprint/construction material consumption (Phase 4 — it will *use* the request board),
wages/buying/selling (Phase 6), guard protection of storage (Phase 8), crafting, minecart
or any non-walking item transport, auto role assignment (Phase 5).
