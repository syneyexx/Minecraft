# Structure Library (M6.1)

MineLife uses a **data-driven structure catalog** of culture-distinct MLS1 assets.
M6.1 completes the library for production-usable worldgen: distinct geometry, asset-first
lot planning, landmark reservation, and strict validation.

## Authority

| Concern | Location |
|---|---|
| Asset metadata + selection | `StructureCatalog` |
| Identity | `contentHash` + palette-independent `geometryHash` |
| Validation | `CatalogValidation` (duplicate IDs/paths/geometry fail) |
| Local block payload (MLS1) | `MlsStructureFormat` |
| Planning selection | `ArchitectureGrammar` (asset-first) → `PlannedBuilding.assetId` |
| Settlement lots | `UrbanPlanner` landmark-first then asset-sized lots |
| Chunk placement | `StructureAssetMaterializer` (rotated chunk index) |
| Dynamic growth | `DynamicUrbanPlanner` asset-first + intent `provenance.assetId` |
| Procedural fallback | `BuildingMaterializer` — emergency/old-plan only |

**Never** re-roll `assetId` at chunk materialization time.

## Resource layout

```
livingmods-worldgen/src/main/resources/assets/livingmods/
  structures/
    catalog_index.txt
    shared/ | ruins/ | bandit/ | infrastructure/
  cultures/<cultureKey>/
    culture.json
    naming.json
    structure_manifest.json
    structures/*.mls
```

Packaged via worldgen Jar-in-Jar. Runtime does **not** contact BuildPaste.

## Identity layers

| Field | Meaning |
|---|---|
| `contentHash` | Exact MLS bytes (palette + blocks) |
| `geometryHash` | Occupied coords + dims + entrance + floor topology (palette ignored) |
| `uniquenessGroup` | Landmark uniqueness scope (e.g. `palace`, `keep`) |

Duplicate production `assetId` / `contentPath` / within-culture exact geometry are validation failures.

## Planning (asset-first)

1. Demand / district role
2. Select candidate assets
3. Choose allowed rotation (entrance toward street)
4. Compute rotated dimensions + clearance
5. Reserve lot
6. Persist `PlannedBuilding.assetId`

Landmarks (palace, keep, temple, market hall, …) are reserved **before** generic fill.

## Materialization

- Rotated per-chunk index — large castles are not fully scanned every chunk
- Foundation modes: `FLAT`, `CUT_AND_FILL`, `TERRACED`, `HILLSIDE`, `STILTS`, `UNDERGROUND`, `WATERFRONT`
- Entrance clearing uses authored entrance coordinates after rotation

## Content revision

`STRUCTURE_CATALOG_REVISION = 2` (M6.1 distinct library)
`WORLDGEN_VERSION = 4` (asset-first planning)

Protocol and canonical save schema unchanged unless their formats change.
