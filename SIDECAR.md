# Sidecar

Entry point: `com.livingmods.sidecar.SidecarMain`

```
--world-id <uuid> --port <int> --save-dir <path> --workers <int>
```

- Binds **127.0.0.1** only (`SidecarServer`)
- Accepts **one** Minecraft session at a time
- Logs to `<save-dir>/livingmods-sidecar.log`
- Persists via `PersistenceCoordinator` + `CanonicalStore`

The NeoForge mod embeds `livingmods-sidecar.jar` (built by `sidecarJar`) and launches it with the same `java.home` binary. Process logs append to `world/livingmods/sidecar/livingmods-minecraft.log`.
