# Worldgen

`WorldPlanner` runs a fixed pipeline: terrain sampling → kingdoms → settlements → roads → urban layouts → resources/ruins → validation.

Output is an immutable `WorldPlan` keyed by settlement ID. Chunks call `WorldPlan.sliceForChunk(ChunkCoord)`; they do not mutate the plan.

On disk, worlds store metadata under `world/livingmods/worldplan/` (`plan.bin`, `seed.dat`). The NeoForge `WorldPlanCache` loads or regenerates from the level seed.

Chunk materialization (`CivilizationMaterializer` + specialists) places roads, bridges, hollow
buildings from architecture grammar, walls/gates, farms/mines/ports, ruins, bandit camps, and
Wizard Trees caverns. Per-chunk attachment provenance (`PHYSICAL_CONTENT_REVISION`) makes
generation idempotent — already-applied chunks are never rewritten over player edits.
