# Architecture

LivingMods splits three concerns:

1. **Deterministic planning** (`livingmods-worldgen`) — immutable civilization geometry
2. **Canonical simulation** (`livingmods-simulation` + `livingmods-sidecar`) — authoritative long-term state
3. **Physical presentation** (`livingmods-neoforge`) — blocks, entities, UI, Minecraft lifecycle

```
INITIAL WORLD
  WorldPlan (immutable plan.bin)
      ↓
  Initial Minecraft realization (CivilizationMaterializer)

LIVE WORLD
  CanonicalWorldState
      ↓
  PhysicalIntent / DynamicPhysicalState
      ↓
  PhysicalReconciliationEngine (NeoForge, chunk-local, bounded)
      ↓
  Minecraft World
      ↓
  PhysicalOutcome (PhysicalInteractionBridge / binders)
      ↓
  PhysicalOutcomeApplier → CanonicalWorldState

Minecraft Server (NeoForge)
  ├─ LivingModsWorldIds          → world/livingmods/world.id
  ├─ WorldPlanCache              → WorldPlanStore.loadOrGenerate (+ MinecraftTerrainProvider)
  ├─ ChunkMaterializationHandler → CivilizationMaterializer (+ chunk attachments)
  ├─ PhysicalReconciliationEngine→ dynamic intents → safe block realization
  ├─ CitizenProjectionBinder     → interest-based citizen spawn/despawn via IPC
  ├─ CaravanProjectionBinder     → shipment LOD (lead merchant + cargo meta)
  ├─ ArmyProjectionBinder        → army squad LOD (never 1:1 manpower)
  ├─ PhysicalInteractionBridge   → verified death/interact → REPORT_PHYSICAL_OUTCOME
  ├─ LivingModsCommands          → /livingmods locate …
  ├─ LivingModsNetwork           → map/dashboard payloads (live overlays from sidecar)
  └─ WorldSessionLifecycle
        ├─ SidecarProcessManager → embed + launch livingmods-sidecar.jar
        ├─ SidecarClient         → async 127.0.0.1 IPC
        └─ TimeSyncBridge        → TIME_SYNC toward sidecar target time

Sidecar JVM
  ├─ SidecarMain / SidecarServer (loopback, single session)
  ├─ SessionHandler              → handshake, requests, typed physical outcomes
  ├─ SidecarSimulationHost
  │     ├─ WorldPlanStore.loadOrGenerate (must match expected plan hash)
  │     ├─ PersistenceCoordinator → CanonicalStore + WAL (schema 4)
  │     └─ SimulationEngine      → phased tick loop (+ ConstructionEngine intents, BanditryEngine)
  └─ DiagnosticsExporter
```

## Authority boundaries

| Concern | Authority |
|---------|-----------|
| World plan geometry | Frozen `plan.bin` after first successful plan for a world — **never mutated by live sim** |
| Civilization sim state | Sidecar `CanonicalWorldState` |
| Dynamic physical changes | `DynamicPhysicalState` inside canonical save (intents, dynamic structures, settlement geometry) |
| Block placement / chunk edits | Minecraft server thread materializers + `PhysicalReconciliationEngine` |
| Projected entities | Minecraft binders, driven by sidecar projection / map overlay plans |
| Player-visible map/dashboard | Server aggregates plan + sidecar live overlays; client does not read `WorldPlanCache` |

## Non-goals of the current wiring

- Worldgen does **not** call the sidecar.
- Materialization does **not** invent new plan geometry at place-time (heightmaps only).
- Citizen projection is **not** 1:1 with population; it is interest-capped (`physicalCitizenProjectionCap`).
- Optional third-party mods are **not** hard dependencies; see [MOD_INTEGRATIONS.md](MOD_INTEGRATIONS.md).

## Failure modes that are coded (not runtime-proven here)

- Sidecar plan-hash mismatch → host fails startup (`IOException`).
- Handshake version / identity mismatch → rejected handshake + error path.
- Sidecar death → bounded restart (3 attempts) then `FAILED`.
- Coordinated save pauses the sim at a phase boundary before writing.

Honesty note: after Blocks A–H the pieces are wired end-to-end in source. That is **INTEGRATED**, not **RELEASE_READY**. End-to-end game verification is the user’s checklist work.
