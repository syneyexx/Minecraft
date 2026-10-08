# Persistence

LivingMods persists three distinct artifacts. Do not conflate them.

## Artifacts

| Artifact | Path | Format / notes |
|----------|------|----------------|
| Stable world id | `world/livingmods/world.id` | UUID text; created once per world |
| World plan | `world/livingmods/worldplan/plan.bin` | Magic `LMPP`, format **2** (full plan), `WORLDGEN_VERSION`, seed, `contentHash`, counts, then `PlanBinaryCodec` payload |
| Plan meta (optional quick check) | `world/livingmods/worldplan/meta.bin` | Seed/hash/version/counts without full payload |
| Seed marker | `world/livingmods/worldplan/seed.dat` | `long` seed written on server start |
| Canonical save | `world/livingmods/sidecar/canonical.bin` | Magic `LMCS`, schema **5** (reads ≥3), full graph + DynamicPhysicalState + player gameplay |
| WAL | `world/livingmods/sidecar/canonical.wal` | Append-only revision + contentHash records |
| Sidecar logs | `…/sidecar/livingmods-sidecar.log`, `livingmods-minecraft.log` | Process logs |

Sidecar save directory is `server.getWorldPath(ROOT)/livingmods/sidecar` (same world folder tree).

## World plan rules

- On first create: plan with `MinecraftTerrainProvider` (in-game) or `TerrainAnalyzer` (tools), then save full `plan.bin`.
- On later loads: **LOAD** the existing plan. Algorithms must not silently replace geometry for an existing world.
- Sidecar and Minecraft must agree on `contentHash`. Sidecar startup compares `--plan-hash` to loaded plan; mismatch aborts.

**Correction vs older docs:** the plan is **not** “regenerated every boot from seed.” Regeneration happens only when no readable full plan exists.

## Canonical save (schema 5)

`CanonicalSaveFormat` encodes/decodes:

- kingdoms, settlements, households, citizens (family/housing/work ids, schedule)
- family relations
- stockpiles, markets, shipments
- diplomacy pairs/treaties, wars, sieges, armies
- crime (incl. optional player offender ids in schema ≥ 5), epidemics, migration groups
- ecology, technology, dynasties, factions, player reputation (kingdom floats always)
- history markers / bounded event lists
- time ticks, save revision, plan content hash, world/session UUIDs
- **DynamicPhysicalState** (schema ≥ 4): physical intents, dynamic structures, settlement geometry
- **Player gameplay addendum** (schema ≥ 5): settlement reputation, faction standing, ruled kingdoms, legal records, knowledge/cartography, realm policies, emergent task lifecycle

Schema 3–4 saves still load (missing schema-5 fields default empty). New writes use schema 5.

`CanonicalStore.saveBarrier` pauses the bound `SimulationEngine` at a phase boundary, writes snapshot, appends WAL.

## Dynamic physical vs WorldPlan

| Artifact | Mutability | Role |
|----------|------------|------|
| `plan.bin` | Immutable after first successful plan | Initial world blueprint |
| `DynamicPhysicalState` in `canonical.bin` | Live | Construction, expansion, camps, founding, damage after initial gen |

Never rewrite live geometry into `plan.bin`.

Triggered by IPC `SAVE_REQUEST` and host shutdown paths.

## Chunk materialization provenance

Not a separate file: NeoForge chunk attachments store applied `PHYSICAL_CONTENT_REVISION` + plan hash so rematerialization is idempotent when revision/hash unchanged.

## Status honesty

Full-graph encode/decode and WAL paths are **INTEGRATED** (unit round-trip test exists; **not run in this pass**). Save/reload preserving wars/citizens/identity across a real Minecraft session is a **RELEASE_CHECKLIST** item — not claimed RELEASE_READY here.
