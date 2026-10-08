# IMPLEMENTATION_LEDGER

Status legend (mandatory):

| Status | Meaning |
|--------|---------|
| `PLANNED` | Designed, not meaningfully coded |
| `IMPLEMENTING` | Partial code; gaps expected |
| `FUNCTIONAL` | Core logic works in isolation (engine/unit/tool path) |
| `INTEGRATED` | Wired across modules (plan ↔ sim ↔ IPC ↔ physical/UI) in source |
| `RELEASE_READY` | model + authority + simulation + persistence + IPC + physical + player + errors + perf + config + docs **all** work in practice |

**Do not use `VERIFIED`.**  
**Do not rubber-stamp `RELEASE_READY`.** After Blocks A–H, almost everything tops out at FUNCTIONAL or INTEGRATED. This pass was documentation + static inspection; **tests were not executed**.

## Subsystem ledger

| Subsystem | Status | Notes |
|-----------|--------|-------|
| Multi-module Gradle foundation | INTEGRATED | common, protocol, worldgen, simulation, sidecar, neoforge, testkit, tools |
| Stable IDs + simulation time | INTEGRATED | UUID domains, `SimulationTime`, calendar, `WorldIdentity` / `WorldIdentityContract` |
| IPC protocol (binary, versioned) | INTEGRATED | `BinaryCodec`, handshake identity fields, unit tests present (not run here) |
| Sidecar process lifecycle | INTEGRATED | Auto-start/stop, embed jar, bounded restart (3), loopback bind |
| Sidecar ↔ Minecraft handshake | INTEGRATED | Version/seed/plan-hash checks; reject paths coded |
| Coordinated save + WAL | INTEGRATED | Schema 4 full graph + DynamicPhysicalState + WAL; save barrier pauses engine |
| Dynamic physical reconciliation | INTEGRATED | PhysicalIntent lifecycle, ConstructionEngine intents, BanditryEngine, NeoForge PhysicalReconciliationEngine, outcome applier |
| Caravan / army projection | INTEGRATED | CaravanProjectionBinder + ArmyProjectionBinder (LOD, not 1:1) |
| Physical interaction bridge | INTEGRATED | Verified death/interact → typed PhysicalOutcomePayload |
| Worldgen terrain (synthetic) | INTEGRATED | `TerrainAnalyzer` for tools/tests |
| Minecraft terrain provider | INTEGRATED | `MinecraftTerrainProvider` injected from `WorldPlanCache` |
| Cultures (12 surface + Wizard Trees) | INTEGRATED | Architecture, naming, government, biomes |
| Kingdom / territory planning | INTEGRATED | Configurable counts; adjacency on kingdoms |
| Settlement planning + scoring | INTEGRATED | Hierarchy + specialization slots |
| Road graph + bridges (plan) | INTEGRATED | Terrain-aware A*; bridge records on roads |
| Urban districts / lots / buildings (plan) | INTEGRATED | Procedural architecture grammar |
| Wizard Trees underground civ | INTEGRATED | Planner + cavern materializer wired |
| Full world-plan persistence | INTEGRATED | `plan.bin` format 2; load-preserves geometry |
| Chunk materialization | INTEGRATED | Roads/bridges/walls/gates/buildings/resources/ruins/camps/Wizard Trees; chunk attachments |
| Initial canonical state handoff | INTEGRATED | `InitialStateFactory` + plan attach on load |
| Citizens / households / professions | INTEGRATED | Family graph, housing/work ids, schedules, projection binder, NBT identity, skins/AI |
| Economy / markets | INTEGRATED | Multi-resource engine; famine unit test present (not run here) |
| Trade / caravans | INTEGRATED | Road-routed `TradeEngine` + shipments in save schema |
| Government / dynasties / succession | INTEGRATED | Engine + succession unit test present (not run here) |
| Diplomacy / treaties | FUNCTIONAL | Relation matrix + treaty types in engine/state |
| Law / crime / justice | FUNCTIONAL | Crime→sentence pipeline engine |
| Military / war / sieges | FUNCTIONAL | Abstract armies + causes; not player-facing battle sim |
| Disease | FUNCTIONAL | Infectivity/severity/immunity fields |
| Migration / refugees | FUNCTIONAL | Citizen migration + refugee outcomes in engine |
| Ecology | FUNCTIONAL | Data-driven multi-archetype species |
| Technology / education | FUNCTIONAL | Tech tree with prereqs; schools |
| Religion | FUNCTIONAL | Festivals, legitimacy, diplomacy leanings |
| History / rumors | FUNCTIONAL | Events → markers + citizen-learned rumors |
| Emergent tasks | FUNCTIONAL | Spawned from canonical problems |
| No-LLM dialogue | FUNCTIONAL | EN/NL intents; knowledge-bounded lines; IPC context |
| Player reputation / realms | FUNCTIONAL | Standing ladder + player-founded kingdom workflow in engine |
| Map (M) / Dashboard (F12) | INTEGRATED | Server payloads + tabbed diagnostics (live client fields) |
| Locate commands | INTEGRATED | Sidecar path + offline plan-cache fallback |
| Time sync / catch-up | INTEGRATED | `TIME_SYNC` + bounded catch-up in host |
| User configuration | INTEGRATED | `livingmods.properties` format v1 + clamped ranges + legacy key migrate |
| Optional mod integrations | IMPLEMENTING | Soft `ModList` detection only; Create/Waystones not claimed complete |
| Determinism unit tests | FUNCTIONAL | Tests exist for sim/worldgen/protocol; **not executed this pass** |
| Long-term accelerated sim | FUNCTIONAL | Host catch-up + tools `bench` |
| Performance hardening | FUNCTIONAL | Indexed citizen loops, spatial cell cache, bounded queues/caches, workers `max(1,n/3)` |
| Error handling / diagnostics | FUNCTIONAL | Plan/schema/duplicate-ID fail loudly; recoverable step degrade |
| Multi-world static reset | FUNCTIONAL | ServerStopping clears clients/process/plan/projection caches |
| Release Block I hardening | FUNCTIONAL | Deserial bounds, loopback IPC, versioned sidecar extract |
| Documentation | INTEGRATED | Rewritten for Blocks A–I; checklist unchecked |

## RELEASE_READY count

| Status | Count (approx.) |
|--------|-----------------|
| RELEASE_READY | **0** |
| INTEGRATED | majority of foundation/worldgen/IPC/persistence/UI wiring |
| FUNCTIONAL | most society engines + unit-tested islands |
| IMPLEMENTING | optional mods (detection-only) |
| PLANNED | real Create/Waystones/Macaw/Better Villages/Guns adapters |

A subsystem may be promoted to RELEASE_READY only after the relevant sections of [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) pass on a real client/server.

## Version axes (do not conflate)

| Axis | Current | Source |
|------|---------|--------|
| Protocol | 2 | `LivingModsVersions.PROTOCOL_VERSION` (typed physical outcomes) |
| Worldgen | 2 | `WORLDGEN_VERSION` |
| Canonical save schema | 4 | `CANONICAL_SAVE_SCHEMA` (DynamicPhysicalState) |
| Physical content revision | 2 | `PHYSICAL_CONTENT_REVISION` (initial materialization only) |
| Mod / sidecar | 0.1.0 | `MOD_VERSION` / `SIDECAR_VERSION` |

Also recorded in `gradle.properties` for clarity; **Java constants win at runtime**.

## Blocks completed (code)

| Block | Theme | In tree |
|-------|-------|---------|
| A | Production IPC, world identity, time sync, scheduler concurrency, full-graph persistence | yes |
| B | Minecraft terrain provider, full world-plan persistence | yes |
| C | Physical materialization + chunk provenance | yes |
| D | Citizen vertical slice (projection, skins, NBT identity) | yes |
| E+F | Multi-resource economy, road-routed trade, politics/war/justice | yes |
| G+H | Society systems + player experience UI (map/dashboard) | yes |
| I | Release hardening (perf, multi-world, security bounds, packaging) + docs | yes |

## Local commands (reference only — not run here)

```bash
./gradlew :livingmods-common:test :livingmods-protocol:test :livingmods-simulation:test
./gradlew :livingmods-sidecar:sidecarJar
./gradlew :livingmods-neoforge:compileJava
./gradlew :livingmods-tools:run --args='plan 42'
```
