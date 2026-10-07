# Simulation

`CanonicalWorldState` holds kingdoms, settlements, citizens, markets, diplomacy, wars, and bounded history.

`InitialStateFactory.fromWorldPlan` bootstraps state from a `WorldPlan`.

`SimulationEngine` advances time in phased ticks (regional phase 1, global phase 2, independent phase 3) using subsystem engines under `com.livingmods.simulation.engine`.

The sidecar runs `SimulationEngine` on a background thread (`tickHour` loop) decoupled from Minecraft ticks.
