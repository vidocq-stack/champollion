# ADR 0002 — `Map<K,V>` detection via Java type + `FieldType.MAP` + `mapKey`/`mapValue`

## Status

Accepted — 2026-05-22.

## Context

Proto3 spec §maps encode `map<K,V> name = N;` as a `repeated Entry { K key = 1; V value = 2; }`
length-delimited. Champollion must represent these fields in Java on annotated
records (without exposing an `Entry` class to the consumer).

Java → proto ambiguity: a Java `Map<Integer, Integer>` could be `map<int32, int32>`,
`map<uint32, uint32>`, `map<sint32, sint32>`, etc. A mechanism is needed to
resolve this ambiguity, just as `@ProtobufField(type=INT32 vs UINT32)` does for
scalars.

## Decision

Three coordinated additions to the existing `@ProtobufField` annotation:

1. **`FieldType.MAP`** — new enum value, `wireType = LEN`, non-packable.
2. **`mapKey()` / `mapValue()`** — `FieldType` default `STRING`, ignored unless
   `type == MAP`. Resolve ambiguity on key and value side like `type()` does
   for scalars.
3. **Java type auto-detection** — `RuntimeBinding.buildPlan` introspects the
   `RecordComponent`: if `type == MAP` and the record component is not
   `java.util.Map`, → `IllegalArgumentException`. The `V` is extracted via
   `ParameterizedType.getActualTypeArguments()[1]` for `MESSAGE` cases
   (lookup of the sub-message `BindingPlan`).

Usage:

```java
@ProtobufField(number = 56, type = FieldType.MAP,
               mapKey = FieldType.INT32, mapValue = FieldType.INT32)
Map<Integer, Integer> map_int32_int32
```

## Consequences

**Positives**:
- Consistent with the `List<X>` pattern already detected (`resolveListElementType`).
- No exposed `MapEntry` class → clean runtime API.
- Single `@ProtobufField` annotation remains the only metadata → simple to
  learn.
- `LinkedHashMap` as default → deterministic wire roundtrip.

**Negatives**:
- 3 new annotation parameters to remember (`type=MAP, mapKey, mapValue`).
- Static APT codegen does not yet support `FieldType.MAP` — TestAllTypesProto3
  had to remove `@ProtobufStatic` to fall back to reflective runtime. To be
  industrialized in M6.

## Rejected Alternatives

| Option | Why rejected |
|---|---|
| Separate `@ProtobufMap(keyType, valueType)` annotation | 3rd annotation to remember, minimal gain |
| Exposed `MapEntry<K,V>` class + `List<MapEntry<K,V>>` | Breaks Java `Map<K,V>` idiom, clutters API |
| Infer `mapKey`/`mapValue` from Java type (Integer → INT32) | Breaks UINT32/SINT32/INT32 ambiguity on same Integer |
