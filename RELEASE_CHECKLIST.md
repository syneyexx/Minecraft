# Release checklist

Unchecked by design. Mark items only after **you** observe them on a running Minecraft 1.21.1 + NeoForge build of this branch.

Agent/docs pass did **not** execute these. Static “code exists” ≠ passed.

Legend for notes: write `pass` / `fail` / `blocked` + short evidence (seed, coordinates, log line).

---

## A. Build & install

- [ ] `./gradlew buildAll -x test` completes
- [ ] `./gradlew :livingmods-sidecar:sidecarJar` produces embeddable jar
- [ ] NeoForge mod loads on 1.21.1 / loader 21.1.x without crash on title screen
- [ ] `LivingModsVersions` axes match `gradle.properties` documentation (protocol 3, worldgen 2, save schema 4, physical revision 2, mod 0.1.0)

## B. World creates

- [ ] New world creates successfully with LivingMods installed
- [ ] `world/livingmods/world.id` created once and stable across reloads
- [ ] `world/livingmods/worldplan/plan.bin` written (format full / worldgen 2)
- [ ] `world/livingmods/worldplan/seed.dat` matches overworld seed
- [ ] Plan meta counts (kingdoms/settlements/roads) are non-zero for default config
- [ ] Second boot loads the **same** plan hash (no silent regenerate)

## C. Sidecar starts & identity

- [ ] Sidecar process starts automatically (unless `-Dlivingmods.sidecar.enabled=false`)
- [ ] Sidecar binds loopback only (127.0.0.1)
- [ ] Handshake reaches READY
- [ ] **Same world plan hash** in Minecraft logs and sidecar logs
- [ ] Seed in sidecar launch args matches overworld seed
- [ ] World id UUID matches `world.id`
- [ ] Version mismatch (forced wrong protocol/schema) fails clearly
- [ ] Plan hash mismatch fails sidecar startup clearly
- [ ] Sidecar log file written under world sidecar dir
- [ ] Minecraft-appended sidecar process log written

## D. Cities / settlements exist

- [ ] `/livingmods locate capital` returns named hits with coords/distance
- [ ] `/livingmods locate city` (and town/village/hamlet) return plausible results
- [ ] Capitals/cities are physically present after chunks load (buildings, streets)
- [ ] Multiple kingdoms have distinct settlement clusters (not one blob)
- [ ] Specialized sites: `/livingmods locate mine` and `port` resolve when planned
- [ ] Ruins locatable and physically present
- [ ] Wizard Trees (if enabled): `/livingmods locate wizardtrees` works; cavern settlement materializes

## E. Roads continuous

- [ ] Inter-settlement roads appear as continuous paths across chunk borders
- [ ] Street networks inside cities connect to gates/arterials
- [ ] No large systematic gaps at chunk seams on the same planned road id
- [ ] Road materials follow culture palettes (not random wool chaos)

## F. Bridges

- [ ] Planned water crossings place bridge structures (deck + supports/railings as applicable)
- [ ] Bridge deck aligns with road endpoints (walkable continuity)
- [ ] Ford/wooden/stone variants appear where planned (spot-check)

## G. Walls / gates

- [ ] Walled settlements have a multi-block wall ring (not a single fence)
- [ ] Towers appear along the wall path
- [ ] Gates are openings with gatehouse structure
- [ ] Roads pass through gates (road continuity preserved)

## H. Chunk materialization hygiene

- [ ] Reloading the same chunks does not double-place / explode volume (idempotent attachments)
- [ ] Player-built blocks are not casually wiped by rematerialization
- [ ] Foreign mod machines (Create/SecurityCraft/etc. if present) are not casually overwritten
- [ ] First visit TPS hitch is acceptable; no permanent server hang

## I. Save / reload preserves sim identity

- [ ] After play, `canonical.bin` exists and grows/updates on save
- [ ] WAL file appends revisions
- [ ] Reload keeps same world id + plan hash
- [ ] Citizen identities persist (same ids/names when re-projected)
- [ ] Wars still present after reload (if any were active)
- [ ] Diplomacy/treaties still present
- [ ] Markets/stockpiles not reset to bootstrap defaults unexpectedly
- [ ] Dynasties / rulers consistent with pre-save dashboard
- [ ] Clean shutdown issues SAVE and completes without corrupting snapshot

## J. NPCs / citizens

- [ ] Approaching a city with sidecar ready spawns projected citizens
- [ ] Population projection respects cap (not thousands of entities)
- [ ] Leaving the area despawns projections
- [ ] Skins/professions render distinctly enough to tell roles apart
- [ ] AI/schedules do something other than stand frozen forever
- [ ] Death/despawn reports do not crash IPC

## K. Economy / society (observational)

- [ ] Dashboard economy tab shows non-zero market/stockpile signals after time passes
- [ ] Trade/shipments appear in summaries or events over time
- [ ] Famine/price stress can appear under shortage (or tools/unit path confirms logic)
- [ ] Government/succession does not crash sim over long ticks
- [ ] Diplomacy relation changes are visible over time
- [ ] War/siege abstractions can start and resolve without freezing sidecar
- [ ] Disease/migration systems can produce observable state changes
- [ ] History markers / rumors accumulate for major events

## L. Map / dashboard / commands

- [ ] `M` opens map; server payload arrives (not infinite “Loading…”)
- [ ] Map shows settlements; kingdom overlay works
- [ ] Road/trade overlay works
- [ ] War/disease/migration overlay mode toggles without crash
- [ ] Pan/zoom usable on desktop and does not hard-lock controls
- [ ] `F12` dashboard tabs: World, Kingdoms, Settlements, Economy, Diplomacy, War, History, Sidecar
- [ ] Sidecar tab shows live diagnostics (connected/pid/lag/queue) — not hardcoded healthy zeros when down
- [ ] Locate works via sidecar when connected and falls back to plan cache when appropriate

## M. Time sync & long advancement

- [ ] Sidecar sim time advances with Minecraft time sync
- [ ] Large time gaps catch up within budget (no unbounded freeze)
- [ ] After long advancement, save still succeeds
- [ ] Tools `bench` completes for a multi-hour accelerated run

## N. Config / disable switches

- [ ] `config/livingmods.properties` created with defaults
- [ ] Changing `surfaceKingdomCount` / radius on a **new** world affects plan density
- [ ] Existing worlds keep old plan despite config edits
- [ ] `-Dlivingmods.sidecar.enabled=false` prevents sidecar launch without breaking worldgen

## O. Optional mods coexistence (if you install them)

- [ ] Create present: availability logged; LivingMods still boots; Create blocks not casually overwritten
- [ ] Waystones present: availability logged; no crash; no false claim of LivingMods-placed waystones
- [ ] SecurityCraft present: protected blocks not overwritten by materializer
- [ ] Better Villages present: note collisions; game still playable (no formal merge expected)
- [ ] Macaw’s present: no adapter expected; note visual clashes only
- [ ] Guns mods present: no adapter expected; abstract wars unchanged

## P. Performance / errors

- [ ] No runaway entity counts near cities
- [ ] IPC timeout does not stall server tick loop
- [ ] Sidecar crash triggers ≤3 restarts then FAILED without killing Minecraft
- [ ] Error payloads visible in logs for handshake/save failures
- [ ] Memory stable enough for a multi-hour local session (qualitative)

## Q. Release gate (all must be true)

- [ ] No known data-loss on save/reload for identity/wars/citizens
- [ ] Plan hash identical in both processes for normal worlds
- [ ] Cities, continuous roads, bridges, walls/gates confirmed in at least one seed
- [ ] Map + dashboard usable
- [ ] Ledger updated honestly (no RELEASE_READY without the above)
- [ ] Version axes documented and consistent

---

When an item fails, file a note with seed + coords + log excerpt. Do not flip ledger rows to RELEASE_READY until the relevant Q-gate items pass.
