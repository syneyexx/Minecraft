# Worldgen

`WorldPlanner` runs a fixed pipeline:

1. Terrain sampling (`TerrainProvider`)
2. Kingdom planning
3. Settlement planning (hierarchy + specialization)
4. Territory mapping (`TerritoryMap` macrocell raster)
5. Road graph + bridge planning (terrain-aware A*)
6. Urban districts / lots / buildings (`UrbanPlanner` + `ArchitectureGrammar`)
7. Road↔gate snap (inside road/urban phases)
8. Resources, ruins, frontier camps (when `frontierEnabled`)
9. Validation (`WorldPlanValidator`)

Planning is **bounded** to the Core Realm Zone (`LivingModsConfig.civilizationRadiusBlocks`, default `12000`). This is not infinite full-world civilization planning.

`WORLDGEN_VERSION = 4` (M6.1: asset-first lot planning + landmark reservation + culture catalog).
Old plans (v2/v3) load; missing/`assetId`-less buildings use procedural fallback.
Bumping does **not** silently rewrite existing worlds — see persistence below.

## TerrainProvider

| Class | Role |
|-------|------|
| `TerrainProvider` | Planning API: surface/ocean height, biome, climate, water/river/coast, slope, roughness, buildable helpers |
| `TerrainAnalyzer` | Deterministic **synthetic** provider for tools/tests |
| `MinecraftTerrainProvider` (neoforge) | Samples overworld `ChunkGenerator.getBaseHeight` (`WORLD_SURFACE_WG` / `OCEAN_FLOOR_WG`), `BiomeSource.getNoiseBiome`, biome climate — used at server-start planning before chunks exist |

`WorldPlanner` accepts `TerrainProvider` via constructor. Pure tools default to `TerrainAnalyzer`. `WorldPlanCache.loadOrGenerate` injects `MinecraftTerrainProvider`.

Chunk **materialization** stays in `CivilizationMaterializer` and uses heightmaps at place-time. It must not invent new plan geometry.

## Cultures

`CultureRegistry`: twelve surface cultures + underground **Wizard Trees** (architecture, naming, government leanings, biome preferences). Wizard Trees settlements are planned by `WizardTreesPlanner` and placed by `WizardTreesMaterializer`.

M6.1 structure catalog (`StructureCatalog` / MLS1): `UrbanPlanner` reserves landmarks, then
`ArchitectureGrammar` selects assets **before** committing lot size (role → asset → rotation →
dimensions + clearance → lot). Entrances align to streets. `StructureAssetMaterializer` uses
rotated chunk indexes and real foundation modes. Procedural `BuildingMaterializer` is emergency
fallback only. See [STRUCTURE_LIBRARY.md](STRUCTURE_LIBRARY.md) and [CULTURE_CONTENT.md](CULTURE_CONTENT.md).

## Territories

`TerritoryMap` is a macrocell ownership raster (influence + natural boundaries). Zones include `CORE`, `BORDER`, `FRONTIER`, `CONTESTED`, `UNCLAIMED`. Kingdom adjacency is derived from border cells onto `PlannedKingdom.adjacentKingdomIds`.

## Physical materialization (NeoForge)

`CivilizationMaterializer` orchestrates per-chunk slices:

- `RoadMaterializer` / street networks
- `BridgeMaterializer` (ford / wooden / stone / major)
- `BuildingMaterializer` (hollow shells + interiors by role)
- `WallMaterializer` (path, towers, gate openings + gatehouses — not a single fence)
- `ResourceSiteMaterializer` (farms / mines / ports)
- `RuinMaterializer`, `BanditCampMaterializer`
- `WizardTreesMaterializer` for underground settlements

`SafeChunkWriter` refuses to casually overwrite non-air foreign mod blocks (including Create / SecurityCraft / several tech mods) and non-`livingmods:` blocks. Idempotency uses chunk attachments keyed by `PHYSICAL_CONTENT_REVISION` + plan `contentHash`.

## Persistence

`WorldPlanStore` writes the **full** immutable plan under:

- `world/livingmods/worldplan/plan.bin` — magic `LMPP`, format 2, versioned header + `PlanBinaryCodec` payload
- `world/livingmods/worldplan/meta.bin` — quick seed/hash/counts
- `world/livingmods/worldplan/seed.dat` — seed marker written on server start

On load of an existing readable plan: **LOAD**. Do **not** regenerate into a different plan when algorithms change. Corrupt/unreadable payloads may fall through to regenerate; that is a recovery path, not normal operation.

Stable world UUID: `world/livingmods/world.id` via `LivingModsWorldIds`.

## Tools

```bash
./gradlew :livingmods-tools:run --args="plan 42"
```

Prints seed, kingdom/settlement/road counts, and `contentHash` using synthetic terrain.

## Status honesty

Planning + persistence + materializers are **INTEGRATED** in code. Continuity of roads/bridges/walls across chunk borders, aesthetic quality, and Minecraft-terrain fidelity are **not** marked RELEASE_READY — they need the manual checklist in [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).
