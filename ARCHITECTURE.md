# Architecture

LivingMods splits **deterministic planning** (worldgen module) from **canonical simulation** (simulation + sidecar) and **physical presentation** (NeoForge).

```
Minecraft Server (NeoForge)
  ├─ WorldPlanCache → WorldPlanner (no sidecar required)
  ├─ ChunkMaterializationHandler → CivilizationMaterializer
  └─ SidecarProcessManager → SidecarClient (async, 127.0.0.1)

Sidecar JVM
  ├─ SidecarServer (single session)
  ├─ SessionHandler (protocol)
  ├─ SidecarSimulationHost → SimulationEngine
  └─ PersistenceCoordinator → CanonicalStore + WAL
```

World generation and chunk materialization never wait on the sidecar. When the sidecar is up, the mod uses IPC for locate queries, snapshots, and live events.
