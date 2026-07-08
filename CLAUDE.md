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
    
- Torch placement
    
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
    
- Miners deposit resources
    

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
- Villager Overhaul (https://modrinth.com/mod/villager-overhaul) Source code: (https://github.com/z2six/VillagerOverhaul)
- Thief (https://modrinth.com/mod/thief) Source code: (https://github.com/mortuusars/Thief)

Do not design around these mods being absent.

This is on Neoforge 1.21.1

Use their APIs and systems instead of recreating functionality.

Examples:

Guard Villagers:
- Use existing guard entities
- Use existing combat AI
- Use existing armor/equipment systems

Villager Overhaul:
- Extend existing villager improvements
- Avoid duplicating professions or villager mechanics

Thief:
- Use existing theft mechanics
- React to theft events
- Build village security and social consequences around theft

Do not duplicate functionality that these mods already provide.

You may update and modify this document with any useful information you find while researching, bug fixing, etc.

---

# Established Facts (researched 2026-07-08 — see docs/RESEARCH.md for detail)

## Pinned versions
- Minecraft 1.21.1, NeoForge 21.1.235, Java 21, ModDevGradle 2.0.141, Parchment 1.21/2024.11.10
- Mod id: `dynamicvillagers`, package `com.dynamicvillagers`, MIT license
- Companion mods (all confirmed on NeoForge 1.21.1): `guardvillagers` 2.4.11 (branch `1.21.1`,
  NOT `main`), `thief` 1.2.3 (GPL-3.0 — never copy code, events only), `villageroverhaul` 3.10.x
  (source-available license — learn-only, never copy code or assets)

## Key integration points
- Thief fires NeoForge bus events: `CrimeCommitedEvent`, `GiftGivenEvent`,
  `ReputationLevelChangedEvent`; crime severity via block/entity tags (`thief:break_protected_*`
  etc.); reputation is stored in vanilla villager gossip. Guards auto-attack criminals via the
  `thief:guards` entity tag (already contains `guardvillagers:guard`).
- Guard Villagers shows the canonical way to add villager Brain behaviors: mixin into
  `VillagerGoalPackages.get*Package` at RETURN (survives brain rebuilds).
- Villager Overhaul gates the vanilla villager Brain **only for player-recruited villagers**
  (`@WrapWithCondition` on `Brain.tick` in `Villager.customServerAiStep`). Rule: Dynamic
  Villagers drives unrecruited villagers; VO owns recruited ones. Keeping our AI in the Brain
  means VO's gate pauses us automatically.
- Architecture decision (Phase 1): enhance vanilla `minecraft:villager` (no custom entity);
  per-villager state in one codec-serialized NeoForge data attachment; village-level state in
  `SavedData`. See docs/PHASE1_PLAN.md.

## Dev environment (this machine)
- JDK 21 is portable at `%USERPROFILE%\.jdks\jdk-21.0.11+10` (not on PATH). Before Gradle:
  `$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jdk-21.0.11+10"` then `.\gradlew.bat <task>`.
- User's IDE is IntelliJ IDEA (do not suggest Eclipse); the `.jdks` location is auto-detected by IDEA.
- GitHub CLI: `C:\Program Files\GitHub CLI\gh.exe` (fresh shells have it on PATH).
- When cloning mod sources for study on Windows: `git config core.longpaths true` first.
- First `gradlew build` verified working (BUILD SUCCESSFUL, ~3 min warm).