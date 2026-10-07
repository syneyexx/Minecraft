# Mod integrations

Optional adapters live in `com.livingmods.neoforge.integrations` and use `ModList` checks only — no compile-time dependencies on Create, Waystones, JEI, etc.

**Honesty:** `CreateIntegration.available()` / `WaystonesIntegration.available()` are detection-only. They do **not** mean Create or Waystones gameplay features are implemented. Call sites must treat a missing (or detected-but-unwired) mod as a graceful no-op.

Extend this package with reflection-based hooks when a soft integration is needed.
