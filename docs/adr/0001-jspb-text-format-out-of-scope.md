# ADR 0001 — JSPB and TEXT_FORMAT out of scope

## Status

Accepted — 2026-05-22.

## Context

Google's `conformance_test_runner` sends 4 wire formats to test:
PROTOBUF, JSON, JSPB, TEXT_FORMAT. To reach 100% PASS, Champollion must
provide a correct response for all 4 — or declare them as expected
failures in `--failure_list`.

- **JSPB**: Google's internal JavaScript Protocol Buffer encoding. Used by
  Closure Library / GWT on Google's frontend side. No reasonable Java consumer.
  Not documented in the public proto3 spec.
- **TEXT_FORMAT**: ad-hoc text format (`my_field: 42 nested { ... }`).
  Officially "debugging only" (see <https://protobuf.dev/reference/protobuf/textformat-spec/>).
  ~5 kLOC of implementation for zero practical business value — Java consumers
  already have native `Message.toString()`.

## Decision

**Champollion supports neither JSPB nor TEXT_FORMAT.**

The conformance tests that require them (`*.JspbInput.*`, `*.JspbOutput.*`,
`*.TextInput.*`, `*.TextOutput.*`) are listed in
`champollion-protobuf-tck/conformance-failure-list.txt` and passed to Google's
runner via `--failure_list`. They are counted as **expected failures** — they do
not affect the 100% PASS score for the supported scope.

`ConformanceRunner.handle` continues to respond `skipped` for these tests
(absent from `KNOWN_TYPES` or the JSPB/TEXT wire format) — Google's runner
classifies them according to the failure list.

## Consequences

**Positives**:
- Saves ~30 person-days of implementation (TEXT_FORMAT reader + writer
  + 4 subgrammars).
- No technical debt for formats that nobody will use in practice on the Java
  side (the public Google spec does not mention JSPB anywhere).
- Consistent with official `protobuf-java`, which also does not provide a strict
  TEXT_FORMAT reader.

**Negatives**:
- The conformance score displayed by the runner includes a line
  `87 expected failures` to explain to reviewers.
- If a future gRPC use case requires a text format (unlikely — gRPC is binary),
  it will have to be implemented.

## Rejected Alternatives

| Option | Why rejected |
|---|---|
| Implement TEXT_FORMAT reader only | Spec not fixed, ~5 kLOC, zero usage |
| Implement JSPB | No public consumer, proprietary Closure format |
| Fork Google's runner to remove these tests | Maintenance cost > failure-list cost |
