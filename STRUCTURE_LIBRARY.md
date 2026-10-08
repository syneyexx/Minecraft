# Structure Library

MineLife M6 replaces palette-only procedural buildings with a **data-driven structure catalog**.

## Authority

| Concern | Location |
|---|---|
| Asset metadata + selection | `livingmods-worldgen` `StructureCatalog` |
| Local block payload (MLS1) | `MlsStructureFormat` |
| Planning selection | `ArchitectureGrammar` → persists `PlannedBuilding.assetId` |
| Chunk placement | `StructureAssetMaterializer` via `SafeChunkWriter` |
| Dynamic growth | `DynamicUrbanPlanner` + intent `provenance.assetId` |
| Procedural fallback | `BuildingMaterializer` (unchanged shells) |

**Never** re-roll `assetId` at chunk materialization time.

## Resource layout

```
livingmods-worldgen/src/main/resources/assets/livingmods/
  structures/
    catalog_index.txt
    shared/structure_manifest.json + *.mls
    ruins/
    bandit/
    infrastructure/
  cultures/<cultureKey>/
    culture.json
    naming.json
    structure_manifest.json
    structures/*.mls
```

Packaged into the mod via worldgen Jar-in-Jar. Runtime does **not** contact BuildPaste.

## Asset metadata

See `StructureAsset` — includes culture, role, archetype, size class, entrance,
foundation mode, provenance, content hash, import status.

Visual subtypes are **archetypes** (e.g. `STAVE_TEMPLE`, `YURT`), not new `BuildingRole` values.

## Selection

Deterministic weighted pick from catalog using:

- world seed + structure ordinal
- culture, role, tier, wealth, size hint
- recent-use penalty / residential dominance cap
- unique-per-settlement for palaces/keeps

If no fit: procedural `StructureRegistry` template fallback.

## MLS1 format

Deterministic palette + non-air placements. No entities, command blocks, or loot inventories.

## Content revision

`StructureCatalogVersions.CONTENT_REVISION` / `LivingModsVersions.STRUCTURE_CATALOG_REVISION`

Distinct from protocol, worldgen, and canonical save schema.
