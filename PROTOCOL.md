# Protocol

Binary envelopes via `BinaryCodec` / `Envelope`:

- Magic `LMDS` (`0x4C4D4453`)
- Fixed 60-byte header: magic, protocol version, type, flags, msgId, requestId, simTicks, world UUID, payload length
- Typed payloads under `MessageType`
- Strings length-prefixed; max string length `65_536`

`PROTOCOL_VERSION = 2` (typed `PhysicalOutcomePayload` for `REPORT_PHYSICAL_OUTCOME`; string-map fallback retained in sidecar decode).

## Handshake

`HandshakePayload` carries:

- protocol / worldgen / canonical save schema versions
- mod + sidecar version strings
- world id, Minecraft seed, world plan hash, plan revision, world root path
- side (`MINECRAFT` / `SIDECAR`) and status (`READY` / `REJECTED` / `NEEDS_MIGRATION`)

Minecraft builds the request from `WorldIdentityContract`. Sidecar rejects mismatches (version, world id, seed, plan hash). Failure surfaces as `HANDSHAKE_RESPONSE` + `ERROR` (`ErrorPayload`, including `VERSION_MISMATCH` where applicable).

## Message types (as coded)

| Direction | Types |
|-----------|-------|
| Control | `HANDSHAKE_REQUEST/RESPONSE`, `HEARTBEAT`, `ERROR`, `SAVE_REQUEST/RESPONSE`, `SHUTDOWN`, `TIME_SYNC` |
| Minecraft → sidecar | `GET_SETTLEMENT_SNAPSHOT`, `GET_NEARBY_CITIZENS`, `GET_PHYSICAL_PROJECTION_PLAN`, `GET_MAP_OVERLAY`, `GET_DIALOGUE_CONTEXT`, `GET_MARKET_STATE`, `GET_KINGDOM_SUMMARY`, `GET_CONSTRUCTION_PLAN`, `GET_WORLD_SUMMARY`, `LOCATE`, `REPORT_PHYSICAL_OUTCOME`, `SUBSCRIBE_REGION`, `UNSUBSCRIBE_REGION`, `PLAYER_ACTION` |
| Sidecar → Minecraft | `RESPONSE`, `EVENT` |

`SessionHandler` implements the request set above. Payload layouts live in `HandshakePayload`, `RequestPayloads`, `EventPayload`, `PayloadIo`.

## Client behavior (NeoForge)

`SidecarClient`:

- Async request/response with allocated request ids
- Connection states including reconnecting/failed
- Configurable timeout from `LivingModsConfig.ipcRequestTimeoutMillis` (default 2000)

## Status honesty

Codec + handshake + request handlers are **INTEGRATED**; protocol unit tests exist under `livingmods-protocol` (**not executed in this docs pass**). Not RELEASE_READY until in-game session longevity and error paths are checklist-proven.
