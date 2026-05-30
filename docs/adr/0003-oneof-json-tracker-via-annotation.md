# ADR 0003 — `oneofGroup` on `@ProtobufField` + stateless JSON HashMap tracker

## Status

Accepted — 2026-05-22.

## Context

Proto3 JSON spec §oneof: a JSON payload cannot contain multiple
property keys belonging to the same `oneof { ... }` group. Google's
runner tests this via `Required.Proto3.JsonInput.OneofFieldDuplicate.*` and
expects `parse_error`.

Wire (binary) side: no rejection — the spec allows multiple values for the
same oneof, **last-wins** (the last value read wins, other slots of the
same oneof are cleared).

Therefore, Champollion must:
- JSON side: explicit rejection of duplicates.
- Wire side: last-wins merge, clear other slots in the same group.

## Decision

1. **`@ProtobufField.oneofGroup() String default ""`** — enriched annotation.
   All fields with the same non-empty value form a oneof group.
   ```java
   @ProtobufField(number = 111, type = UINT32, explicitPresence = true,
                  oneofGroup = "oneof_field") Integer oneof_uint32
   ```

2. **JSON side (`ProtobufJsonRuntime.readMessage`)** — `HashMap<String,String>
   seenOneofs` local to `readMessage`. For each non-null property key read,
   `put(oneofGroup, key)`; if the oneof was already set, throw `IOException`
   mentioning the conflict. JSON `null` values are excluded from the tracker
   (consistent with "null = absent" §JSON canonical).

3. **Wire side (`RuntimeBinding.readMessage`)** — before writing a slot
   for a field with non-empty `oneofGroup`, clear all other slots in the same
   group (`slots[other.componentIndex] = null; hasValue[other.componentIndex] = false`).
   Implements the spec's "last-wins" semantics.

4. **No `ThreadLocal`, no `synchronized`** — the tracker is local
   to the `readMessage` stack frame, virtual-thread-safe by construction.

## Consequences

**Positives**:
- Correct JSON rejection (covers +5 Champollion tests + N conformance tests).
- Correct wire last-wins (covers `ValidDataOneof.X.MultipleValuesForDifferentField`).
- Zero runtime cost (HashMap allocation ~zero given the number of oneof fields per
  message).
- No modification to APT-generated classes — purely annotation metadata.

**Negatives**:
- 5th `@ProtobufField` parameter to know (already 4: number, type, packed,
  explicitPresence). Acceptable since oneof is rare.
- Exhaustive pattern matching on oneof is not represented in Java —
  consumer must check each slot for null. To be industrialized via sealed
  interface in a future M6+ evolution.

## Rejected Alternatives

| Option | Why rejected |
|---|---|
| Sealed interface `Oneof_X permits ...` at record level | Too intrusive, complicates record API |
| Modeling `Optional<Choice>` with sub-types | Java records don't support inheritance |
| Tracker in `ScopedValue` thread-confined | Unnecessary — a parser calls `readMessage` synchronously, no tracker sharing |
