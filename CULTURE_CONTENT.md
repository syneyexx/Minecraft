# Culture Content (M6)

Goal: each MineLife culture is recognizable **without reading its name**.

## Cultures

Surface: `nordheim`, `avalon`, `sahari`, `yamato`, `helvetia`, `amaru`,
`varangian`, `celtara`, `qin`, `atlantea`, `steppeborn`, `ironvale`

Special: `wizard_trees` (underground / cavern)

`CultureDefinition` remains the simulation authority (government, palette, roof/layout,
naming, military, biomes, professions, religion, law).

## What changed in M6

1. **Structure library** — ~90–100+ culture-owned assets each + shared infrastructure
2. **Planning** — selects `assetId` with real dimensions before lot commitment
3. **Materialization** — chunk-sliced MLS placement + procedural fallback
4. **Dynamic growth / player realms** — same catalog; `assetId` in intent provenance
5. **Naming** — expanded data files `cultures/<key>/naming.json`
6. **Skins** — pool path resolution with baseline fallback

## Silhouette intent

| Culture | Recognizable cues |
|---|---|
| Nordheim | Elongated timber halls, steep roofs, stone bases |
| Avalon | Timber-frame + stone, church/castle skyline |
| Sahari | Flat roofs, courtyards, domes |
| Yamato | Layered roofs / pagoda, timber compounds |
| Helvetia | Steep alpine roofs, stone ground floors |
| Amaru | Terraces, stepped temples |
| Varangian | Log towns, timber forts |
| Celtara | Roundhouses, palisades, grove temples |
| Qin | Courtyard compounds, pagodas, axial gates |
| Atlantea | Coastal villas, lighthouse, classical civic |
| Steppeborn | Yurts/gers, khan halls (not European stone cities) |
| Ironvale | Compact stone/deepslate, forge/mine, underground |
| Wizard Trees | Root/fungal/arcane cavern modules |

## Adding a culture later

1. Register `CultureDefinition` in `CultureRegistry`
2. Add `assets/livingmods/cultures/<key>/` with `culture.json`, `naming.json`, manifest + MLS
3. Add query plan under `culture-library/query_plans/`
4. Bump `STRUCTURE_CATALOG_REVISION` / regenerate catalog index
5. Increment `WORLDGEN_VERSION` only if new-world selection identity changes

## Adding an asset manually

1. Place `.mls` under the culture `structures/` folder
2. Append metadata to `structure_manifest.json`
3. Ensure `catalog_index.txt` lists the manifest
4. Run `culture-library validate` + `report`

## Versioning

| Axis | M6 value |
|---|---|
| WORLDGEN_VERSION | 3 (`PlannedBuilding.assetId`) |
| CANONICAL_SAVE_SCHEMA | 6 (`DynamicStructureRecord.assetId`) |
| STRUCTURE_CATALOG_REVISION | 1 |
| PROTOCOL_VERSION | 4 (unchanged) |
| PHYSICAL_CONTENT_REVISION | 2 (unchanged) |

Old plans without `assetId` → procedural fallback.
