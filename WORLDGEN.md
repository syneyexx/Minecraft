# Worldgen

`WorldPlanner` runs a fixed pipeline: terrain sampling → kingdoms → settlements → territory map →
roads → urban layouts → road↔gate snap → resources/ruins → validation.

Planning is **bounded** to the Core Realm Zone (`LivingModsConfig.civilizationRadiusBlocks`,
default 12000). Frontier resources/ruins/camps use the same radius when `frontierEnabled` is true.
This does not imply infinite full-world civilization planning.

## TerrainProvider

- `com.livingmods.worldgen.terrain.TerrainProvider` — planning terrain API
  (surfaceHeight, oceanFloor, biome, climate, water/river/coast, slope, roughness, buildable helpers).
- `TerrainAnalyzer` — **deterministic synthetic** provider for tests/tools only.
- `MinecraftTerrainProvider` (neoforge) — samples overworld `ChunkGenerator.getBaseHeight`
  (`WORLD_SURFACE_WG` / `OCEAN_FLOOR_WG`), `BiomeSource.getNoiseBiome`, and biome climate settings
  during server-start planning before chunks exist.

`WorldPlanner` accepts `TerrainProvider` via constructor injection. Pure tools default to
`TerrainAnalyzer`. `WorldPlanCache.loadOrGenerate` injects `MinecraftTerrainProvider`.

Chunk **materialization** (Block C) stays in `CivilizationMaterializer` and uses heightmaps at
place-time; it must not invent new plan geometry.

## Persistence

`WorldPlanStore` persists the **full** immutable plan (kingdoms, territories, settlements, roads,
districts, lots, buildings, resources, ruins, camps, bridges, walls/gates) in a versioned compact
binary format under `world/livingmods/worldplan/plan.bin`.

On load of an existing world: **LOAD** the plan. Do **not** regenerate into a different plan when
algorithms change. Old worlds keep their civilization layout.

## Territories

`TerritoryMap` is a macrocell ownership raster (influence field + natural boundaries). Zones:
`CORE`, `BORDER`, `FRONTIER`, `CONTESTED`, `UNCLAIMED`. Kingdom adjacency is derived from border
cells and stored on `PlannedKingdom.adjacentKingdomIds`.
