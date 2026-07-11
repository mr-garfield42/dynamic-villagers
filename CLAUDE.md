**Before any substantial coding task** (new feature, data generation, implementation over ~20 lines):
pause and check — does a public API, package, or one-liner already solve this? If yes, use it.
Only then proceed with the minimum that solves the problem today.

**Before writing each code block:**
build only what was explicitly asked for. Do not add error handling, tests, type annotations,
docstrings, or abstractions unless requested. If something seems worth adding, say so after
delivering the output — don't add it unilaterally.

**Skip both rules for:** bug fixes under ~10 lines, infra/terraform/k8s, DB queries, or when
the user explicitly asked for a complete or production-ready implementation.



## Dynamic Villagers (title may change)

Dynamic Villagers is a complete overhaul of Minecraft villages for NeoForge 1.21.1. Instead of villagers acting as scripted NPCs, they become autonomous citizens capable of gathering resources, constructing buildings, managing inventories, establishing economies, defending their homes, and interacting with neighboring villages.

Villages are no longer pre-generated settlements that remain mostly unchanged. Every village becomes a living society that continuously adapts to its environment. Villagers harvest trees, mine ores, gather food, transport materials, expand infrastructure, repair damage, and respond to changing needs without player intervention.

Every villager possesses a real inventory and performs actions under the same physical limitations as the player. They must carry materials, walk to storage, look directly at blocks before breaking or placing them, use tools that wear down over time, consume food to survive, and craft required items before construction can begin.

As villages accumulate resources and population, they naturally expand by constructing additional homes, farms, warehouses, mines, roads, defensive walls, and other structures. Builders cooperate with lumberjacks, miners, farmers, and blacksmiths to complete projects while transporting materials through the village.

Economically, each village develops its own government and economic system. Some villages operate under capitalism, where villagers earn emerald wages according to their contribution and purchase goods from one another. Others share resources communally or organize themselves under democratic or authoritarian governments. Wealth, employment, and labor distribution emerge naturally from village activity.

Villages are also capable of interacting with one another. Merchants establish trade routes, alliances form between neighboring settlements, conflicts may arise over territory or resources, and diplomacy influences regional development.

Combat is delegated to Guard Villagers through compatibility with the Guard Villagers mod. Guards patrol settlements, defend valuable storage, escort workers, upgrade equipment using village resources, and organize themselves into defensive groups as settlements grow.

The player is not intended to micromanage the village. Instead, villages exist as independent societies that the player may choose to observe, assist, exploit, or influence.

---

# Design Philosophy

Every villager should follow three rules:

1. They only know information they have learned.
    
2. They obey the same physical rules as players.
    
3. Every action should have a logical reason.
    

That means:

- No teleporting items.
    
- No magically spawning buildings.
    
- No infinite inventories.
    
- No cheating AI.
    

Everything must be physically accomplished.


# Thief Mod Integration

The mod should integrate with the Thief mod rather than creating a separate theft system.

The Thief mod should be treated as the source of truth for theft mechanics. Dynamic Villagers should react to theft events rather than independently detecting and implementing stealing behavior.

## Integration Goals

When theft occurs:

- Villages should recognize stolen resources
    
- Guards should respond
    
- Villagers should react according to their government and security policies
    
- Reputation consequences may occur
    
- Security investments should reduce theft risk
    

---


---

## Theft Response

When the Thief mod reports a theft:

Dynamic Villagers should determine:

- Who owns the stolen item
    
- Which village it belongs to
    
- The value of the stolen goods
    
- Whether guards or citizens respond
    
- What consequences occur
    

---

## Village Security System

Dynamic Villagers should manage the societal response:

Security level is affected by:

- Number of guards
    
- Wealth of village
    
- Government type
    
- Defensive structures
    
- Previous thefts
    
- Population size
    

Higher security villages should:

- Have more guards
    
- Patrol more often
    
- Protect valuable storage
    
- Respond faster
    

---

## Government Interaction

Different governments should react differently.

Examples:

Capitalist:

- Strong protection of private property
    
- Increased punishment for theft
    

Communist:

- Strong protection of communal storage
    
- Less emphasis on individual ownership
    

Democracy:

- Investigation-based response
    

Monarchy:

- Response depends on authority of leadership
    

Dictatorship:

- Immediate and harsh response
    

---

## Guard Villagers Integration

When theft occurs:

Guards should:

- Receive alerts
    
- Investigate the area
    
- Pursue criminals
    
- Protect storage
    
- Coordinate with other guards
    

Use Guard Villagers combat mechanics instead of replacing them.

---

## Design Rule

Do not duplicate Thief mod functionality.

Use:

- Thief mod for stealing mechanics
    
- Guard Villagers for combat mechanics
    
- Dynamic Villagers for social/economic consequences

---

# Suggested Development Roadmap

I strongly recommend **not** starting with governments or diplomacy. Those depend on a functioning village simulation.

## Phase 1 — Villager Framework (Foundation)

Difficulty: ★★★☆☆

This phase creates the systems every later feature depends on.

Features:

- Individual inventories
    
- Hunger
    
- Item durability
    
- Tool usage
    
- Picking up dropped items
    
- Carrying resources
    
- Basic memory
    
- Simple task system
    

No construction yet.

Goal:

> Villagers behave like simplified players.

---

## Phase 2 — Resource Gathering

Difficulty: ★★★★☆

Add professions capable of collecting resources.

Features:

- Tree cutting
    
- Replant saplings
    
- Farming improvements
    
- Mining
    
- Torch placement (villagers light up dark areas around the village to prevent hostile mob
  spawns — requested by owner 2026-07-09; light-level scan + PlaceBlockTask with torches)
    
- Quarry generation
    
- Strip mines
    
- Basic bridge construction
    
- Ladder construction
    

Goal:

Villages become resource-positive.

---

## Phase 3 — Storage Network

Difficulty: ★★★☆☆

Features:

- Public storage
    
- Private storage
    
- Item reservation
    
- Material requests
    
- Builders know where supplies are (only to prevent time wasted searching through chests)
    
- Miners, lumberjacks, farmers, deposit resources
    

Goal:

Villagers stop carrying everything forever.

---

## Phase 4 — Construction

Difficulty: ★★★★★

Probably the hardest early milestone.

Features:

- Blueprint system
    
- Foundation laying
    
- Wall construction
    
- Roof construction
    
- Path building
    
- Farm construction
    
- House construction
    
- Repair damaged buildings
    
- Dirt scaffolding
    

At this point villages finally grow.

---

## Phase 5 — Population

Difficulty: ★★★☆☆

Features:

- Children
    
- Housing capacity
    
- Population limits
    
- Assign new jobs
    
- Workforce balancing
    

Goal:

Villages expand naturally.

---

## Phase 6 — Economy

Difficulty: ★★★★★

Features:

- Emerald wages
    
- Employment
    
- Wealth
    
- Bankruptcy
    
- Buying food
    
- Paying builders
    
- Hiring miners
    
- Blacksmith services
    

Every villager now has finances.

---

## Phase 7 — Governments

Difficulty: ★★★★☆

Features:

Capitalism

Communism

Democracy

Monarchy

Dictatorship

Each government changes:

- ownership
    
- wages
    
- taxes
    
- storage
    
- decision making
    

This becomes a modifier rather than replacing every system.

---

## Phase 8 — Defense

Difficulty: ★★★★☆

Using Guard Villagers.

Features:

- Guard patrols
    
- Equipment upgrades
    
- Chest defense
    
- Escort workers
    
- Wall construction
    
- Gates
    
- Watchtowers
    

---

## Phase 9 — Diplomacy

Difficulty: ★★★★★

This becomes almost a strategy game.

Features:

- Merchant caravans
    
- Trade routes
    
- Alliances
    
- Wars
    
- Peace treaties
    
- Territory
    
- Shared resources
    
- Negotiations
    

---

## Phase 10 — World Simulation

Difficulty: ★★★★★

The "wow" phase.

Villages can:

- Found colonies
    
- Expand indefinitely
    
- Compete
    
- Recover from disasters
    
- Rebuild after raids
    
- Adapt to changing terrain
    

At this point Minecraft begins to resemble a civilization simulator.

---

# Architecture Recommendation

Instead of every villager making huge decisions every tick, I'd recommend something like this:

```
Village
│
├── Village Manager
│      Tracks resources
│      Tracks buildings
│      Tracks jobs
│      Tracks population
│
├── Task Queue
│      Build House
│      Mine Iron
│      Chop Trees
│      Repair Wall
│
├── Resource Manager
│
├── Economy Manager
│
├── Government Manager
│
└── Diplomacy Manager
```

Each villager would then have its own "brain" that decides _how_ to complete assigned work, while the village-level systems decide _what_ work needs doing. This preserves the feeling of independent villagers while avoiding the computational cost of having every villager constantly solve global planning problems.

---

# Estimated Project Scale

Compared to well-known Minecraft mods:

| Mod                  | Relative Scope |
| -------------------- | -------------- |
| Vanilla Villagers    | 1×             |
| Guard Villagers      | 2×             |
| Villager Overhaul    | 3×             |
| MineColonies         | 8×             |
| The completed vision | **10–15×**     |

Before writing a single line of code, please review multiple sources on Minecraft modding for Neoforge, typical conventions, how Mojang's classes work. These sources will also provide context about: 

- NeoForge registries
- Event system
- Entity creation
- Entity AI (Goals & Brain)
- Pathfinding
- Inventory systems
- Block interaction (breaking/placing)
- SavedData and persistence
- Networking
- Data generation
- Performance optimization
- Software architecture and design patterns

- Sources (do not limit yourself to just these):
  - https://docs.neoforged.net
  - https://docs.neoforged.net/docs/gettingstarted/structuring
  - https://docs.neoforged.net/docs/gettingstarted/modfiles
  - https://github.com/neoforged
  - https://github.com/neoforged/NeoForge
  - https://www.baeldung.com/java-performance
  - https://www.redblobgames.com/pathfinding/a-star/introduction.html
  - https://www.gameaipro.com
  - https://refactoring.guru/design-patterns

# Mod Dependencies

The following mods are core dependencies and may be directly integrated:

- Guard Villagers (https://modrinth.com/mod/guard-villagers) Source code: (https://github.com/seymourimadeit/guardvillagers)
- Thief (https://modrinth.com/mod/thief) Source code: (https://github.com/mortuusars/Thief)

Do not design around these mods being absent.

This is on Neoforge 1.21.1

Use their APIs and systems instead of recreating functionality.

Examples:

Guard Villagers:
- Use existing guard entities
- Use existing combat AI
- Use existing armor/equipment systems

Thief:
- Use existing theft mechanics
- React to theft events
- Build village security and social consequences around theft

Do not duplicate functionality that these mods already provide.

**Villager Overhaul was removed as a companion mod (owner decision, 2026-07-09).** Its license
forbids code reuse, it exposes no integration API, and its client rendering mixins crash dev
environments. Do not add it back to the dev runtime or design around it. If a user runs it
alongside anyway, its deep control only affects player-recruited villagers, so incidental
compatibility is likely but no longer tested.

You may update and modify this document with any useful information you find while researching, bug fixing, etc.

---

# Established Facts (researched 2026-07-08 — see docs/RESEARCH.md for detail)

## Owner directives (standing)
- **Smelting** (2026-07-10): in addition to crafting, villagers must eventually be able to
  smelt in furnaces — deferred to the crafting/economy phases. Furnaces are deliberately NOT
  storage (`dynamicvillagers:storage_containers` tag); when smelting lands they become
  workstations with their own handling.
- **Vanilla village structures** (2026-07-10): the buildings villagers construct are the
  vanilla village structures (wiki: Village/Structure/Blueprints), loaded directly from
  `minecraft:village/...` templates — do NOT design custom houses. Mod-authored templates
  are allowed only as gametest fixtures / debug scaffolding (e.g. `starter_shelter`),
  never as village content.

- **Profession integration** (2026-07-10, owner decisions from planning Q&A): DV roles and
  vanilla professions become one identity. Mapping: Farmer↔Farmer, Miner↔Toolsmith,
  Lumberjack↔Fletcher, Builder↔Mason. Setting a DV role makes the villager seek and claim
  the nearest free matching jobsite block, which grants the vanilla profession/skin the
  vanilla way (role works immediately; cosmetic arrives with the POI claim — professions are
  never force-set). Reverse direction: a villager that naturally claims any mapped jobsite
  auto-gains the DV role. Unmapped professions (librarian, cleric, ...) stay pure vanilla;
  villagers keep and level their trades (Phase 6 hook).
  - **IMPLEMENTED 2026-07-10** (research: docs/RESEARCH_PROFESSIONS.md). `RoleProfessions`
    (bidirectional map), `SeekJobSiteBehavior` (CORE prio 5, ahead of vanilla AcquirePoi:
    `PoiManager#take` reserves the specific jobsite ticket + writes POTENTIAL_JOB_SITE, then
    vanilla walks + assigns), and a `Villager#setVillagerData` HEAD mixin mirrors profession
    back into the role. Forward seek gates on profession==NONE (a traded villager can never
    drop its profession, so re-roling an employed villager is a no-op by design — leaves a
    role≠profession mismatch that only gathering, not trades, acts on). Cosmetic is NOT
    instant: it lands when the villager reaches the jobsite (a reachable jobsite block must
    exist, else the role stays skinless). 8 gametests incl. two end-to-end walk-and-claim.
  - **Assignment outranks the mirror (fix 2026-07-11)**: a villager with an active build/path
    assignment (Building/Path Marker or `/dv build|path assign`) keeps its BUILDER role — it
    does NOT seek a jobsite (`SeekJobSiteBehavior` bails on `hasBuildAssignment`) and the
    `setVillagerData` mirror does NOT overwrite its role. Without this, a marked builder near
    a claimable jobsite (composter, ...) would claim it, gain that profession, and get
    re-roled away from BUILDER — silently stopping the build (owner playtest regression).
- **Builders build assigned sites only** (2026-07-10): no opportunistic adoption of open
  construction sites until the Phase 5 village manager assigns work properly.

## Scheduled next after Phase 4 (owner, 2026-07-10)
- **Hunter role** — see the idea entry below; pull it forward as the first work item once
  Phase 4 closes (killing + cooking + depositing loop; sales wait for Phase 6).
- **Lumberjack: plant extra saplings near felled trees** — beyond the current replant-on-
  the-stump, plant spare saplings on nearby valid soil around recently cut trees so wooded
  areas thicken instead of merely holding steady.

## Owner ideas for future phases (not yet scheduled, 2026-07-10)

Captured for later planning — do not start implementing until a phase plan picks these up.

- **Procedural village/villager names.** Owner's explicit design guidance: don't store a
  name list, generate names from small prefix/suffix pools instead — tiny memory footprint,
  no duplicate-list maintenance, easy to bias by context.
  - Village names: prefix + suffix (e.g. `Oak/River/Stone/Iron/Green/Ash/Wolf/Red/Pine/Fox/
    Black/High` + `dale/brook/ford/haven/cross/field/vale/crest/wick/ridge/moor/hollow` →
    `Oakbrook`, `Riverdale`, `Stonehaven`, `Foxhollow`...). 100×100 pools = 10,000 unique
    names from 200 stored strings.
    - Weight/select pools by biome so names fit their environment (e.g. `Snow`+`haven` in
      snowy biomes, `Sand`+`reach` in desert) — owner suggested going further and building
      culture-specific pools per biome group (Plains, Taiga, Desert, Savanna, Jungle, Swamp).
  - Villager names: same generative approach, presumably its own prefix/suffix (or
    first/last) pools — not detailed yet, decide alongside village names.
  - Natural home: Phase 5 (Population), where villages first need persistent identity beyond
    "the nearest bell."
- **Village inspector UI via the Debug Stick.** Right-click a **bell** with the existing
  Villager Debug Wand (`DVItems.DEBUG_WAND`) to open a village-level panel (population, job
  breakdown, house count, etc.) — the village-scoped sibling of the current per-villager
  debug screen (`VillagerDebugScreen`). Depends on there being an actual `Village`/
  `VillageManager` SavedData to query (Phase 5), not just the per-villager `VillageAnchor`
  used through Phase 3.
- **New role: Builder.** Constructs designated structures — this *is* Phase 4
  (Construction); no new roadmap phase needed, just implement the role there alongside the
  blueprint system.
- **New role: Hunter.** Kills animals, cooks the meat (furnace or campfire — ties directly
  into the standing smelting/cooking directive above), and supplies/sells that food to other
  villagers alongside the Farmer. Gathering-shaped work (natural fit as a Phase 2-style
  role, added after Phase 2 closed) but the "sell to other villagers" half needs Phase 6
  economy (wages/buying/selling) to mean anything — implement the killing+cooking+depositing
  loop whenever convenient, wire in actual sales once Phase 6 exists.
- **Compatibility with Improved Village Placement**
  (https://modrinth.com/mod/improved-village-placement,
  https://github.com/Apollounknowndev/improved-village-placement). Owner is open to
  eventually merging it into Dynamic Villagers rather than just depending on it. Needs the
  same research-before-coding pass as Guard Villagers/Thief got (see docs/RESEARCH.md) before
  any integration work: what it actually changes about village worldgen placement, whether
  it exposes hooks/events, and whether its logic could just become part of our village
  generation once merged. Not yet researched — do that first when this is picked up.

## Pinned versions
- Minecraft 1.21.1, NeoForge 21.1.235, Java 21, ModDevGradle 2.0.141, Parchment 1.21/2024.11.10
- Mod id: `dynamicvillagers`, package `com.dynamicvillagers`, MIT license
- Companion mods (all confirmed on NeoForge 1.21.1): `guardvillagers` 2.4.x (branch `1.21.1`,
  NOT `main`), `thief` 1.2.x (GPL-3.0 — never copy code, events only). Villager Overhaul was
  dropped 2026-07-09 (see Mod Dependencies section).

## Key integration points
- Thief fires NeoForge bus events: `CrimeCommitedEvent`, `GiftGivenEvent`,
  `ReputationLevelChangedEvent`; crime severity via block/entity tags (`thief:break_protected_*`
  etc.); reputation is stored in vanilla villager gossip. Guards auto-attack criminals via the
  `thief:guards` entity tag (already contains `guardvillagers:guard`).
- **Thief's structure gate** (verified from the 1.2.x jar, 2026-07-10): with the default
  `crime_only_in_protected_structures = true`, crimes are detected ONLY inside `#thief:protected`
  worldgen structures (= generated `#minecraft:village`). Creative test pads and any village
  our mod grows outside a generated structure are unprotected — this is why a chest-steal test
  on a flat world shows "no Thief effects" (not a bug on either side). Chest/barrel opening is
  a MEDIUM interact crime (`#c:chests`/`#c:barrels`) and the default guard-attack threshold is
  MEDIUM, so guards do respond inside real villages when witnessed (32-block LOS witness model;
  Hero of the Village grants immunity). The dev instance config (`run/config/thief-server.toml`,
  gitignored) has the gate set to false so test pads behave. Phase 8 must address dynamic
  villages: either document the config recommendation or contribute a positional protection
  hook upstream to mortuusars/Thief.
- Adding villager Brain behaviors: do NOT mixin into `VillagerGoalPackages.get*Package` —
  Guard Villagers' cancellable RETURN injection there short-circuits any later handler
  (verified 2026-07-09; cost a debugging session). Instead inject at TAIL of
  `Villager.registerBrainGoals` and call `Brain.addActivity(Activity.CORE, ...)` — appends
  safely, survives brain rebuilds, composes with GV/VO/Thief.
- Architecture decision (Phase 1): enhance vanilla `minecraft:villager` (no custom entity);
  per-villager state in one codec-serialized NeoForge data attachment; village-level state in
  `SavedData`. See docs/PHASE1_PLAN.md, docs/PHASE2_PLAN.md, docs/PHASE3_PLAN.md (all
  complete) and docs/PHASE4_PLAN.md (current).

## Dev environment (this machine)
- JDK 21 is portable at `%USERPROFILE%\.jdks\jdk-21.0.11+10` (not on PATH). Before Gradle:
  `$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jdk-21.0.11+10"` then `.\gradlew.bat <task>`.
- User's IDE is IntelliJ IDEA (do not suggest Eclipse); the `.jdks` location is auto-detected by IDEA.
- GitHub CLI: `C:\Program Files\GitHub CLI\gh.exe` (fresh shells have it on PATH).
- When cloning mod sources for study on Windows: `git config core.longpaths true` first.
- First `gradlew build` verified working (BUILD SUCCESSFUL, ~3 min warm).