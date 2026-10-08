# LivingMods

LivingMods is a **Minecraft 1.21.1 / NeoForge 21.1.x / Java 21** project that plans deterministic civilizations and simulates them in a local sidecar process.

Status after release Blocks A–H (static inspection only — **this agent did not run tests or in-game verification**): most subsystems are **FUNCTIONAL** or **INTEGRATED**. Almost none meet the bar for **RELEASE_READY**. See [IMPLEMENTATION_LEDGER.md](IMPLEMENTATION_LEDGER.md) and [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).

> Design intent: the world exists before the player arrives, keeps simulating via the sidecar, and projects into loaded Minecraft chunks. What is coded is not the same as what is proven in a running game.

## What actually exists

| Layer | What is implemented |
|-------|---------------------|
| Worldgen | Deterministic `WorldPlanner` pipeline (terrain → kingdoms → settlements → territory → roads → urban → resources/ruins), full `plan.bin` persistence, Minecraft terrain sampling at plan time |
| Physical | Chunk materializers for roads, bridges, buildings, walls/gates, farms/mines/ports, ruins, bandit camps, Wizard Trees caverns; chunk attachment idempotency |
| Sidecar | Separate JVM, loopback IPC, handshake with world-id / seed / plan-hash, simulation loop, canonical save + WAL |
| Simulation | Phased engine with demography, economy, trade, government, diplomacy, war, crime, disease, migration, ecology, tech, religion, history, dialogue, player systems |
| Player UX | Citizen entity projection (interest-based), map (`M`), dashboard (`F12`), `/livingmods locate …` |
| Integrations | Soft `ModList` availability checks only — no real Create/Waystones/etc. adapters |

## Architecture (short)

```
Minecraft / NeoForge  <--- 127.0.0.1 binary IPC --->  LivingMods Sidecar
physical world / UI                                 canonical civilization sim
```

Worldgen and chunk materialization do **not** wait on the sidecar. The sidecar loads the same persisted world plan and must match `contentHash` with Minecraft’s identity contract.

## Modules

| Module | Role |
|--------|------|
| `livingmods-common` | IDs, geo, cultures, config, domain models, version axes |
| `livingmods-protocol` | Versioned binary IPC (`LMDS` envelopes) |
| `livingmods-worldgen` | Planning, validators, plan persistence |
| `livingmods-simulation` | Canonical state + subsystem engines + save format |
| `livingmods-sidecar` | Executable simulation host |
| `livingmods-neoforge` | Mod entry, materialization, sidecar lifecycle, entities, map/dashboard |
| `livingmods-testkit` | Fixtures / benches |
| `livingmods-tools` | CLI (`plan`, `bench`) |

## Version axes (do not conflate)

| Axis | Value | Constant |
|------|-------|----------|
| Protocol | 3 | `LivingModsVersions.PROTOCOL_VERSION` |
| Worldgen | 2 | `WORLDGEN_VERSION` |
| Canonical save schema | 4 | `CANONICAL_SAVE_SCHEMA` |
| Physical content revision | 2 | `PHYSICAL_CONTENT_REVISION` |
| Mod / sidecar | 0.1.0 | `MOD_VERSION` / `SIDECAR_VERSION` |

Documented also in `gradle.properties`. Runtime authority is the Java constants.

## Build (local)

```bash
./gradlew buildAll -x test
./gradlew :livingmods-sidecar:sidecarJar
./gradlew :livingmods-tools:run --args="plan 42"
```

Installable mod artifact (includes Jar-in-Jar deps + embedded sidecar):

`livingmods-neoforge/build/libs/livingmods-0.1.0.jar`

Use that file in your NeoForge `mods/` folder — **not** the `-sources.jar`.

Unit tests exist under protocol/simulation/worldgen modules; this documentation pass did **not** execute them. There is **no CI / GameTest / GitHub Actions** in this repository by design.

## In-game controls (as coded)

| Input | Action |
|-------|--------|
| `M` | Top-down civilization map (`MapScreen` ← server `MapDataPayload`) |
| `F12` | Civilization dashboard (tabs + sidecar diagnostics) |
| `/livingmods locate <category>` | `settlement`, `capital`, `city`, `town`, `village`, `hamlet`, `mine`, `port`, `ruin`, `kingdom`, `wizardtrees` |

Disable sidecar: `-Dlivingmods.sidecar.enabled=false` (worldgen/materialization still run; live sim/IPC features will not).

Config file (created with defaults if missing): `config/livingmods.properties`.

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md)
- [WORLDGEN.md](WORLDGEN.md)
- [SIMULATION.md](SIMULATION.md)
- [SIDECAR.md](SIDECAR.md)
- [PROTOCOL.md](PROTOCOL.md)
- [PERSISTENCE.md](PERSISTENCE.md)
- [MOD_INTEGRATIONS.md](MOD_INTEGRATIONS.md)
- [TESTING.md](TESTING.md)
- [IMPLEMENTATION_LEDGER.md](IMPLEMENTATION_LEDGER.md)
- [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md)
