# Phase 1 Plan — Villager Framework

Goal (from CLAUDE.md): **villagers behave like simplified players.** Individual inventories,
hunger, tool use with durability, item pickup/carrying, basic memory, and a simple task system.
No construction, no economy, no village-level planning yet.

Status: **in progress** (updated 2026-07-08).
- Done: GameTest harness (headless, `gradlew runGameTestServer`); milestone 1.0 (attachment
  plumbing, `VillagerEssence`, `/dv inspect` + `/dv hunger` commands); 1.1 extended inventory
  (27 logical slots — vanilla 8 + 19 extra; item pickup still open); 1.2 hunger decay + eat
  behavior (Brain injection via `VillagerGoalPackages` mixin). 6/6 GameTests green.
- Next: 1.1 item pickup, 1.3 WorkExecutor, 1.4 memory, 1.5 task system, 1.6 mod-coexistence pass.

## Architectural decisions (proposed)

1. **Enhance vanilla villagers; do not replace the entity.** Villager Overhaul, Guard
   Villagers, and Thief all operate on `minecraft:villager` — a custom entity would break all
   three integrations. All extra state lives in NeoForge data attachments; all extra behavior
   is added to the existing Brain.
2. **AI via Brain behaviors, injected with a `VillagerGoalPackages` mixin** (the Guard
   Villagers pattern): append our behaviors to the WORK/IDLE/CORE packages so they respect the
   vanilla schedule (work hours, meals, sleep) and survive brain rebuilds. Bonus: when Villager
   Overhaul gates the brain for a player-recruited villager, our AI pauses automatically —
   correct behavior for free.
3. **Two-layer decision making** (from CLAUDE.md architecture): village-level systems decide
   *what* needs doing (later phases); the villager brain decides *how*. In Phase 1 the "what"
   is a per-villager `TaskQueue` fed by debug commands and simple needs (eat when hungry,
   store surplus), so the framework exists before village managers do.
4. **Server-authoritative everything.** No client code in Phase 1 beyond what debugging needs
   (the existing vanilla renderer is enough; carried-item display is a stretch goal).

## Milestones

### 1.0 Foundations
- Data attachment registration plumbing (`DeferredRegister<AttachmentType<?>>`).
- `VillagerEssence` attachment (working name): codec-serialized container for all Phase 1
  state below, so we add one attachment, not five.
- Debug command root (`/dynamicvillagers` or `/dv`): inspect a looked-at villager's state.

### 1.1 Inventory & carrying
- Expanded inventory (target: 27 slots, config later) wrapping/superseding the vanilla 8-slot
  `SimpleContainer` — vanilla behaviors (farmer seed pickup, food sharing) must keep working.
- Item pickup: extend `wantsToPickUp`/pickup behavior to whitelist task-relevant items.
- Weight/limit rule (simple: slot count only, no encumbrance yet).
- Debug: command to dump inventory; drop-all on death (no item deletion — player rules).

### 1.2 Hunger
- Hunger value (0–20 like the player) + saturation-lite, on the attachment; decays with
  activity, ticks server-side only.
- Eat behavior: when hungry and food in inventory, play eat animation/sounds, restore hunger.
  Reuses/extends the vanilla `foodLevel` concept (breeding willingness must not break).
- Starvation: damage at 0, never below — villagers don't die of hunger in Phase 1 (config
  flag), they emit a "hungry" state other systems can see (groundwork for farming demand).

### 1.3 Tools & physical work
- `WorkExecutor`: the one code path through which villagers break/place blocks —
  look-at requirement, mining progress with `destroyBlockProgress`, correct tool speed,
  durability loss via `hurtAndBreak`, drops go to the world (then picked up), tool breaks
  are real. No task may bypass it (this enforces design rule #2 "same physical rules").
- Tool selection from inventory (best appropriate tool for the block).
- Debug: command to order "break that block" / "place block from inventory here".

### 1.4 Basic memory
- `VillagerMemory` (part of the attachment): remembered positions (containers seen, blocks of
  interest) with timestamps and a hard cap; forget-on-invalid when a remembered thing is gone.
  Design rule #1: villagers only know what they have learned (saw it, walked past it).
- Feed from a lightweight sensor (reuse/extend vanilla `NEAREST_ITEMS` style sensing), not from
  world queries at decision time.

### 1.5 Task system
- `Task` interface + per-villager `TaskQueue` (priority + FIFO within priority).
- Tasks compile down to Brain behaviors/walk targets; interruption rules (PANIC, sleep, VO
  recruitment) suspend and resume tasks instead of dropping them.
- Built-in Phase 1 tasks: `EatTask`, `PickUpItemsTask`, `GoToTask`, `BreakBlockTask`,
  `PlaceBlockTask`, `DepositToContainerTask` (uses memory of containers).
- Persistence: queue serializes with the attachment; tasks survive chunk unload/reload.

### 1.6 Verification pass
- GameTest scenarios: hunger decay + eating, tool durability + breaking, pickup + deposit
  round-trip, memory forget-on-invalid.
- Manual scenario in dev client with Guard Villagers + Thief + Villager Overhaul all loaded:
  confirm no AI fights (recruit a villager in VO → our tasks suspend; convert one to guard →
  nothing breaks; steal with witnesses → vanilla/Thief behavior unchanged).

## Open questions (decide before/while implementing)

1. Whether Phase 1 milestone 1.1 replaces the vanilla 8-slot container or wraps it — needs a
   spike against `AbstractVillager` internals to see which is less invasive.
2. Tick budget: how many villagers with active tasks per tick before frame cost shows on a
   dedicated server — measure early with 50–100 villagers.
3. Whether Villager Overhaul exposes a reliable "is recruited/controlled" check we can read
   without compiling against it (their saved data format), or whether "our behaviors live in
   the Brain so VO's gate pauses us" is sufficient — test in 1.6.

## Explicitly out of scope for Phase 1

Construction, blueprints, professions' resource gathering (Phase 2), storage networks beyond
"deposit into a remembered chest" (Phase 3), economy, governments, guard coordination,
diplomacy, any world-gen changes.
