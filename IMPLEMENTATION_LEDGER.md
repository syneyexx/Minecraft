# Implementation ledger

| Area | Status |
|------|--------|
| Protocol module | Implemented (`BinaryCodec`, payloads) |
| World planner pipeline | Implemented (planners + validator) |
| Simulation canonical state | Implemented with subsystem engines |
| Sidecar executable | Implemented (loopback IPC, single session) |
| NeoForge mod | Implemented (worldgen materialization, sidecar lifecycle, commands, UI keys) |
| Testkit / tools | Implemented |
| Canonical save graph | Incremental (header + hash; full entity restore TBD) |
| Optional mod hooks | Stub detection only |

Version axes: see `LivingModsVersions` (protocol, worldgen, canonical save schema).
