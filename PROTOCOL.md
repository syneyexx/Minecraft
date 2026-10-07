# Protocol

Binary envelopes (`BinaryCodec`) with magic `LMDS`, versioned headers, and typed payloads (`MessageType`).

Handshake uses `HandshakePayload` with explicit protocol/worldgen/save schema checks. Version mismatch returns `HANDSHAKE_RESPONSE` + `ERROR` (`ErrorPayload.VERSION_MISMATCH`).

Requests include `LOCATE`, `GET_NEARBY_CITIZENS`, `GET_WORLD_SUMMARY`, `TIME_SYNC`, `SAVE_REQUEST`. Responses use `MessageType.RESPONSE` or `ERROR`. Sidecar pushes `EVENT` messages.

See `livingmods-protocol` sources for payload layouts.
