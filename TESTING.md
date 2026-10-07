# Testing

## Agent disclaimer

**This documentation/agent pass did not execute tests, did not launch Minecraft, and did not run the sidecar.** Status claims in the ledger come from static code inspection after Blocks A–H. Humans must run the steps below.

There is **no CI / GameTest / GitHub Actions** workflow in this repository by design.

## Automated unit tests (local)

```bash
# Prefer focused modules for day-to-day checks
./gradlew :livingmods-common:test :livingmods-protocol:test :livingmods-simulation:test

# Worldgen determinism test can be slow
./gradlew :livingmods-worldgen:test

# Or everything
./gradlew testAll
```

Known unit coverage (exists in tree):

- `BinaryCodecTest` — protocol envelopes
- `EconomyFamineTest`, `SuccessionTest`, `SimulationDeterminismTest`, `InitialStateFactoryTest`, `CanonicalSaveRoundTripTest`
- `WorldPlannerDeterminismTest` (expensive)

Helpers:

- `livingmods-testkit` — `WorldPlanFixtures.smallPlan(seed)`, `SimulationBench.runTicks(…)`
- `livingmods-tools` — `plan`, `bench`

```bash
./gradlew :livingmods-tools:run --args="plan 42"
./gradlew :livingmods-tools:run --args="bench 42 24"
```

## Build artifacts needed for in-game checks

```bash
./gradlew buildAll -x test
./gradlew :livingmods-sidecar:sidecarJar
./gradlew :livingmods-neoforge:compileJava
```

Install/run the NeoForge mod jar via your usual NeoForge 1.21.1 client/server setup (ModDev or packaged jar from the neoforge module build output).

## Exact manual verification steps

### 1. World creation

1. Launch Minecraft 1.21.1 + NeoForge 21.1.x with LivingMods.
2. Create a **new** world (note seed).
3. Confirm logs show LivingMods loaded and (unless disabled) sidecar started with seed + planHash.
4. Confirm files appear:
   - `world/livingmods/world.id`
   - `world/livingmods/worldplan/plan.bin` (+ `meta.bin`, `seed.dat`)
   - `world/livingmods/sidecar/` (jar extract, logs, eventually `canonical.bin`)

### 2. Worldgen checks

1. `/livingmods locate capital` (also try `city`, `town`, `village`, `port`, `mine`, `ruin`, `wizardtrees`).
2. Travel to a capital/city; confirm buildings/roads exist in loaded chunks (not empty plains with only vanilla villages).
3. Follow a planned road across chunk borders — look for continuity.
4. Find a water crossing on a road — bridge deck/supports present.
5. Find a walled settlement — wall ring, towers, gate openings (not a one-block fence).
6. If Wizard Trees enabled: locate and inspect underground settlement materialization.

### 3. Sidecar / identity checks

1. With sidecar enabled, open dashboard (`F12`) → Sidecar tab: connected, pid, non-fake metrics.
2. Compare Minecraft log planHash with sidecar log planHash — **must match**.
3. Confirm handshake READY in logs.
4. Kill sidecar process externally once; confirm bounded restart attempts in logs.
5. Repeat with `-Dlivingmods.sidecar.enabled=false`: worldgen/materialization still work; live IPC features degrade cleanly.

### 4. Save / reload

1. Play until wars/diplomacy/citizens have state (advance time; visit settlements).
2. Save & quit.
3. Confirm `canonical.bin` (+ WAL growth) under sidecar save dir.
4. Reload same world.
5. Check: same `world.id`, same plan `contentHash`, wars/citizens/identity still present (dashboard + locate + projected NPCs).

### 5. NPC / projection checks

1. Approach a settlement with sidecar ready.
2. Confirm citizen entities spawn (capped, not entire population).
3. Leave area — entities despawn; no permanent leak.
4. Relog near same settlement — identities rebind (NBT citizen ids), not random new people every time.

### 6. Economy / society observations

1. Dashboard Economy / Kingdoms / Diplomacy / War tabs show data from sidecar summaries (not only zeros).
2. Over accelerated time (sleep / long session), markets and history markers change.
3. Dialogue path (if exposed in your build UI): intents EN/NL resolve to knowledge-bounded lines, not omniscient lore dumps.

### 7. Map / UI

1. Press `M` — map loads from server payload (not blank forever).
2. Cycle overlay modes (settlements/kingdoms/roads/wars).
3. Press `F12` — tabs render; Sidecar diagnostics update ~1.5s.

### 8. Performance observations (qualitative)

1. Note TPS while flying over dense cities during first materialization.
2. Note IPC lag field on dashboard under load.
3. Note memory of sidecar JVM after multi-hour catch-up.
4. Record anything that freezes the server thread (materialization must stay on server thread by design).

### 9. Long-time advancement

1. Leave the world running (or use tools `bench`) for many simulated hours/days.
2. Confirm catch-up remains bounded (`catchUpBudgetSteps`) and save still succeeds afterward.

## Recording results

Use [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md). Leave items unchecked until **you** pass them. Do not mark runtime items passed from static inspection alone.
