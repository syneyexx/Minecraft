# Culture Content (M6.1)

Goal: each MineLife culture is recognizable **without reading its name**.

## Cultures

Surface: `nordheim`, `avalon`, `sahari`, `yamato`, `helvetia`, `amaru`,
`varangian`, `celtara`, `qin`, `atlantea`, `steppeborn`, `ironvale`

Special: `wizard_trees` (underground / cavern) — full-size library (~119 assets)

`CultureDefinition` remains the simulation authority (government, palette, roof/layout,
naming, military, biomes, professions, religion, law).

## What M6.1 completed

1. **~100+ distinct culture-owned assets each** (geometryHash uniqueness; no alias inflation)
2. **Asset-first planning** — select asset → rotation → dimensions → reserve lot
3. **Landmark-first** — palace/keep/temple/market hall reserved before generic fill
4. **Role reachability** — agriculture, mines, docks, gates, walls, ruins, waystations
5. **Chunk-index materialization** — rotated slice index; no full-castle scan per chunk
6. **Foundation modes** — FLAT / CUT_AND_FILL / TERRACED / HILLSIDE / STILTS / UNDERGROUND / WATERFRONT
7. **Naming** — curated culture pools (no gibberish syllable splicing; culture-specific suffixes/titles)
8. **Reports** — coverage uses geometry uniqueness; distinctness reports palette-independent overlap

## Silhouette intent

| Culture | Recognizable cues |
|---|---|
| Nordheim | Elongated timber halls, steep roofs, stone bases |
| Avalon | Timber-frame + stone, church/castle skyline |
| Sahari | Flat roofs, courtyards, domes |
| Yamato | Layered roofs / pagoda, timber compounds |
| Helvetia | Steep alpine roofs, stone ground floors, hillside |
| Amaru | Terraces, stepped temples |
| Varangian | Log towns, timber forts |
| Celtara | Roundhouses, palisades, grove temples |
| Qin | Courtyard compounds, pagodas, axial gates |
| Atlantea | Coastal villas, lighthouse, waterfront |
| Steppeborn | Yurts/gers, khan halls, dispersed spacing |
| Ironvale | Compact stone/deepslate, forge/mine |
| Wizard Trees | Root/fungal/arcane cavern modules |

## Versions

- `WORLDGEN_VERSION = 4` — asset-first planning changes new-world geometry
- `STRUCTURE_CATALOG_REVISION = 2` — distinct geometry library
- Canonical save schema / protocol unchanged (still 6 / 4)

## Adding content

1. Prefer `culture-library author` regeneration or `import-local` for `.mls`
2. Never invent `IMPORTED_EXACT` for authored shells
3. Bump `STRUCTURE_CATALOG_REVISION` when library identity changes
4. Bump `WORLDGEN_VERSION` only when new-world selection/planning identity changes
