# LivingMods

LivingMods is a Minecraft **1.21.1** / **NeoForge 21.1.x** / **Java 21** multi-module project that adds deterministic civilization worldgen, an optional simulation sidecar, and a NeoForge mod that materializes plans into the world.

## Modules

| Module | Role |
|--------|------|
| `livingmods-common` | Shared models, IDs, cultures, config |
| `livingmods-protocol` | Binary IPC envelopes and payloads |
| `livingmods-worldgen` | Deterministic `WorldPlanner` pipeline |
| `livingmods-simulation` | Canonical civilization state + engines |
| `livingmods-sidecar` | Loopback IPC server + persistence |
| `livingmods-neoforge` | In-game mod (worldgen, sidecar, UI, commands) |
| `livingmods-testkit` | Plan fixtures and simulation bench helpers |
| `livingmods-tools` | CLI (`plan`, `bench`) |

## Build

```bash
./gradlew buildAll
```

Sidecar fat jar:

```bash
./gradlew :livingmods-sidecar:sidecarJar
```

Tools CLI:

```bash
./gradlew :livingmods-tools:run --args="plan 42"
```

NeoForge run configs are provided by ModDevGradle (`client`, `server`).

## Docs

- [ARCHITECTURE.md](ARCHITECTURE.md)
- [WORLDGEN.md](WORLDGEN.md)
- [SIMULATION.md](SIMULATION.md)
- [SIDECAR.md](SIDECAR.md)
- [PROTOCOL.md](PROTOCOL.md)
- [PERSISTENCE.md](PERSISTENCE.md)
- [MOD_INTEGRATIONS.md](MOD_INTEGRATIONS.md)
- [TESTING.md](TESTING.md)
- [IMPLEMENTATION_LEDGER.md](IMPLEMENTATION_LEDGER.md)
