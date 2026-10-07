# Worldgen

`WorldPlanner` runs a fixed pipeline: terrain sampling → kingdoms → settlements → roads → urban layouts → resources/ruins → validation.

Output is an immutable `WorldPlan` keyed by settlement ID. Chunks call `WorldPlan.sliceForChunk(ChunkCoord)`; they do not mutate the plan.

On disk, worlds store metadata under `world/livingmods/worldplan/` (`plan.bin`, `seed.dat`). The NeoForge `WorldPlanCache` loads or regenerates from the level seed.

Chunk materialization is implemented in `CivilizationMaterializer` (roads, building footprints, simple walls).
