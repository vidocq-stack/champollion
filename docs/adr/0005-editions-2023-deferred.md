# ADR 0005 — Editions 2023 deferred to M6

## Status

Accepted — 2026-05-22.

## Context

Protocol Buffers Editions 2023 is the successor spec to proto2/proto3 (cf.
<https://protobuf.dev/editions/overview/>). The wire format remains identical
but introduces configurable `features` at file/message/field level:

- `features.field_presence ∈ {IMPLICIT, EXPLICIT, LEGACY_REQUIRED}`
- `features.enum_type ∈ {OPEN, CLOSED}`
- `features.repeated_field_encoding ∈ {PACKED, EXPANDED}`
- `features.utf8_validation ∈ {VERIFY, NONE}`
- `features.message_encoding ∈ {LENGTH_PREFIXED, DELIMITED}` ← reactivates
  proto2 groups (wire types 3/4)
- `features.json_format ∈ {ALLOW, LEGACY_BEST_EFFORT}`

Champollion already has:
- ✅ M4.1: `FeatureSet` model in `Descriptors` (records sealed enum).
- ✅ M4.2: `file → message → field` propagation in codegen.
- ✅ M4.3: runtime consumes `Utf8Validation.VERIFY` (`readStringRequireUtf8`,
  strict `writeStringNoTag`) and `FieldPresence.EXPLICIT` (via
  `@ProtobufField(explicitPresence=true)`).

## Decision

**M5 targets proto3 only** (`--maximum_edition PROTO3` on Google's runner side).
The ~80 Editions 2023 tests that the runner could send are excluded by
this flag — they don't appear in the current 100% PASS score.

**M6 will open Editions 2023 conformance** (target PASS on
`--maximum_edition 2023`):

1. **`MessageEncoding.DELIMITED`** runtime: reactivate wire types 3/4
   (START_GROUP / END_GROUP). Our `readTag` currently rejects these wire
   types (cf. M5.6.2 BadTag) — a lenient mode will be needed when the feature
   is active.

2. **`FieldPresence.LEGACY_REQUIRED`**: a non-present field at read time
   must fail (proto2 `required` semantics).

3. **`EnumType.CLOSED`**: enum value out of range = `MalformedProtobufException`
   instead of the current silent null. Also on codegen side — `lookupEnumOrNull`
   must become `lookupEnumOrThrow` in CLOSED mode.

4. **Edition 2023 `.proto` syntax** in `SchemaResolver`: we already support
   `edition = "2023"` but not all features overrides at field level.

5. **TestAllTypesEdition2023**: record to create (proto3/proto2 equivalent),
   registered in `KNOWN_TYPES`.

## Consequences

**Positives**:
- M5 delivers 100% PASS proto3 without dependence on Editions 2023 maturity
  (which is still "experimental" on Google's side).
- The `FeatureSet` model is already in place — M6 will be a runtime activation,
  not a from-scratch design.

**Negatives**:
- The displayed 100% PASS score doesn't cover Editions 2023.
- If the gRPC ecosystem massively migrates to Editions 2023 before M6, we'll
  have catching up to do.

## Rejected Alternatives

| Option | Why rejected |
|---|---|
| Implement Editions 2023 in M5 | Triples scope, delays 100% proto3 by ~1 month |
| Never support Editions | Official Google spec, eventually replaces proto2/proto3 |
| Failure-list Editions tests | Confusing — `--maximum_edition PROTO3` is the runner's proper option |
