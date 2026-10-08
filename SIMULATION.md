# Simulation

Canonical authority lives in `CanonicalWorldState` (kingdoms, settlements, households, citizens, family relations, markets, stockpiles, trade shipments, diplomacy, wars, sieges, armies, crime, epidemics, migration groups, ecology, technology, dynasties, factions, player reputation, history markers, rumors, emergent tasks).

## Bootstrap

`InitialStateFactory.fromWorldPlan(WorldPlan)` builds the first canonical graph from the frozen plan (citizens, households, markets, stockpiles, diplomacy seeds, etc.).

On sidecar restart, if `canonical.bin` loads and `planContentHash` matches the world plan, that save is used and `InitialStateFactory.attachWorldPlan` rebinds plan geometry. Otherwise a fresh bootstrap runs.

## Engine

`SimulationEngine` advances time with:

- **Phase 1** — regional work (parallel workers, bounded jobs)
- **Phase 2** — global settlement/kingdom coupling
- **Phase 3** — independent global systems once per tick schedule

Subsystems (all registered in the engine constructor):

| Engine | Responsibility (as coded) |
|--------|---------------------------|
| `DemographyEngine` | Birth/death/aging, household population |
| `ScheduleEngine` | Citizen schedule states |
| `EconomyEngine` | Multi-resource production/consumption/markets |
| `TradeEngine` | Road-routed shipments/caravans |
| `GovernmentEngine` | Rule, taxes, succession/dynasties |
| `ReligionEngine` | Festivals, legitimacy leanings |
| `DiplomacyEngine` | Relation matrix + treaties |
| `MilitaryEngine` | Abstract armies, wars, sieges, casus belli |
| `CrimeJusticeEngine` | Crime → verdict/sentence pipeline |
| `DiseaseEngine` | Epidemics (infectivity/severity/immunity fields) |
| `MigrationEngine` | Citizen migration + refugee outcomes |
| `EcologyEngine` | Data-driven multi-archetype species (not a Lotka–Volterra toy) |
| `TechnologyEngine` | Tech tree with prerequisites / schools |
| `ConstructionEngine` | Housing/profession/security/war demand → resource reservation → PhysicalIntent |
| `BanditryEngine` | Dynamic camps from security/trade/unrest/war causes |
| `DialogueEngine` | Template dialogue; EN + NL intent keywords; knowledge-bounded |
| `HistoryEngine` | Meaningful events → markers + rumors |
| `EmergentTaskEngine` | Tasks from canonical problems; completion mutates state |
| `PlayerSystemsEngine` | Reputation ladder + player-founded realm workflow |

Save barriers can pause the loop at a phase boundary (`pauseForSave` / `resumeAfterSave`).

## Time / catch-up

Sidecar host advances toward Minecraft time targets from `TIME_SYNC`. Large gaps use bounded catch-up (`catchUpBudgetSteps`, scheduler thresholds for hourly/daily coarsening). Tools CLI `bench` exercises accelerated ticks against a small fixture plan.

## Projection out of sim

Sidecar answers `GET_PHYSICAL_PROJECTION_PLAN`, `GET_NEARBY_CITIZENS`, market/kingdom/settlement summaries, dialogue context, map overlay, locate, and accepts `REPORT_PHYSICAL_OUTCOME` / `PLAYER_ACTION`. Minecraft never owns the canonical citizen graph.

## Status honesty

Engines are wired and schema-3 persistence covers the graph → **FUNCTIONAL** to **INTEGRATED**. Unit coverage exists for famine pricing, succession, determinism, save round-trip, and initial state — **this agent did not execute those tests**. Long-term balance, war readability, and player-facing outcomes are not RELEASE_READY.
