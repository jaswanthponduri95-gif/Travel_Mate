# Catalog regression fix

## Root cause

The previous transport cleanup replaced catalog durations with `Check route`. The
catalog regression tests assert that the source catalog retains `tr-001 = 1h 45m`
and `tr-005 = 50m`, and that the database stores their durations as 105 and 50
minutes. Existing databases are seeded only when a catalog table is empty, so a
JSON-only correction would not repair an already populated MySQL database.

## Changes

- Restored the two duration metadata values in `catalog-data.json` and the browser
  fallback catalog in `js/data.js`.
- Added a narrowly scoped compatibility repair in `DatabaseInitializer` for only
  `tr-001` and `tr-005`, and only when the stored details still contain the legacy
  `Check route` placeholder. It updates both the JSON details and `duration_min`.
- Removed the fixed Bengaluru–Mysuru transport route examples and defaults from
  `transport.html`. Route insight copy now refers to the active trip generally.
- The transport UI continues to display the active trip's origin and destination;
  catalog duration/fare metadata is illustrative and not a live route quote.

## Verification in this environment

- `node --check` passed for all 14 frontend JavaScript files.
- `catalog-data.json` parsed successfully; the two expected durations were verified.
- The transport page was checked to contain no fixed Bengaluru/Mysuru route labels.
- Maven tests/build could not be run here because Maven Wrapper attempted to reach
  Maven Central and DNS/network access to `repo.maven.apache.org` was unavailable.
  Run `./mvnw.cmd test` and `./mvnw.cmd package` on Windows after extracting the ZIP.
