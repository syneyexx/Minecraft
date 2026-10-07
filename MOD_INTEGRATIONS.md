# Mod integrations

## Honest status

Optional integration support is **availability logging + soft placement refusal**, not real adapters.

| Mod | Code presence | What it actually does |
|-----|---------------|------------------------|
| Create | `CreateIntegration.available()` → `ModList.isLoaded("create")` | Logged at mod init. **No** contraption/schematic/train adapter. |
| Waystones | `WaystonesIntegration.available()` → `ModList.isLoaded("waystones")` | Logged at mod init. **No** waystone placement or teleport hooks. |
| JEI | Logged in `ModIntegrations.logAvailability()` | Detection only. **No** recipe/category plugin. |
| Macaw’s (doors/bridges/etc.) | **None** | No adapter. Not integrated. |
| SecurityCraft | No adapter class | `SafeChunkWriter` soft-refuses overwriting blocks whose id contains `securitycraft`. That is placement protection, not an integration. |
| Better Villages | **None** | No adapter. LivingMods uses its own settlement planner/materializer; do not assume coexistence is tuned. |
| Guns / firearm mods | **None** | No adapter, no combat bridging. Military sim is abstract armies in the sidecar. |

There are **no compile-time dependencies** on Create, Waystones, JEI, Macaw, SecurityCraft, Better Villages, or gun mods.

## Soft foreign-block refusal

`SafeChunkWriter.isForeignProtected` refuses casual overwrite of non-`minecraft:` / non-`livingmods:` blocks, with explicit string checks for ids containing:

- `create`
- `securitycraft`
- `immersiveengineering`
- `mekanism`
- `ae2`
- `refinedstorage`

Plus a general refuse for other foreign namespaces. This reduces griefing of other mods’ machines during materialization; it is **not** feature integration.

## Extending later

Put real soft hooks under `com.livingmods.neoforge.integrations` using reflection/`ModList` only. Until then, ledger status stays **IMPLEMENTING** (detection) rather than INTEGRATED feature work.

## Restrictions / expectations

- Do not claim Create factories feed LivingMods markets — they do not.
- Do not claim Waystones appear in planned cities — they do not.
- Do not claim Macaw furniture/bridges replace LivingMods roads/bridges — they do not.
- Do not claim SecurityCraft auto-defends settlements — it does not.
- Do not claim Better Villages structures merge with LivingMods urban lots — they are independent systems that may collide spatially.
- Do not claim gun mods affect abstract war resolution — they do not.
