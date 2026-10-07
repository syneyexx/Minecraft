# Sidecar

Entry point: `com.livingmods.sidecar.SidecarMain`

## Launch arguments (required)

```
--world-id <uuid>
--port <int>
--save-dir <path>
--world-root <path>
--seed <long>
--plan-hash <long>
--plan-revision <int>
--workers <int>          # optional; defaults to cores/3
```

NeoForge’s `SidecarProcessManager` extracts the embedded `livingmods-sidecar.jar` into `world/livingmods/sidecar/`, then launches it with the same `java.home` binary and the identity fields above.

## Network / session

- Binds **127.0.0.1 only** (`SidecarServer`)
- Accepts **one** Minecraft session at a time
- Logs to `<save-dir>/livingmods-sidecar.log`
- Process stdout/stderr from Minecraft’s launcher append to `world/livingmods/sidecar/livingmods-minecraft.log`
- Default port base `27564` + `(worldId.hashCode() & 0x7FF)`

Disable from Minecraft: `-Dlivingmods.sidecar.enabled=false`.

## Host startup contract

`SidecarSimulationHost`:

1. Loads/generates the world plan from `--world-root` via `WorldPlanStore`
2. **Fails hard** if `plan.contentHash() != --plan-hash`
3. Loads `canonical.bin` when present and plan hash matches; else bootstraps from plan and writes an initial save
4. Starts `SimulationEngine` on a daemon thread
5. Serves IPC through `SessionHandler`

## Lifecycle from NeoForge

`WorldSessionLifecycle`:

- On server start: load/create world id → plan → identity contract → start sidecar → handshake
- On tick: time sync, citizen projection, detect dead process → restart up to 3 times
- On stop: save request with timeout, then process stop

## Persistence façade

`PersistenceCoordinator` wraps `CanonicalStore` + `SaveBarrier`:

- `beginSaveBarrier` / `completeSaveBarrier` for coordinated saves
- WAL append of revision + content hash

## Diagnostics

`DiagnosticsExporter` + dashboard `GET_WORLD_SUMMARY` expose live metrics (queue depth, lag, revision, etc.). Dashboard must not invent hardcoded healthy zeros when disconnected.

## Status honesty

Process lifecycle, identity checks, and IPC handlers are **INTEGRATED** in source. Crash recovery, long-run stability, and performance under load are **not** RELEASE_READY without the manual checklist.
