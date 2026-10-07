# LivingMods

LivingMods turns Minecraft into a world with **persistent simulated civilizations**.

- **Minecraft 1.21.1** · **NeoForge 21.1.x** · **Java 21**
- Deterministic civilization **worldgen** (kingdoms, settlements, roads, urban layouts, Wizard Trees)
- Local **simulation sidecar** (loopback IPC) owns long-term canonical state
- Minecraft owns physical projection (blocks, loaded NPCs, combat, UI)

> This world existed before you arrived. It keeps living when you leave. Your actions can change its future.

## Architecture

```
Minecraft / NeoForge  <--- 127.0.0.1 IPC --->  LivingMods Sidecar
physical world / UI                            canonical civilization sim
```

Worldgen does **not** depend on the sidecar. The sidecar consumes the world plan as initial canonical state.

## Modules

| Module | Role |
|--------|------|
| `livingmods-common` | Shared IDs, geo, cultures, config, domain models |
| `livingmods-protocol` | Versioned binary IPC (not JSON) |
| `livingmods-worldgen` | Deterministic planning + validators |
| `livingmods-simulation` | Economy, demography, government, war, disease, … |
| `livingmods-sidecar` | Executable simulation host |
| `livingmods-neoforge` | Mod entry, materialization, sidecar lifecycle, map/dashboard |
| `livingmods-testkit` | Local fixtures / benches |
| `livingmods-tools` | CLI (`plan`, `bench`) |

## Build (local)

```bash
./gradlew buildAll -x test
./gradlew :livingmods-protocol:test :livingmods-simulation:test
./gradlew :livingmods-sidecar:sidecarJar
./gradlew :livingmods-tools:run --args="plan 42"
```

There is **no CI / GameTest / GitHub Actions** in this repository by design — run and verify locally.

## In-game

| Input | Action |
|-------|--------|
| `M` | Top-down civilization map |
| `F12` | Civilization dashboard |
| `/livingmods locate capital` | Locate capitals (also: settlement, city, town, village, hamlet, mine, port, ruin, kingdom, wizardtrees) |

Disable sidecar: `-Dlivingmods.sidecar.enabled=false`

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
