# Persistence

| Artifact | Location | Notes |
|----------|----------|-------|
| World plan meta | `world/livingmods/worldplan/plan.bin` | Seed + content hash; full plan regenerated deterministically |
| World seed marker | `world/livingmods/worldplan/seed.dat` | Written on server start |
| Canonical save | `<sidecar-save>/canonical.bin` | Schema `CANONICAL_SAVE_SCHEMA` |
| WAL | `<sidecar-save>/canonical.wal` | Append-only revision records |

Save barriers are triggered by `SAVE_REQUEST` over IPC.
