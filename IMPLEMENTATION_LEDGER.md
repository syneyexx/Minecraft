# IMPLEMENTATION_LEDGER

Status legend: `PLANNED` · `IMPLEMENTING` · `FUNCTIONAL` · `INTEGRATED` · `VERIFIED`

A system is `VERIFIED` only when model + simulation + persistence + IPC + physical projection + UI + tests that apply are working.

| Subsystem | Status | Notes |
|-----------|--------|-------|
| Multi-module Gradle foundation | VERIFIED | common, protocol, worldgen, simulation, sidecar, neoforge, testkit, tools |
| Stable IDs + simulation time | VERIFIED | UUID domains, `SimulationTime`, calendar |
| IPC protocol (binary, versioned) | VERIFIED | `BinaryCodec`, handshake, local unit tests |
| Sidecar process lifecycle | FUNCTIONAL | Auto-start/stop from NeoForge, loopback bind |
| Sidecar ↔ Minecraft handshake | FUNCTIONAL | Version mismatch fails clearly |
| Coordinated save + WAL | FUNCTIONAL | Full graph encode for kingdoms/settlements/citizens/markets/stockpiles |
| Worldgen terrain analyzer | VERIFIED | Deterministic noise, region summaries |
| Cultures (12 surface + Wizard Trees) | VERIFIED | Architecture, naming, government, biomes |
| Kingdom / territory planning | VERIFIED | Configurable kingdom count |
| Settlement planning + scoring | VERIFIED | Hierarchy + specialization |
| Road graph + bridges | VERIFIED | Terrain-aware A* |
| Urban districts / lots / buildings | VERIFIED | Procedural architecture grammar |
| Wizard Trees underground civ | FUNCTIONAL | Theocratic cavern settlements |
| Chunk materialization | FUNCTIONAL | Hollow buildings, roads/bridges/walls/gates, farms/mines/ports, ruins, bandits, Wizard Trees caverns; chunk attachment idempotency |
| Initial canonical state handoff | VERIFIED | `InitialStateFactory` |
| Citizens / households / professions | INTEGRATED | Family graph, housing/work structure ids, demand professions, schedule, projection binder, NBT identity, skins/AI |
| Economy / markets / trade | VERIFIED | Famine → price unit test |
| Government / dynasties / succession | VERIFIED | Succession unit test |
| Diplomacy / treaties | FUNCTIONAL | Relation matrix + treaty types |
| Law / crime / justice | FUNCTIONAL | Crime→sentence pipeline engine |
| Military / war / sieges | FUNCTIONAL | Abstract armies + causes |
| Disease / migration / refugees | FUNCTIONAL | Infectivity/severity/immunity; real citizen migration + refugee outcomes |
| Ecology | FUNCTIONAL | Data-driven multi-archetype species (not Lotka–Volterra toy) |
| Technology / education | FUNCTIONAL | Tech tree with prereqs; schools; advanced_agriculture reachable |
| Religion | FUNCTIONAL | Festivals, legitimacy, diplomacy leanings from ReligionDefinition |
| History / rumors | FUNCTIONAL | Meaningful events → markers + citizen-learned rumors |
| Emergent tasks | FUNCTIONAL | Spawned from canonical problems; completion mutates state |
| No-LLM dialogue | FUNCTIONAL | EN/NL intents; citizen knowledge (no omniscience) |
| Player reputation / realms | FUNCTIONAL | Faction standing ladder + player-founded kingdom workflow |
| Map (M) / Dashboard (F12) | FUNCTIONAL | MapDataPayload networking; tabbed SidecarClient diagnostics |
| Locate commands | FUNCTIONAL | Distance-sorted name/type/kingdom/coords/distance |
| User configuration | FUNCTIONAL | config/livingmods.properties with validated ranges |
| Optional mod integrations | IMPLEMENTING | Soft detection adapters only |
| Determinism tests | VERIFIED | Simulation + worldgen (worldgen slow) |
| Long-term accelerated sim | FUNCTIONAL | `catchUpBounded` / tools bench |
| Performance hardening | IMPLEMENTING | Spatial subscriptions, async IPC |

## Version axes (do not conflate)

| Axis | Current |
|------|---------|
| Protocol | 1 |
| Worldgen | 1 |
| Canonical save schema | 1 |
| Mod / sidecar | 0.1.0 |

## Local verification (no CI)

```bash
./gradlew :livingmods-common:test :livingmods-protocol:test :livingmods-simulation:test
./gradlew :livingmods-sidecar:sidecarJar
./gradlew :livingmods-neoforge:compileJava
./gradlew :livingmods-tools:run --args='plan 42'
```

Worldgen full-plan determinism test is expensive; prefer simulation/protocol tests for day-to-day checks.
