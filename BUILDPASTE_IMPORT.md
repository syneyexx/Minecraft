# BuildPaste Import

BuildPaste ([buildpaste.net](https://buildpaste.net)) is an **import source only**.
It is never contacted during gameplay or ordinary Gradle builds.

## Public discovery (M6.1 re-audit)

Unauthenticated tooling uses:

- `GET /search/__data.json?q=...` (SvelteKit data) — titles, slugs, premium flags
- HTML search pages as fallback
- `GET /build/<slug>/__data.json` — sampled public builds returned empty `nodes`

Findings:

- Mentions of “NBT” in search text refer to Minecraft version changelog copy, not file URLs
- “Download” links in results point at external map sites (e.g. minecraftmaps), not BuildPaste block payloads
- No public unauthenticated structure-payload / NBT / schematic download endpoint was found
- Premium and authenticated `/paste` flows are **not** bypassed

Batches are small (≤20), paced, cached under `.livingmods-cache/buildpaste/` (gitignored).

## Structure payloads

When a payload is not publicly available, the importer records:

```
SOURCE_UNAVAILABLE
```

Never fake `IMPORTED_EXACT` for authored content.

## Local / manual import

Developers may drop validated `.mls` files into an inbox directory:

```
culture-library import-local --culture avalon --dir .livingmods-cache/buildpaste/local-inbox
```

Pipeline: validate MLS → copy into culture structures → append manifest → resumable state.
Use `IMPORTED_SANITIZED` when the source was converted/sanitized; `IMPORTED_EXACT` only for
byte-faithful public imports (none available today).

## Production content

MineLife ships a large **authored** culture-distinct MLS library:

```
./gradlew :livingmods-tools:run --args='culture-library author --out livingmods-worldgen/src/main/resources/assets/livingmods' -x test
```

Authored assets use archetype-specific footprints (L/T/U/compound/round/elongated),
role interiors, and culture silhouettes — not palette swaps or alias inflation.

## CLI

```
culture-library search --query "viking house" [--limit 20]
culture-library import --culture avalon
culture-library import-local --culture avalon --dir path
culture-library resume
culture-library validate
culture-library report
culture-library author
culture-library names
```

## Statuses

`DISCOVERED`, `FETCHED`, `VALIDATED`, `IMPORTED_EXACT`, `IMPORTED_SANITIZED`,
`AUTHORED`, `DUPLICATE`, `INVALID_SOURCE`, `UNSUPPORTED_BLOCKS`, `WRONG_CATEGORY`,
`SOURCE_UNAVAILABLE`, `QUARANTINED`.
