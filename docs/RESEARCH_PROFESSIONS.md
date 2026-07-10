# Research Notes — Vanilla Villager Professions & Job-Site POIs

Collected 2026-07-10 from the decompiled 1.21.1 / NeoForge 21.1.235 sources (the
`sourcesAndCompiledWithNeoForge_*_output.jar` in the neoformruntime cache). Every claim is
cited to `Class#member`. Background on the villager Brain, POI system, and the CORE-inject
pattern is in docs/RESEARCH.md — read that first.

Goal this supports: unify the mod's `VillagerRole` with vanilla `VillagerProfession` so that
**setting a role makes the villager walk to and claim the nearest free matching job-site block,
gaining the profession the vanilla way** (never force-set without a claimed POI), and **a
villager that naturally claims a mapped job site auto-gains the mod role**. Mapping:
Farmer↔Farmer, Miner↔Toolsmith, Lumberjack↔Fletcher, Builder↔Mason.

---

## 0. Role↔Profession↔POI↔Block mapping (Q5)

`VillagerProfession` (`net.minecraft.world.entity.npc.VillagerProfession`) fields, each bound to
a `PoiTypes` key; the block is from `PoiTypes#bootstrap`. All four job POIs are `maxTickets = 1,
validRange = 1` (`PoiTypes#register(..., 1, 1)`), i.e. **one villager per block**, must stand
adjacent to work.

| Mod `VillagerRole` | `VillagerProfession` | `PoiTypes` key | Block (`PoiTypes#bootstrap`) |
| --- | --- | --- | --- |
| `FARMER`    | `VillagerProfession.FARMER`    | `PoiTypes.FARMER`    | `Blocks.COMPOSTER` (line 113) |
| `MINER`     | `VillagerProfession.TOOLSMITH` | `PoiTypes.TOOLSMITH` | `Blocks.SMITHING_TABLE` (line 120) |
| `LUMBERJACK`| `VillagerProfession.FLETCHER`  | `PoiTypes.FLETCHER`  | `Blocks.FLETCHING_TABLE` (line 115) |
| `BUILDER`   | `VillagerProfession.MASON`     | `PoiTypes.MASON`     | `Blocks.STONECUTTER` (line 118) |
| `NONE`      | `VillagerProfession.NONE`      | — | — |

**`heldJobSite` vs `acquirableJobSite`** (`VillagerProfession` record, fields 2 & 3): for all four
of our professions the two predicates are **identical** — `p -> p.is(<PoiTypes key>)` — because
each is created through `VillagerProfession#register(String, ResourceKey<PoiType>, SoundEvent)`
(line 61) or the 5-arg farmer form (line 71), both of which pass the same `is(jobSite)` lambda for
both slots. Semantics:
- `heldJobSite` — used to **validate an already-claimed** `JOB_SITE` still matches the profession
  (`Villager.POI_MEMORIES` for `JOB_SITE`, `Villager.java:186`; `PoiCompetitorScan`;
  `ValidateNearbyPoi` at CORE prio 0 for `JOB_SITE`).
- `acquirableJobSite` — the candidate filter passed to `AcquirePoi` when **seeking** a job
  (`VillagerGoalPackages#getCorePackage`, prio 6).
- `VillagerProfession.NONE.acquirableJobSite() == VillagerProfession.ALL_ACQUIRABLE_JOBS`
  (`VillagerProfession.java:33-34`) = `p -> p.is(PoiTypeTags.ACQUIRABLE_JOB_SITE)`
  (`PoiTypeTags.java:8`). That tag holds **all 13 profession job POIs**, so an *unemployed* (NONE)
  villager will grab **any** job type it can reach — **not** necessarily ours. This is the single
  most important gotcha for the role→profession direction (see Q3/Q8).
- `Villager.POI_MEMORIES` `POTENTIAL_JOB_SITE` predicate is also `ALL_ACQUIRABLE_JOBS`
  (`Villager.java:187-188`), used by the CORE-prio-0 `ValidateNearbyPoi` for `POTENTIAL_JOB_SITE`.

---

## 1. The vanilla employment pipeline (Q1)

All job-seeking behaviors live in **`Activity.CORE`** (`VillagerGoalPackages#getCorePackage`,
`Villager#registerBrainGoals:243`), so they run every tick regardless of the day-schedule
activity. The `WORK` activity (which contains `WorkAtPoi`/`WorkAtComposter`) is gated on
`JOB_SITE` present: `Villager#registerBrainGoals:236-240`
(`addActivityWithConditions(WORK, ..., {JOB_SITE: VALUE_PRESENT})`). So a villager can *seek* a job
any time but only *works* once it holds a `JOB_SITE`.

Ordered chain (priorities from `getCorePackage`):

1. **Discover + claim (prio 6)** — `AcquirePoi.create(profession.acquirableJobSite(),
   MemoryModuleType.JOB_SITE, MemoryModuleType.POTENTIAL_JOB_SITE, /*onlyIfAdult*/ true,
   Optional.empty())` (`getCorePackage:43`). This is the 5-arg overload
   (`AcquirePoi.java:38`) where `existingAbsentMemory = JOB_SITE`, `acquiringMemory =
   POTENTIAL_JOB_SITE`. It runs **only when `JOB_SITE` is absent** (outer `.absent(existingAbsentMemory)`
   wrap, `AcquirePoi.java:104-106`) **and `POTENTIAL_JOB_SITE` is absent** (inner
   `.absent(acquiringMemory)`, line 50). Behaviour (lines 54-99):
   - baby short-circuits (`onlyIfAdult && isBaby`, line 54);
   - throttled ~every few ticks with 20-tick jitter (`mutablelong`, lines 56-62);
   - `PoiManager#findAllClosestFirstWithType(acquirablePois, retryPredicate, blockPosition(), 48,
     Occupancy.HAS_SPACE)` (line 76) — **48-block scan**, closest-first, only POIs with a free
     ticket; `.limit(5)`;
   - pathfinds to the set (`findPathToPois`, line 81/110); if reachable,
     **`PoiManager#take(acquirablePois, (t,p)->p.equals(target), target, 1)`** acquires the ticket
     (line 85) **and writes `GlobalPos` into `POTENTIAL_JOB_SITE`** (line 86), plus happy-particle
     event if configured. Unreachable candidates go into a jittered-retry backoff map (lines 91-96).
   - **NB: it writes `POTENTIAL_JOB_SITE`, not `JOB_SITE`** — there is no separate sensor writing
     `POTENTIAL_JOB_SITE`; `AcquirePoi` (and `YieldJobSite`) are the only writers.

2. **Walk to it (prio 7)** — `GoToPotentialJobSite(speedModifier)` (`getCorePackage:44`,
   class `GoToPotentialJobSite.java`). Start-gated by `checkExtraStartConditions`: the active
   **non-core** activity must be `IDLE`, `WORK`, or `PLAY` (lines 25-30) — i.e. not during
   PANIC/RAID/REST/MEET. `tick` sets walk+look toward `POTENTIAL_JOB_SITE` (lines 36-40).
   `TICKS_UNTIL_TIMEOUT = 1200`; on `stop` (timeout or memory gone) it **releases the POI ticket**
   (`poimanager.release`, line 50) **and erases `POTENTIAL_JOB_SITE`** (line 56). So a claim that
   can't be reached within 60 s is surrendered.

3. **Hand-off (prio 8)** — `YieldJobSite.create` (`getCorePackage:45`, `YieldJobSite.java`). Only
   for a **NONE-profession** villager holding `POTENTIAL_JOB_SITE` but no `JOB_SITE` (lines 33-36):
   if a nearby villager already *has that profession* but lost its site
   (`nearbyWantsJobsite`, lines 69-82 — matches `heldJobSite`, no potential/job of its own), the
   seeker **gives up** its potential site (erases its memories) and pushes the site to that other
   villager. Relevant edge case, not the main path.

4. **Assign profession (prio 10)** — `AssignProfessionFromJobSite.create()` (`getCorePackage:48`,
   `AssignProfessionFromJobSite.java`). Requires `POTENTIAL_JOB_SITE` present + `JOB_SITE`
   registered (line 19). Fires only when the villager is **within 2.0 blocks** of the potential
   site, `!globalpos.pos().closerToCenterThan(position, 2.0)` unless `assignProfessionWhenSpawned()`
   (line 24). Then: erase `POTENTIAL_JOB_SITE`, **set `JOB_SITE` = that pos** (lines 27-28),
   broadcast event 14 (happy particles). Then:
   - if `getVillagerData().getProfession() != NONE` → **keep** existing profession, just re-homed
     the job site (lines 30-31);
   - if `NONE` → find the profession whose `heldJobSite().test(poiType)` matches the POI at that
     pos and **`setVillagerData(getVillagerData().setProfession(p))` + `refreshBrain`**
     (lines 33-45). **This is the only vanilla path that grants a profession, and it requires a
     physically claimed, walked-to POI** — exactly the "never force-set" property we want.

5. **Reset (prio 10)** — `ResetProfession.create()` (`getCorePackage:49`) — see Q2.

Also always in CORE:
- **prio 0** two `ValidateNearbyPoi` (`getCorePackage:37-38`): one validating `JOB_SITE`
  against `heldJobSite`, one validating `POTENTIAL_JOB_SITE` against `acquirableJobSite`. Erases
  the memory when the block within 16 blocks no longer matches (Q2).
- **prio 2** `PoiCompetitorScan` (`getCorePackage:40`) — deduplicates two villagers on one site (Q3/Q8).

**Gates summary**: baby → cannot acquire (`AcquirePoi` onlyIfAdult) nor yield (`YieldJobSite:33`);
existing profession → `AcquirePoi` still re-acquires a matching site when `JOB_SITE` absent, but
`AssignProfessionFromJobSite` won't *change* a non-NONE profession; trades/XP → no gate on
gaining, but block loss on losing (Q2). Nothing about trades blocks acquisition.

---

## 2. Profession LOSS (Q2)

- **`ResetProfession`** (`ResetProfession.java:12-32`): runs when **`JOB_SITE` is absent**
  (line 14). Resets to `NONE` (+`refreshBrain`) **only if all of**: profession `!= NONE` and
  `!= NITWIT`, **`getVillagerXp() == 0`**, and **`getVillagerData().getLevel() <= 1`** (lines
  19-24). ⇒ **Any trade XP (>0) OR any level-up (>1) makes the profession permanent** even with no
  job site. A villager that has ever traded can **never** lose or change its profession through
  vanilla. (`Villager#getVillagerXp` = `villagerXp` field, `Villager.java:956`; XP accrues in
  `Villager#rewardTradeXp:613`; level via `increaseMerchantCareer:753`.)
- **Job-site block broken** → the POI record is dropped by `PoiManager#checkConsistencyWithBlocks`
  on block update; then within 16 blocks `ValidateNearbyPoi` (CORE prio 0,
  `ValidateNearbyPoi.java:28-29`) sees `!poiManager.exists(pos, heldJobSite)` and **erases the
  `JOB_SITE` memory**. `ResetProfession` may then clear the profession, subject to the XP/level
  rule above. A villager >16 blocks away keeps a stale `JOB_SITE` until it returns.
- **`Villager#releaseAllPois()`** (private, `Villager.java:664-669`) releases the HOME / JOB_SITE /
  POTENTIAL_JOB_SITE / MEETING **tickets**; called from `Villager#die:660` and
  `Villager#thunderHit:836` (witch conversion). **`Villager#releasePoi(MemoryModuleType)`** (public,
  `Villager.java:682-698`) releases one ticket iff the POI still matches its `POI_MEMORIES`
  predicate. **Note: releasing a ticket does NOT erase the brain memory** — that is
  `ValidateNearbyPoi`'s job. **There is no `onJobSiteRemoval` method in 1.21.1** (the name in the
  task prompt does not exist here).
- **`PoiCompetitorScan`** (`PoiCompetitorScan.java`): when two villagers hold `JOB_SITE` at the
  **same** pos with matching profession (`competesForSameJobsite:56-59`), the **higher-`villagerXp`**
  one keeps it and the loser's `JOB_SITE` memory is **erased** (`selectWinner:41-54`) — which can
  then trigger `ResetProfession` on the loser (XP/level rule applies).

---

## 3. Programmatically claiming a specific / nearest-free job site (Q3)

**Is writing `MemoryModuleType.POTENTIAL_JOB_SITE` (GlobalPos) enough?** Almost — vanilla will then
walk (`GoToPotentialJobSite`) and convert (`AssignProfessionFromJobSite`) — **but do not just set
the memory.** Do exactly what `AcquirePoi`/`YieldJobSite` do: **acquire the ticket first**, then set
the memory. Recommended one-shot (server side):

```java
PoiManager poi = serverLevel.getPoiManager();
Predicate<Holder<PoiType>> want = h -> h.is(PoiTypes.<TARGET>);        // e.g. FLETCHER for LUMBERJACK
Optional<BlockPos> target = poi.take(want, (t, p) -> true,            // nearest FREE + acquire ticket
                                     villager.blockPosition(), RADIUS); // one call = find + take
target.ifPresent(pos -> villager.getBrain().setMemory(
        MemoryModuleType.POTENTIAL_JOB_SITE, GlobalPos.of(serverLevel.dimension(), pos)));
```

`PoiManager#take` (`PoiManager.java:155-163`) filters to `Occupancy.HAS_SPACE`, applies the
type/pos predicate, and calls `acquireTicket()` on the first match — returning the claimed pos.
This finds the nearest free **specific** block and reserves it in one call.

**Pitfalls:**
1. **Specific vs any-job.** A NONE villager's own CORE `AcquirePoi` uses
   `ALL_ACQUIRABLE_JOBS` and will grab *any* job POI (composter, lectern, barrel…). To force a
   *specific* type you must claim it yourself (above). Setting `POTENTIAL_JOB_SITE` also **suppresses**
   the vanilla `AcquirePoi` (its `acquiringMemory = POTENTIAL_JOB_SITE` must be absent to run,
   `AcquirePoi.java:50`), so once you set it the villager won't wander off to a different type.
2. **maxTickets = 1.** All four POIs allow one villager. If already occupied, `take` returns empty —
   check the `Optional` before setting the memory; otherwise you'd write a memory for a full site and
   `AssignProfessionFromJobSite` could still assign it, leaving `PoiCompetitorScan` to evict the
   lower-XP claimant next tick.
3. **Competitor scan.** Even after a clean claim, if another villager ends up with `JOB_SITE` at the
   same pos, the higher-XP one wins (Q2).
4. **Reachability + distance.** `AcquirePoi` scans 48 blocks; `GoToPotentialJobSite` times out at
   1200 ticks and *releases* the claim if it can't path there. Prefer the nearest free site and a
   modest RADIUS; don't hand it an unreachable target.
5. **Proximity gate for assignment.** The profession is granted only after the villager physically
   reaches within 2.0 blocks (`AssignProfessionFromJobSite.java:24`). This is desirable (the "vanilla
   way") but means role→profession is **not instantaneous** — it completes once the villager arrives.
6. **Activity gate.** `GoToPotentialJobSite` only starts when the non-core activity is IDLE/WORK/PLAY
   (`GoToPotentialJobSite.java:25-30`). A panicking/raiding/sleeping villager won't walk there until
   it returns to a normal activity.
7. `AssignProfessionFromJobSite` does **not** itself verify a held ticket (it only reads
   `getPoiManager().getType(pos)`), which is why omitting `take` causes contention rather than an
   outright failure — but always `take` to be correct.

---

## 4. Detecting "profession changed" server-side (Q4)

**No NeoForge event exists.** Verified: the neoforge event tree in this jar contains only
`net.neoforged.neoforge.event.village.{VillagerTradesEvent, WandererTradesEvent, VillageSiegeEvent}`
and `event.entity.player.TradeWithVillagerEvent` — none fire on profession change. There is no
`VillagerProfessionChangeEvent` or similar in 21.1.235.

**Use a mixin on `Villager#setVillagerData(VillagerData)`** (`Villager.java:595-603`) — the single
funnel for all `VillagerData` mutations. It is called on **both** directions:
- gain: `AssignProfessionFromJobSite.java:43`;
- loss: `ResetProfession.java:23` (and indirectly after `PoiCompetitorScan` erases `JOB_SITE`);
- also: constructor (`Villager.java:202`), breeding→NONE and spawn-egg type
  (`finalizeSpawn:788,792`), **level-ups with profession unchanged** (`increaseMerchantCareer:754`),
  zombie-cure, `/summon` NBT.

`setVillagerData` itself already nulls `this.offers` when the profession differs
(`Villager.java:598-600`). Recommended inject:

```java
@Inject(method = "setVillagerData", at = @At("HEAD"))
private void dv$onDataSet(VillagerData data, CallbackInfo ci) {
    Villager self = (Villager)(Object)this;
    if (self.level().isClientSide) return;                       // entityData set runs both sides
    VillagerProfession oldP = self.getVillagerData().getProfession();   // HEAD → still the old value
    VillagerProfession newP = data.getProfession();
    if (oldP != newP) { /* sync VillagerEssence role ↔ newP */ }
}
```

Reliable for both gain and loss. Filter to `oldP != newP` (the same method fires for type/level
changes where profession is equal). At `HEAD`, `getVillagerData()` still returns the pre-change value.
`refreshBrain` (called right after by the two behaviors) rebuilds the brain and **re-runs our
`registerBrainGoals` TAIL inject**, so our CORE behaviors survive every profession change.

---

## 5. (covered in §0)

---

## 6. Trades after a POI-driven profession gain (Q6)

- A profession gained via `AssignProfessionFromJobSite` starts at **level 1** (`VillagerData`
  min level, `VillagerData.java:13,44`) with `villagerXp == 0`. `setVillagerData` clears
  `offers` when the profession changes (`Villager.java:598-600`); the next `getOffers()`/
  `updateTrades` (`Villager.java:882-899`) regenerates from `VillagerTrades.TRADES.get(profession)`
  at the current level (2 offers per level, `addOffersFromItemListings(..., 2)`). Leveling proceeds
  normally via `rewardTradeXp → shouldIncreaseLevel → increaseMerchantCareer → setLevel + updateTrades`
  (`Villager.java:611-624, 748-756`).
- **Our role sync must not touch `VillagerData`.** `VillagerEssence#setRole`
  (`VillagerEssence.java:75`) mutates only our attachment — no trade impact. Trades are only reset
  when we call `setVillagerData` with a *different profession*; the reverse-sync (jobsite→role) does
  not call it, and the forward-sync (role→jobsite) lets **vanilla** assign the profession, so vanilla
  owns the single reset. Never call `setProfession` directly for a villager that already has trade
  XP — vanilla wouldn't allow the change and it would wipe trades.

---

## 7. Interop cautions (Q7 — from docs/RESEARCH.md, mods not decompiled)

- **Guard Villagers**: our CORE-inject-at-`registerBrainGoals`-TAIL pattern is already the agreed
  workaround for GV's cancellable `VillagerGoalPackages` RETURN injection (docs/RESEARCH.md). The
  job-POI behaviors (`AcquirePoi`/`Assign`/`Reset`/`ValidateNearbyPoi`/`PoiCompetitorScan`) all live
  in vanilla CORE and GV does not touch job sites or `VillagerData`, so there is **no collision**.
  Converting a villager to a `guardvillagers:guard` removes the villager entity entirely (its
  attachment/role vanishes with it) — nothing to sync. **Do not force-set professions** — this both
  honours the owner's rule and avoids fighting GV/others over `VillagerData`.
- **Thief**: reputation is vanilla gossip (`GossipContainer`); profession change is orthogonal and
  does not read/write gossip. No collision. (Do not mixin `VillagerGoalPackages.get*Package` — GV
  cancels there; use the established TAIL inject.)
- **General**: `refreshBrain` runs on every profession change and rebuilds all activities, so keep
  our behaviors stateless per-build (already the case — new instances added in the TAIL inject).

---

## 8. Recommended implementation sketch (Q8)

**Where behaviors go.** Reuse the existing `com.dynamicvillagers.mixin.VillagerMixin`
(`@Inject(method="registerBrainGoals", at=@At("TAIL"))`, adds to `Activity.CORE`). Add a small
`Role↔VillagerProfession` table (a static map or a method on `VillagerRole`) using the §0 mapping.

**Forward: role set → seek job site.**
1. On `VillagerEssence#setRole` (or in a new CORE behavior that reads `essence.getRole()`), compute
   the target profession. If the villager already **has** that profession, do nothing.
2. Add a CORE behavior (server, adult-gated like vanilla) that, when the essence role maps to a
   profession the villager lacks **and both `JOB_SITE` and `POTENTIAL_JOB_SITE` are absent**, runs
   the §3 `poiManager.take(is(<targetPoiKey>), (t,p)->true, blockPosition(), RADIUS)` + set
   `POTENTIAL_JOB_SITE`. Throttle it (reuse the `nextPlanTime`-style throttle already in
   `VillagerEssence`). Vanilla `GoToPotentialJobSite` + `AssignProfessionFromJobSite` then finish
   the walk-and-claim; our `setVillagerData` mixin (§4) confirms the role once the profession lands.
   - Setting `POTENTIAL_JOB_SITE` suppresses vanilla's `ALL_ACQUIRABLE_JOBS` `AcquirePoi`, so the
     villager won't grab an off-target job type in the meantime.
   - If `take` returns empty (no free target in range), leave the role "pending" and retry later.

**Reverse: job site claimed → role granted.** In the `setVillagerData` HEAD mixin (§4): when
`oldP == NONE && newP` maps to a role, `essence.setRole(mappedRole)` if it differs. When
`newP == NONE` (profession lost), clear the role to `NONE` (owner's call — recommended for symmetry).

**Edge cases:**
- **Role changed while employed elsewhere.** A villager with `TOOLSMITH` + trade XP/level >1
  **cannot** drop that profession (Q2). Either (a) restrict role→jobsite seeking to villagers whose
  profession is `NONE`, or (b) accept a temporary role≠profession mismatch (role drives gathering,
  profession drives trades) until the site is lost. Cleanest for the owner's "unify" intent: only
  auto-seek from `NONE`; surface a warning if asked to re-role an XP'd, professioned villager.
- **Jobsite destroyed mid-work.** `ValidateNearbyPoi` erases `JOB_SITE` (within 16 blocks) →
  `ResetProfession` clears profession iff `xp==0 && level<=1` → our mixin sees `→NONE` and clears the
  role → the forward seek re-claims a new site. Fully consistent; no extra handling.
- **Two villagers, one composter.** `maxTickets = 1`; the second `take` returns empty (guard on it).
  If a duplicate still forms, `PoiCompetitorScan` evicts the lower-XP villager (Q2). Our seek should
  only set `POTENTIAL_JOB_SITE` on a successful `take`.
- **Baby.** Gate the seek on `!isBaby()` to mirror `AcquirePoi` (babies also can't be assigned).
- **`assignProfessionWhenSpawned`** (`Villager.java:273-284`, `finalizeSpawn:795-796`): structure-
  spawned villagers skip the 2-block proximity gate for one tick — irrelevant to runtime seeking but
  note it if we ever spawn pre-professioned villagers.

**Net**: the mod should **never** call `setProfession` directly. Forward direction claims a POI via
`PoiManager#take` + `POTENTIAL_JOB_SITE` and lets vanilla assign; reverse direction observes
`setVillagerData` and mirrors into `VillagerEssence`. Both survive `refreshBrain`.
