# Phase 2 Plan — Resource Gathering

Goal (from CLAUDE.md): **villages become resource-positive.** Villagers with gathering roles
autonomously find, harvest, and store resources under player rules: real reach, real tools,
real inventories, drops on the ground, knowledge only from perception.

Status: **complete** (2026-07-09). One loose end: the manual Thief steal test from 2.7 is
still pending; it is folded into the Phase 3 gate (see docs/PHASE3_PLAN.md, milestone 3.4).
- Done 2026-07-09: milestones 2.0 + 2.1, death drops, debug wand + GUI (owner-verified in
  game). Milestones 2.2 (torches), 2.3 (farmer), 2.4 (miner) done later the same day, plus
  the **obstruction fix** (owner bug report: villagers mined logs through leaves): all block
  work now enforces "you can only work on what your eye-ray can hit" — `BreakBlockOrder`
  mines the obstructing block first, `PlaceBlockOrder` clears soft obstructions, container
  tasks skip walled-off chests, and villagers' heads track the exact block being worked
  (direct `LookControl` aim, wins over idle look-at-player). 34/34 GameTests green with
  Guard Villagers + Thief loaded.
- Torch rule: block light **< 8** (not == 0): matches how players light bases, self-limits
  (torch radius), and the exact-0 rule is untestable anyway — the gametest harness's status
  beacon leaks block light ~5 into every arena.
- 2.5 (strip mines) done 2026-07-09, plus owner-requested **miner darkness fear**: a site
  where mobs could spawn (adjacent air with block light &lt; 8) is only worked if the miner
  carries torches to light it first; torchless miners skip it, go fetch torches, and return.
  Tunnels go dark fast, so torch cadence and hazard avoidance in strip mines both emerge from
  the one rule. Tunnel digging additionally refuses to break any block with fluid behind it.
  `/dv minesite <villager> <pos> <facing>` designates a tunnel (`clear` to unassign).
- **Farmer retilling of trampled farmland is already emergent**: trampling turns farmland to
  dirt next to remaining farmland with water in range — exactly what the 2.3 till rule
  targets. Only a *fully* trampled farm (no farmland left to be adjacent to) is not
  recovered; that needs remembered farm plots — deferred to the village-manager phase.
- 2.6 (quarry) done 2026-07-09. **Design decision — ramp, not ladders**: vanilla villager
  pathfinding cannot intentionally climb ladders, so a laddered pit would strand miners.
  Quarries instead keep a walk-out staircase (one step down per column along the cornerA
  wall; depth capped by wall length). The dig phase skips the stair cells, and a build phase
  repairs any step lost to obstruction-clearing with carried cobblestone (fetching some back
  from storage if it was already deposited) — deepest step first, so a miner at the bottom
  builds its own way out. `/dv quarry <villager> <corner1> <corner2>` (sides ≤ 32; `clear`).
  Also fixed alongside: Break/PlaceBlockTask now have give-up timers so machine-chosen
  unreachable targets can't stall the queue forever. Bridge planks deferred to Phase 4
  (construction), where scaffolding/support building lands anyway.
- Torch chore fixes (owner bug report with screenshot, 2026-07-09): one torch per plan cycle
  (batched placements ignored the light of the torch just placed → adjacent clusters) and a
  village anchor (bell → bed → self, radius 24) so a torch-stocked villager no longer walks a
  hundred blocks torching wilderness (the scan used to re-center on the villager each cycle,
  dragging it outward forever).
- Creative designation markers (owner request): **Mine Site Marker** and **Quarry Marker**
  (Tools tab). Right-click a villager to bind the marker, then click blocks: mine marker =
  tunnel start heading the way the player faces; quarry marker = two corners (sneak-click
  resets a half-placed corner). Designating a site auto-assigns the MINER role. Requires
  creative or permission 2.
- 2.7 benchmark done 2026-07-09 (kept as gametests `PerformanceBenchmarks`, own batches):
  50 villagers in a barren arena, all planners failing into full-scan backoff — the most
  expensive idle path. **Baseline 50 plain villagers: 2.011 ms/tick; 50 idle lumberjacks:
  2.332 ms/tick → ~0.32 ms/tick of mod overhead for 50 workers ≈ 6 µs per villager per
  tick.** No budgeting needed at Phase 2 scale.
- 2.7 manual coexistence session (owner, 2026-07-09): guard conversion mid-chop **works**;
  markers **work**; the Thief steal test is still pending (blocked at the time by villagers
  never depositing — fixed below; retest when convenient).
- Playtest fixes from that session (owner feedback, 2026-07-09):
  - **Focus**: work tasks re-assert their WALK_TARGET every tick. It was every 20 ticks, and
    the moment the vanilla movement sink cleared the target (reached/failed), an idle stroll
    behavior would hijack it — the "wanders off for a second mid-job" effect.
  - **Canopy descend**: a chopper stuck above its target (villagers can't path across leaves)
    digs the block under its own feet after a 3 s stuck spell — only leaves/logs, never
    terrain, so hillsides are safe.
  - **Torches vs. digging**: tunnel/quarry dig collectors treat torch blocks as cleared (they
    used to re-mine their own floor torches), and torch spots reject supports that the
    *current dig batch* is about to remove (the "placed, then broken a second later" bug).
    Supports dug in later layers are allowed on purpose: the torch serves its layer and is
    re-placed deeper, the way players re-torch. An earlier stricter rule (no torch anywhere
    in the pit) stalled quarries in fear-backoff loops — batch-scoped is the right width.
  - **Eager deposits**: gatherers now deposit once carrying ≥ 16 non-kept items (~3 trees)
    instead of only when nearly full (27 slots × 64-stacks ≈ 50 trees — why the owner's
    storage chest stayed empty). Reminder: villagers must have *seen* the chest (within 8
    blocks, line of sight) to know it exists.
- Findings while testing:
  - The GameTest framework encases each running test in a **barrier cage**. Barriers are
    motion-blocking (they cap the heightmap — the cage ceiling, not the tree, is the
    `MOTION_BLOCKING_NO_LEAVES` top) but have an **empty visual shape** (line-of-sight rays
    pass through), so scan-based tests still need their own batches. The scanner now descends
    up to 10 blocks below the heightmap surface to find trunks, which also finds real trees
    under overhangs; LOS still gates what counts as "seen".
  - Honest LOS has teeth: a trunk wrapped in leaves at eye level is invisible. Vanilla trees
    have bare lower trunks, so this only bites artificial/dense shapes.
  - Phase 1's `does_not_walk_to_non_food_items` was racy (a random idle stroll can walk the
    villager onto the item, and contact pickup is allowed); replaced with a direct test of
    the seek gate (`SeekFoodItemBehavior.findNearestFood`).

## Owner requests folded into this phase (2026-07-09)

- **Death drops**: killing a villager must drop *everything* it carries (vanilla 8-slot +
  extra 19-slot). Vanilla silently deletes the villager inventory on death — that violates
  design rule "no item deletion". Implemented via `LivingDropsEvent` (milestone 2.0).
- **Debug GUI**: a in-game panel to inspect/control a villager without typing commands.
  Debug wand item (right-click a villager, permission level 2) opens a client screen showing
  hunger, role, task queue, memory counts, and the combined inventory, with buttons for the
  common debug actions (set role, clear tasks, deposit, pickup, replan, hunger +/-).
  Villager state is server-side (data attachment), so this needs custom payloads; the GUI is
  a plain `Screen` fed by snapshots, not a menu/container (nothing is interactive per-slot).
- **Torch placement** (requested 2026-07-09, already in CLAUDE.md): milestone 2.2.

## Architectural decisions

1. **Roles, not professions.** A gathering role (`LUMBERJACK`, later `MINER`/`FARMER`) is a
   Dynamic Villagers concept stored on the `VillagerEssence` attachment — vanilla professions,
   trades, and brain schedules stay untouched, so Guard Villagers conversion and Thief
   reputation keep working. No custom profession/POI block registration in Phase 2 (that would
   force job-site blocks and fight the vanilla job system). Roles are assigned by command/GUI
   now; the village manager assigns them in Phase 5.
2. **The planner decides *what*, the task queue does *how*** (CLAUDE.md two-layer rule).
   One new Brain behavior, `PlanWorkBehavior` (CORE, same injection point as Phase 1): when a
   villager has a role, an empty task queue, daylight, and no PANIC/RAID/REST activity, it
   asks the role's `RolePlanner` for the next work cycle and enqueues ordinary Phase 1 tasks.
   Planning is throttled (short cooldown after a successful plan, long backoff after a failed
   one) so scans never run every tick.
3. **Perception before action.** Villagers only chop/mine what they have seen: resource scans
   do line-of-sight checks (same `ClipContext.Block.VISUAL` rule as container perception) and
   found spots go into `VillagerMemory` (new generic named-spot storage next to the Phase 1
   container memory, capped per kind, forget-on-invalid).
4. **Tools are sourced, not conjured.** A gatherer that lacks its tool looks through
   remembered containers (`TakeItemsTask` walks to each and searches inside — knowing a chest
   exists is not knowing its contents). No tool → work continues bare-handed at player speed
   (players punch trees too). Tool *crafting* is out of scope until the economy phases.
5. **Item filters as strings.** Task parameters that select items (`"axe"`, `"sapling"`,
   `"food"`, `"item:<id>"`, `"tag:<id>"`, `"any"`) parse via `ItemFilter` into predicates and
   serialize as plain strings, so every task stays NBT-round-trippable.

## Milestones

### 2.0 Gathering framework  *(this session)*
- `VillagerRole` on the essence + `/dv role <villager> <role>` + shown in `/dv inspect`.
- `PlanWorkBehavior` + `RolePlanner` registry (decision #2).
- `VillagerMemory` generic spots: `rememberSpot(kind, pos, time)` etc., serialized, cap 16/kind.
- `TakeItemsTask(filter, count)`: visit remembered containers nearest-first, withdraw matches.
- `DepositToContainerTask` gains keep-filters and now empties the **combined** inventory
  (default keeps food — villagers don't dump their lunch).
- `PlaceBlockTask` gains an item filter + a `canSurvive` check (saplings only go on soil).
- Death drops (`LivingDropsEvent`, see owner requests).
- Debug wand + GUI (see owner requests). Networking: `RegisterPayloadHandlersEvent`,
  versioned registrar, S2C state snapshot + C2S action payloads; screen polls ~1/s while open.

### 2.1 Lumberjack  *(this session)*
- `TreeScanner`: heightmap columns (`MOTION_BLOCKING_NO_LEAVES`) in radius 16 → descend to
  trunk base → BFS the connected log cluster (26-neighborhood, cap 24 logs). A cluster is a
  *choppable tree* only if: it has adjacent **non-persistent** leaves (log piles and cabins are
  not trees), horizontal spread ≤ 3 from the base, and height ≤ 5 above the base (what a
  villager can reach from the ground — taller trees need Phase 4 scaffolding, skipped for now).
  Line of sight to base or crown required at discovery time.
- `ChopTreeTask`: fell bottom-up through the existing `BreakBlockOrder` (tool selection,
  mining speed, durability, crack animation all inherited).
- Work cycle from `LumberjackPlanner`: inventory nearly full → deposit (keep axe/saplings/food);
  else find tree (memory first, scan on miss) → optionally fetch axe (cooldown-limited) →
  chop → pick up drops → replant a sapling at the stump (best-effort: needs a carried sapling
  and soil). Leaf-decay drops are gleaned opportunistically by the Phase 1 auto-pickup.

### 2.2 Torch placement (owner request) — *done*
- Scan for spots near the villager/village with block light 0, solid ground, and air above;
  place torches from inventory (`PlaceBlockTask` with `item:minecraft:torch`), fetch torches
  from containers like tools. Runs as a low-priority chore for any roled villager when the
  planner has nothing better. Keeps hostile spawns down around the settlement.

### 2.3 Farming improvements — *done*
- `FARMER` role: deposit surplus produce (keep seeds + food), till reachable dirt near
  existing farmland with a carried hoe, plant carried seeds. Must not fight the vanilla farmer
  brain (vanilla harvest/replant continues to run; our planner only adds hauling and expansion).

### 2.4 Miner — exposed resources — *done*
- `MINER` role: perception scan for surface-exposed stone/ores (cliff faces, cave mouths)
  with LOS; mine remembered spots with pickaxe sourcing, deposit. Ore blocks recognized by
  tag (`minecraft:iron_ores` etc. + stone). No tunneling yet.

### 2.5 Strip mines — *done*
- Command-designated mine start (`/dv minesite`) → villager digs a 1×2 tunnel segment by
  segment. **Hazard rule**: never break a block with fluid on any side of it. **Darkness
  rule** (owner request): the face must be lit ≥ 8 or the miner places a carried torch
  first — no torches means no deep digging. Ladder shafts moved to 2.6.

### 2.6 Quarry — *done*
- Designated rectangular pit dug layer by layer with a walk-out staircase along one wall
  (see status notes: ramp instead of ladders, dig-then-repair stairs). Produces bulk stone
  for Phase 4 construction. Bridge construction deferred to Phase 4.

### 2.7 Phase gate
- Perf: 50 villagers with active gathering roles on a dedicated server — measure tick cost of
  planner scans and chop ticks; budget or stagger if needed.
- Coexistence re-run with Guard Villagers + Thief loaded (all gametests + manual dev-client
  session: convert a lumberjack to guard mid-chop, steal from a villager mid-cycle).
- Docs updated with findings.

## Open questions / deferred

- **Tall trees** need pillar-up scaffolding (place block under self, dig down after) — that
  mechanic belongs to Phase 4 construction; pull it forward only if short-tree-only chopping
  proves too limiting in real worlds.
- **Sapling type matching**: replant currently accepts any sapling (`tag` filter); exact
  wood-type matching is a polish item.
- **Chest contents knowledge**: villagers search containers by walking to them. Phase 3's
  storage network ("builders know where supplies are") will remember contents after a visit.
- **Double chests** are treated as two single containers for now.
- **GameTest isolation**: scan-based lumberjack tests run in their own gametest batches so a
  villager can't see a neighboring test's tree (scan radius 16 > grid spacing).

## Explicitly out of scope for Phase 2

Blueprint construction (Phase 4), storage reservation/requests (Phase 3), wages/economy
(Phase 6), auto role assignment (Phase 5), guard behavior changes (Phase 8), crafting.
