# Phase 4 Plan — Construction

Goal (from CLAUDE.md): **villages finally grow.** A blueprint system and a Builder role that
physically constructs buildings block by block — foundation laying, walls, roofs, paths,
farms, repair of damaged buildings, and dirt scaffolding to reach high blocks. The roadmap's
named features map onto this plan as follows: *blueprint system* (4.0), *house/wall/roof
construction* (4.1–4.5 — walls and roofs are not separate systems, they are what building a
blueprint bottom-up looks like), *foundation laying* (4.2), *farm construction* (4.4),
*dirt scaffolding* (4.5), *path building* (4.6), *repair damaged buildings* (4.7).

Status: **4.0–4.2 complete; 4.4 core + 4.5 implemented** (2026-07-10) — full gametest suite
green with Guard Villagers + Thief loaded. The owner's first real house build (vanilla
plains small house) succeeded end-to-end minus door/bed, which drove 4.4's multi-part work
forward. Implemented out of milestone order because playtests hit them: 4.4 atomic door/bed
placement + doors-last ordering (farmland/water and the vanilla-house completion gametest
still open), 4.5 reachability-aware batching + dirt/cobble staircase scaffolding with
teardown. 4.2 landed after: `SiteValidator` refuses overlapping sites and footprints with
no ground within `MAX_FOUNDATION_DEPTH` (marker + `/dv build add`, `force` overrides), and
builders pillar hanging bottom-layer columns down to sturdy ground with dirt/cobble before
and while walls rise (clear work already fell out of the 4.1 diff). Owner decisions along
the way: builders are **assigned-only** (no adopting open sites until Phase 5), and the
profession-integration design is recorded in CLAUDE.md (research complete — see
docs/RESEARCH_PROFESSIONS.md). 4.3 landed too: builders keep a **staging chest** real
(place a carried one on the ring outside the footprint, fetch one, or fall back to public
storage), post one capped request per material kind the network cannot supply (tracked on
the site, cancelled at DONE and by `/dv build cancel`), and `/dv build staging <id> <pos>`
points requests at a chest of your choice. Still open: rest of 4.4 (farmland/water +
vanilla-house completion gametest), 4.6 paths, 4.7 repair, 4.8 gate, two-builder sharing
gametest. Findings so far:
- A builder clearing its own line of sight (mining a just-placed block to see a farther
  target) can strand material drops; the planner now **gleans matching drops inside the
  site bounds** before walking to storage. Proper attachable/interior ordering (4.4) and
  scaffold standing spots (4.5) will make this rarer.
- Free dependent halves (door upper, bed head) place without consuming an item in
  `PlaceStateOrder`, so vanilla houses are largely constructible before 4.4 — 4.4 is
  ordering polish (door last, interior first) plus farmland/water, not a prerequisite for
  walls-and-roof builds.
- Owner playtest (2026-07-10): a builder blocked on materials fell through to `TorchChore`
  and spent the site's own torches lighting the village while the fetch cooldown ticked.
  Rules now: an open site suppresses all chore fallbacks (blocked = wait, don't improvise);
  the fetch cooldown is only consumed when a fetch/glean is actually enqueued; and one
  storage trip tops up every material on the blueprint's bill instead of one kind per trip.
- Reload robustness: `TaskTypes.load` drops an unloadable task instead of failing the whole
  essence attachment; a gametest simulates quit+rejoin for a mid-build villager including
  the debug-wand snapshot round-trip (the owner's dead-wand report was most likely
  collateral of the pre-fix save crash leaving that world half-saved).
- Owner playtest round 2 (2026-07-10): a batch needing any single unavailable material
  stalled the whole site ("opens a chest, then forgets his job" — visible as one chest
  visit per fetch cooldown and idle wandering between). Batches are now **partitioned**:
  entries whose materials are in hand build immediately, only the short entries wait for
  restocking; gametest `builder_restocks_and_builds_around_missing_materials`.
- Cosmetic fix from the same round: when a just-placed block obstructed the line of sight
  to the next target, the builder started mining its own wall. `PlaceStateOrder` now
  sidesteps (walks perpendicular to the sight line, alternating sides) for up to 3 seconds
  before falling back to clearing — mining your own work is the last resort, not the first.

## What Phases 1–3 already give us

- **WorkOrder physics** (`BreakBlockOrder`/`PlaceBlockOrder`): reach, look-at, obstruction
  clearing, tool speed and durability, drops. All construction block work reuses this layer.
- **Task queue + persistence** and the planner pattern: `PlanWorkBehavior` asks a
  `RolePlanner` for the next work cycle only when the queue is empty. The Builder is just
  another planner.
- **The storage network**: ledger knowledge of contents, reservations, `TakeItemsTask`'s
  reserve-walk-verify path, and the `MaterialRequest` board with `DeliverItemsTask` and
  gatherer produce redirection. Phase 3 explicitly built the request board for construction
  sites to be its first real customer — the gatherer side of "feed the build site" already
  works with zero new code.
- **Site designation pattern**: `SiteMarkerItem` subclasses + `/dv` commands.
- **Batch-against-world planning** (quarries): plan a bounded batch per cycle by inspecting
  actual world state instead of storing per-block progress. Construction generalizes this.

Gaps Phase 4 must fill:

- `PlaceBlockOrder` places the `defaultBlockState()` of whatever item matches a filter —
  fine for torches and cobblestone, wrong for stairs (facing), logs (axis), slabs (half),
  doors and beds (two-part). It also treats "spot already occupied" as success, which is
  wrong when the occupant is the *wrong* block.
- Work sites live per-villager in `VillagerEssence` (mine/quarry). Buildings must outlive
  any single villager and support several builders — they need village-level storage.
- Nothing reads structure templates; there is no notion of "what a finished building is".
- Reach is 4 blocks from the eyes — a second story is unbuildable without scaffolding.

## Architectural decisions

1. **Blueprints are vanilla structure templates, read as data — and the houses ARE the
   vanilla houses.** Owner directive (2026-07-10): villages build the vanilla village
   structures (wiki: Village/Structure/Blueprints), loaded straight from
   `minecraft:village/...` templates in the game jar — we do not design our own houses.
   Any `.nbt` reachable through `StructureTemplateManager` works (vanilla's, ours, a data
   pack's), so mod-authored templates remain as gametest fixtures and debug scaffolding
   only (`starter_shelter`). We never call `placeInWorld`; a `Blueprint` parses the
   template into an ordered block list and villagers place every block by hand.
   Verified against `plains_small_house_1` (7×7×7, 343 entries): jigsaws carry full
   `final_state` strings (`oak_stairs[facing=east,...]`), doors/beds are two-part,
   wall torches map back to the torch item, and glass panes / stair `shape` properties
   self-correct via neighbor updates once the surroundings match the template.
   Because `StructureTemplate.palettes` is private (verified against 21.1.235 sources; see
   RESEARCH.md), parsing goes through the public `template.save(CompoundTag)` round-trip —
   the structure NBT format (size / palette / blocks) is stable and documented.
   Normalization at parse time: jigsaw blocks become their `final_state`, `structure_void`
   entries are skipped (don't-care positions), `waterlogged` is stripped, entities are
   ignored, only the first palette is used. `Rotation` is supported (positions via
   `StructureTemplate.transform`, states via `BlockState.rotate`); `Mirror` is not.

2. **Construction sites are village-level SavedData.** New `ConstructionLedger` (sibling of
   `StorageLedger`, one per level, `"dynamicvillagers_construction"`), holding
   `ConstructionSite` records: id, template id, origin, rotation, staging container,
   scaffold positions, per-block claims, status. Queries are distance-gated by
   `NETWORK_RANGE` around the villager's anchor, exactly like storage — real village
   ownership arrives with Phase 5, which will also make the village manager (not the
   player) post sites. Until then: `/dv build` commands + a creative Building Marker.

3. **Progress is verified against the world, never stored per block.** Each planning cycle
   diffs a bounded slice of the blueprint against actual world state (a cursor makes the
   scan incremental; a budget caps blocks-per-cycle like the quarry batch cap). Whatever
   doesn't match the plan *is* the remaining work, in build order. This is idempotent —
   it survives reloads, interrupted tasks, and two builders racing — and it makes repair
   (roadmap feature) the same code path: a finished building that stops matching its
   blueprint is simply unfinished again. No stored phase machine: clear work, foundation
   work, layer work, and finishing work are found in priority order by the same diff.

4. **A new `PlaceStateTask` places exact block states.** Reuses the WorkOrder physics
   (move into reach, look, obstruction clearing) but: target is a specific rotated
   `BlockState`; wrong occupant → break it first, then place; consumes the item mapped by
   a `BlockRequirements` resolver (default `block.asItem()`, with a specials table);
   refuses to place a block that would collide with an entity (players can't entomb
   themselves either). Entries whose support block is missing (`canSurvive` fails — wall
   torches, ladders) are deferred and picked up by a later diff cycle. Multi-part specials:
   a door is two halves for one door item, a bed is foot+head for one bed item, a double
   slab is one state for two items, farmland is `TillSoilTask` on placed dirt, a water
   source is a carried water bucket emptied into the hole (bucket returned).

5. **The site is the request board's first real customer.** The builder self-serves
   materials that already exist in the network (`TakeItemsTask` ledger path, with the
   builder's keep-list covering active-site materials so eager deposits don't dump them).
   Computed shortfalls post `MaterialRequest`s with deliver-to = the site's **staging
   container** (designated at the site, or a chest the builder places beside the site when
   it can get one, or nearest public storage as fallback). Phase 3 already redirects
   matching gatherer produce and idle haulers to request destinations — the supply chain
   lumberjack → staging chest → wall exists the day requests are posted. Requests are
   refreshed/capped per site and cancelled with it.

6. **Build order: clear → foundation → layers bottom-up → finishing, door last.** Within a
   layer, structural blocks before attachables. The doorway stays an open gap until
   finishing so a builder working the interior always has an exit; interior attachables
   (bed, torches) go in before the door; the door is placed last, from outside. Scaffold
   blocks are temporary dirt, recorded on the site, and torn down top-down after the work
   that needed them — the village does not live with construction mess.

7. **No crafting in Phase 4 core.** The owner's smelting directive already slates
   crafting/smelting for the crafting/economy phases, so builds draw on gathered materials
   (logs, cobblestone, dirt) plus whatever crafted goods (planks, doors, beds, torches)
   sit in village storage — player-stocked for now. Starter blueprints are chosen to be
   buildable this way and avoid glass entirely (needs smelting). A scoped "craft
   construction materials at a crafting table" milestone is flagged as an owner decision
   in Open Questions — without it, wooden builds depend on a stocked warehouse.

## Milestones

### 4.0 Blueprint + construction ledger foundation
- `construction/Blueprint`: parsed, normalized template — ordered `PlannedBlock(relPos,
  state)` list, size, rotation transform, aggregate item requirements. `Blueprints` cache:
  load by ResourceLocation via the template manager (save-round-trip per decision 1), or
  from raw NBT (gametests).
- `construction/BlockRequirements`: state → item prototype + count resolver with the
  specials table (air → nothing; door/bed halves → one item on the primary part; double
  slab → two; farmland → dirt; water → bucket).
- `village/ConstructionLedger extends SavedData` + `ConstructionSite` (decision 2), CRUD,
  nearest-open-site query, claim map with TTL, `clear()` for gametest isolation.
- `/dv build add <template> [rotation]` (at the targeted position), `list`, `cancel <id>`,
  `info <id>` (requirements vs. current ledger availability — the shortfall report).
- Test fixture `starter_shelter.nbt` (generated by `tools/gen_structures.py`): planks/log
  shelter with slab roof, doorway gap, floor torch — deliberately no specials so the 4.1
  builder can finish it deterministically in gametests. NOT village content (decision 1);
  real builds load `minecraft:village/...` templates.
- GameTests: blueprint parsing (size, block count, air-means-clear entries), rotation
  correctness, requirements math, jigsaw/void normalization (from constructed NBT), a
  vanilla village house parses cleanly, ledger persistence round-trip.

### 4.1 Builder role MVP — builds the shelter on flat ground from stocked storage
- `VillagerRole.BUILDER` + `BuilderPlanner` registered like the other roles.
- `PlaceStateTask` per decision 4 (minus multi-part specials, which land in 4.4; the
  dependent halves of doors/beds place for free once their paying half exists, so vanilla
  houses are already mostly constructible before 4.4 polishes ordering).
- Debug entry points (owner request, 2026-07-10): `/dv build assign <villager> <site>`
  (forces BUILDER role + pins the villager to that site) and the creative **Building
  Marker** (bind a villager, click the ground → site posted there and the villager
  assigned; sneak-click cycles rotation; template from item NBT, default is a vanilla
  plains house).
- Planner cycle: find nearest open site → diff next slice (decision 3) → carried materials?
  enqueue place/break batch : fetch from network (per-item filters, needed counts) : back
  off / fall through to `RequestChore`/`TorchChore` like other roles. Keep-list = tools +
  food + active-site materials.
- Per-block batch claims so two builders split a site instead of racing it.
- Site completes when a full diff pass finds no mismatch (status DONE).
- GameTests: shelter built correctly from a stocked chest (assert exact final states,
  incl. a rotated site); two builders share one site; mid-build reload resumes cleanly.

### 4.2 Site preparation & foundation laying
- Site validation at `add`: bounding box overlap with other sites, ground coverage,
  bounded foundation depth (refuse cliff-edge/floating/underwater sites with a clear
  message; creative override flag).
- Clear pass from the same diff: plan-wants-air positions and replaceables (grass, leaves)
  in the box → break + pick up drops.
- Foundation pass: each footprint column pillars down from template bottom to the first
  sturdy ground with foundation material (dirt/cobblestone filter), bounded depth.
- GameTests: sloped arena gets a filled foundation; a tree inside the footprint is felled
  and cleared before walls rise.

### 4.3 Staging container & material requests
- Staging container per decision 5: designate via command/marker click on a chest, or the
  builder places a carried/fetched chest at a computed spot beside the site; nearest
  public storage as fallback deliver-to.
- Shortfall math per cycle → post/refresh capped `MaterialRequest`s; cancel with the site;
  consume from staging first when building.
- GameTests: a site missing planks posts the right request; stock elsewhere in the network
  arrives at staging via existing hauling; a lumberjack's logs redirect to the site
  (Phase 3 behavior asserted end-to-end against a real site).

### 4.4 Special placements & finishing — the farm completes
- Specials per decision 4: doors (two halves, one item, placed last from outside), beds
  (foot+head, orientation), wall torches/ladders (support-deferred), double slabs,
  farmland (till placed dirt via existing `TillSoilTask`), water source via carried
  water bucket (empty bucket kept).
- Finishing order per decision 6 wired into the diff ordering.
- Proof templates are vanilla (decision 1): `minecraft:village/plains/houses/
  plains_small_house_1` completes end-to-end from stocked storage, and a vanilla farm
  piece (e.g. `village/plains/houses/plains_small_farm_1`) builds its farmland + water.
  Builders do NOT plant crops — the existing `FarmerPlanner` sees finished farmland and
  takes over (emergent cross-role handoff).
- Vanilla-template quirks to handle here: grass-block floor entries (place dirt, accept
  grass regrowth as matching — dirt/grass equivalence in the diff), `structure_void`
  padding (already skipped), glass panes until smelting exists (stocked storage only —
  `/dv build info` shows the shortfall).
- GameTests: door consumes one item and stands as two correct halves; bed orientation;
  wall torch placed after its wall; the plains small house completes; a farm piece ends
  planted by a farmer.

### 4.5 Dirt scaffolding — the second story
- Reachability check in the diff: a target with no standable position within reach →
  plan a dirt scaffold column under the best adjacent standing spot; scaffold positions
  recorded on the site; teardown top-down once the blocks needing them match plan, dirt
  picked back up. Scaffold dirt is fetched like any other material.
- `two_story.nbt` test template.
- GameTests: second-story walls and roof complete; zero scaffold blocks remain; the dirt
  came from and returns to the village (inventory or storage).

### 4.6 Path building
- `PathSite` on the ledger: waypoint polyline (command/marker), width 1 to start.
- Terrain-following, not a template: per column along the line — clear low obstructions,
  fill 1-deep gaps with dirt/cobblestone, then convert the surface to dirt path with a
  carried **shovel** (the vanilla right-click interaction, with durability), or place
  gravel/planks where conversion doesn't apply. Builders need a shovel like miners need
  a pickaxe.
- GameTests: a continuous, walkable path across a bumpy arena; shovel loses durability.

### 4.7 Repair damaged buildings
- DONE sites keep their blueprint reference. Idle builders run a budgeted integrity
  cursor over nearby DONE sites; any mismatch flips the site back to open — everything
  else (requests, fetching, placing, scaffolding) is the existing machinery per decision 3.
- GameTests: a hole blown in a finished wall is re-fetched-for and repaired; an untouched
  building generates no work.

### 4.8 Phase gate
- Perf: benchmark with builders + active sites on top of the standard 50-villager
  scenarios; document diff-scan budget cost; no regression against Phase 3 numbers.
- Full gametest suite green with Guard Villagers + Thief loaded.
- Manual playtest checklist: shelter/hut/farm end-to-end from a stocked warehouse, request
  flow visible in chests, scaffold teardown leaves no mess, repair after creeper damage,
  path across terrain, existing roles undisturbed.
- Docs updated with findings; CLAUDE.md pointer moved to Phase 5.

## Open questions / deferred

- **Construction crafting (owner decision).** Without crafting, every plank/door/bed/torch
  comes from player-stocked storage. A scoped milestone — builder walks to a crafting
  table and crafts only what the active site's shortfall needs, via the vanilla
  `RecipeManager` (logs→planks→sticks, doors, beds?, torches from coal the miners already
  dig) — would make villages genuinely self-building one phase early. Flag for the 4.8
  playtest conversation; default per decision 7 is out.
- **Water buckets** (4.4) are the first non-block placement — if it feels wrong in play,
  the fallback is farm templates whose water hole must be dug to existing groundwater.
- **Curating the vanilla catalog**: which of the vanilla village pieces (per biome —
  plains/desert/savanna/taiga/snowy) villages may pick, and their footprint/material
  metadata, becomes the Phase 5 expansion catalog. Phase 4 only has to build any one of
  them correctly when told to.
- **Site preview rendering** (ghost outline of a pending site) — client polish, not
  needed for function; revisit with the Phase 5 village inspector UI idea.
- **Multi-builder crews** beyond batch claims (dedicated hauler vs. placer division of
  labor) — Phase 5/6 territory once the village manager assigns jobs.
- **Pathfinding into half-built shells**: navigation updates as blocks appear; the 2.6
  give-up timers bound the damage if a builder walls off its own path mid-batch. Watch in
  the 4.8 playtest.
- **Double-chest staging** inherits the known Phase 3 double-chest seam (two records).

## Explicitly out of scope for Phase 4

Deciding *what/where* to build (Phase 5 village manager — sites are player/command-posted
this phase), housing assignment and population growth (Phase 5), wages or paying builders
(Phase 6), defensive walls/gates/watchtowers (Phase 8 — the blueprint system will serve
them), entity placement from templates (item frames, armor stands), `Mirror` transforms,
glass and anything else requiring smelting, and non-walking material transport.
