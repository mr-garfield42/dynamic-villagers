# Dynamic Villagers

A complete overhaul of Minecraft villages for **NeoForge 1.21.1**. Villagers become autonomous
citizens that gather resources, construct buildings, manage inventories, establish economies,
defend their homes, and interact with neighboring villages — all under the same physical rules
as the player. No teleporting items, no magically spawning buildings, no cheating AI.

> Early development. Nothing playable yet — see [docs/PHASE1_PLAN.md](docs/PHASE1_PLAN.md) for
> the current roadmap position.

## Companion mods

Dynamic Villagers is designed to run alongside (and integrate with) these mods rather than
duplicate them:

| Mod | Role | Integration |
| --- | --- | --- |
| [Guard Villagers](https://modrinth.com/mod/guard-villagers) | Combat & guard entities | Guards defend villages; we manage alerts, equipment funding, patrol assignments |
| [Thief](https://modrinth.com/mod/thief) | Theft & crime detection | We react to its `CrimeCommitedEvent` etc. with social/economic consequences |

## Development setup

Requirements: JDK 21 (64-bit).

```
./gradlew build        # build the mod jar
./gradlew runClient    # launch a dev client
```

First run downloads and decompiles Minecraft — expect it to take a while.

Project docs live in [docs/](docs/): research notes on the companion mods and NeoForge
internals, plus phase plans. High-level design rules are in [CLAUDE.md](CLAUDE.md).

## License

Code is [MIT](LICENSE). Based on the [NeoForge MDK template](https://github.com/NeoForgeMDKs/MDK-1.21-ModDevGradle).
