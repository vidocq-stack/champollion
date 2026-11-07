# BUG.md — champollion

Suivi des bugs reproductibles. Convention : voir `../CLAUDE.md` (workspace root).

Statuts : `OPEN` → `INVESTIGATING` → `FIXED` (commit hash) → `CLOSED`.

---

## BUG-20260611-01 — JSON-B runtime: nested-POJO field aborts the enclosing object read inside a collection element

- **Date** : 2026-06-11
- **Statut** : FIXED (PR #3 — escape hatch restricted to user-deserializer readers)
- **Module touché** : `champollion-jsonb` — `RuntimeReadRegistry.readObjectAndApply`
- **Symptôme** : `JsonbException: Expected object, got KEY_NAME` when deserializing
  `List<Order>` (via a parameterized `Type`) where `Order` has a nested-POJO
  field (`shipping: Address`) followed by more properties (`total`, `priority`).
  The same `Order` deserialized as the root value works. This is why the
  JSON-B read LARGE benchmark was historically `(n/c)` in `BENCH.md`.
- **Reproduction minimale** :
  ```java
  Type t = new TypeReference<List<Workloads.Order>>(){}.getType(); // jackson TypeReference or any ParameterizedType
  jsonb.fromJson(Workloads.largeJson(1), t); // throws "Expected object, got KEY_NAME"
  ```
- **Hypothèse de cause** : after a member is read, `readObjectAndApply` does
  `if (p.currentEvent() == Event.END_OBJECT) break;` (escape hatch for custom
  `JsonbDeserializer`s that consume up to the parent's END_OBJECT). A plain
  nested-POJO member also leaves `currentEvent() == END_OBJECT` (its own),
  so the enclosing object read breaks early; the unread keys (`total`...) then
  surface as `KEY_NAME` where the collection expects the next `START_OBJECT`.
  The guard cannot distinguish "child consumed its own END_OBJECT" from
  "custom deserializer overran into the parent's END_OBJECT" — it needs depth
  tracking (e.g. compare parser depth before/after `w.apply`) instead of the
  bare event-type check.
- **Investigations** :
  - 2026-06-11 : reproduced on `main` (pre-P12) and on the P12 lazy-tokenizer
    branch — identical failure, NOT a P12 regression (verified by stashing).
    Found while extending the JSON-B read benchmark coverage to LARGE.
  - 2026-06-11 : root cause confirmed, and the bug is WORSE than the original
    symptom: the root case silently drops every property following a
    nested-POJO member (`Order.total`/`priority` read back as `0.0`/`false`,
    no exception). Fix: `BindingReader.UserDeserializer` marker on the readers
    built from `@JsonbTypeDeserializer`; `readObjectAndApply` applies the
    `currentEvent()==END_OBJECT` early-exit only to those (internal readers
    consume exactly their value by contract). Regression tests in
    `JsonbNestedPojoRegressionTest` (root + collection + greedy-deserializer
    escape hatch). Validated: full reactor green, JSON-B TCK 289/295
    (reference score).

---

## BUG-20261026-01 — JSON-B: the `@JsonbStatic` bindings of a Vidocq application are never used

- **Date** : 2026-10-26
- **Statut** : FIXED (branch `fix/jsonb-static-bindings-app-layer`, Vidocq/vidocq#382)
- **Module touché** : `champollion-jsonb` — `ChampollionJsonbBuilder.buildChampollion`, `StaticBindings.fromServiceLoader`, `RuntimeBindingRegistry.resolveClass`
- **Symptôme** : in a Vidocq application whose records are annotated `@JsonbStatic` (with
  `champollion-codegen-apt` on the annotation processor path), the build generates `Todo$$Binding` and lists it in
  `META-INF/services/io.vidocq.champollion.jsonb.spi.JsonbBinding`, but at runtime every response still goes through
  `ChampollionJsonb.toJson` → `RuntimeBindingRegistry.resolveRecord` / `collectionWriter` / `dynamicWriter` (about
  20 % of the CPU samples at 40 000 req/s, measured on 0.4.0-RC1 by the benchmark session, BENCH-20261026-01).
  No generated binding frame appears.
- **Reproduction minimale** : `StaticBindingsClassLoaderTest` — a loader that is a child of Champollion's and
  carries the services file; the thread's context class loader is that child. Before the fix the binding view is
  empty.
- **Cause** : the services files were read through `ChampollionJsonbBuilder.class.getClassLoader()` only. In a
  Vidocq application Champollion sits in the parent layer (the application class loader) and the application's
  classes and `META-INF/services` in the application layer, a child loader that the parent cannot see. A
  `provides ... JsonbBinding` clause in the application's `module-info.java` changes nothing, because the lookup
  never reaches that layer.
- **Investigations** :
  - 2026-10-26 : on a request thread of a Vidocq application (`vidocq dev`, 0.4.0-RC1) the context class loader is
    the application layer's `VaubanClassLoader`, the very loader of the application's records, while Champollion's
    loader is the JDK application class loader. So the context class loader reaches the bindings.
  - 2026-10-26 : fix = `StaticBindings.fromServiceLoader` takes several loaders, in order, a loader given twice or
    `null` counting once, and the builder passes its own loader then the thread's context class loader. Red then
    green on `StaticBindingsClassLoaderTest`; the 514 tests of the reactor pass.
  - 2026-10-26 : the benchmark session (JFR at 30 000 req/s on the PR's first commit) showed the profile
    unchanged, so the lookup was only the first of two causes. **Second cause**: the static binding was consulted at
    the root only, by the raw class of the value. A `List<Todo>` (what a JAX-RS method returns) has the raw class
    `List`, and its elements, map values and nested members are written by `RuntimeBindingRegistry`, which never read
    `StaticBindings`: so `resolveRecord`, `collectionWriter` and `dynamicWriter` stayed on the hot path.
    Fix: `RuntimeBindingRegistry.resolveClass` asks `StaticBindings` first, as the root does, and every per-class writer
    is cached by `ClassValue`. Tests first in `JsonbStaticBindingTest.BelowTheRoot` (list element, map value, record
    component, `Object`-typed element red; a type with no binding stays on the runtime, green).
  - A binding is looked up by the exact `Class`: with a type loaded twice (the application module is in the boot layer
    and in the application layer of a packaged run) a binding of one copy is never applied to the other. The binding
    found through the application layer's loader names the application layer's `Todo`, the one Cassini writes.
  - Not covered: reading. `RuntimeReadRegistry` does not consult `StaticBindings` either, so the elements of a
    `List<Todo>` request body are still read by the runtime; a root-typed body (`TodoInput`) uses its binding.
  - Like the root before it, a static binding ignores the `JsonbConfig` (naming strategy, null values, date format):
    it now ignores it below the root too.
  - Open follow-up, not part of this fix: Cassini keeps its default `Jsonb` in a static holder
    (`CassiniJsonbReaderWriter.DefaultJsonb`) created on the first request. After an in-JVM dev reload the holder
    still carries the bindings of the first application layer: the reloaded records are other `Class` objects, so
    the lookup misses and falls back to the runtime registry (correct, slower), and the first layer stays reachable.
    To be tracked on the Cassini side.

## BUG-20261027-01 — Protobuf: a map value of enum type is decoded by ordinal, encoded by `@ProtoEnumValue`

- **Date** : 2026-10-27
- **Statut** : FIXED (test/protobuf-coverage)
- **Module touché** : `champollion-protobuf` — `RuntimeBinding.readScalarTyped` (ENUM case)
- **Symptôme** : a map whose values are an enum with explicit `@ProtoEnumValue` numbers round-trips wrong: the writer emits the declared number, the reader looks the number up as an ordinal. A value declared `-1` comes back as `null`.
- **Reproduction minimale** : a message with `Map<String, Status>` where `Status` has `@ProtoEnumValue(-1) UNKNOWN`; encode `{"a": UNKNOWN}`, decode it.
- **Hypothèse de cause** : the map value reader uses `values()[n]` instead of the `@ProtoEnumValue` lookup the field reader uses.
- **Investigations** :
  - 2026-10-28 : reproduced by `MapEnumValueTest` (declared numbers -1 and 10 read back as `null`, and the number 1,
    which no constant declares, read back as the constant of ordinal 1). Fix: the map value reader uses
    `EnumValueMap.byValue`, like the field reader. Regression test: `MapEnumValueTest`.

## BUG-20261027-02 — JSON-B: the "date with offset only" fallback can never parse

- **Date** : 2026-10-27
- **Statut** : FIXED (branch `test/jsonb-coverage`)
- **Module touché** : `champollion-jsonb` — `RuntimeReadRegistry.parseAsZonedDateTime`
- **Symptôme** : `"2020-01-02+01:00"` or `"2020-01-02Z"` read into a `Calendar` (or a `Calendar`/`Date` member with the
  default `@JsonbDateFormat`) is rejected with a `DateTimeParseException`. That is the very form the writer uses for a
  calendar at midnight (`ISO_OFFSET_DATE`, JSON-B 3.0 §3.5.1), so such a calendar did not round-trip.
- **Reproduction minimale** : `jsonb.fromJson("\"2020-01-02+01:00\"", Calendar.class)`; or write
  `GregorianCalendar.from(ZonedDateTime.of(2020, 1, 2, 0, 0, 0, 0, ZoneId.of("Europe/Paris")))` and read it back.
- **Cause** : the fallback built `s + "T00:00:00" + last 6 chars`, appending the offset after a string that already
  ends with it, so the result was never a valid date-time; and a `Z` offset is one character, not six.
- **Investigations** :
  - 2026-10-28 : fix = parse with `DateTimeFormatter.ISO_OFFSET_DATE` and take the start of that day at that offset.
    Red then green on `CalendarDateWithOffsetRegressionTest` (numeric offset, `Z`, midnight round trip).

## BUG-20261027-03 — APT: the generated static binding of a `List<Float>` does not compile

- **Date** : 2026-10-27
- **Statut** : FIXED 2838b5d (fix/list-float-binding, Vidocq/champollion#87)
- **Module touché** : `champollion-codegen-apt` — `JsonbStaticProcessor` (list element reading, `readLeaf`)
- **Symptôme** : a `@JsonbStatic` record with a `List<Float>` component makes the annotation processor emit code that fails to compile.
- **Reproduction minimale** : `@JsonbStatic record Sample(List<Float> values) {}` compiled with the processor.
- **Hypothèse de cause** : `readLeaf` returns a `double` for `Float` elements, which the generated code adds to a `List<Float>` without a narrowing conversion.
- **Investigations** :
  - 2026-11-07 : confirmed — the same applied to `Optional<Float>` and `Map<String, Float>`. `readLeaf` now reads a `Float` with `getBigDecimal().floatValue()`. Test `CodegenContainersTest.boxed_float_elements_round_trip_in_list_optional_and_map`.

## BUG-20261027-04 — Protobuf JSON: `UInt64Value` is written signed

- **Date** : 2026-10-27
- **Statut** : FIXED (test/protobuf-coverage)
- **Module touché** : `champollion-protobuf` — `ProtobufJsonRuntime`, the JSON writer and reader of `Wrappers.UInt64Value` and `Wrappers.UInt32Value`, at top level and as a field
- **Symptôme** : a value above `Long.MAX_VALUE` is written as a negative number.
- **Reproduction minimale** : write a `UInt64Value` holding `-1L` (2^64 - 1 unsigned): the JSON says `"-1"`, the proto3 JSON mapping expects `"18446744073709551615"`.
- **Hypothèse de cause** : the writer uses `Long.toString` instead of `Long.toUnsignedString`.
- **Investigations** :
  - 2026-10-28 : confirmed by `WrapperJsonMappingTest`, and the same holds for `UInt32Value` (`-1` instead of
    `4294967295`). The reader had the mirror defect: `"18446744073709551615"` and `4294967295` were rejected
    (signed parsing), and a negative value was accepted. Fix: the wrapper writers share one switch that writes
    `Long.toUnsignedString` / `Integer.toUnsignedLong`, and the readers use the unsigned range checks of the
    `uint64` / `uint32` fields. Regression tests: `WrapperJsonMappingTest.uint64_*` and `uint32_*`.

## BUG-20261027-05 — Protobuf: `BytesValue` compares and prints the array, not the bytes

- **Date** : 2026-10-27
- **Statut** : FIXED (fix/sonar-overall-reliability-security, Sonar S6218)
- **Module touché** : `champollion-protobuf` — `wkt.Wrappers.BytesValue` (and `wkt.Any.toString`)
- **Symptôme** : two `BytesValue` holding the same bytes are not equal and hash differently; `toString` prints `[B@…`. `Any` already compared its bytes but printed the array identity.
- **Reproduction minimale** : `new BytesValue(new byte[]{1}).equals(new BytesValue(new byte[]{1}))` is `false`.
- **Hypothèse de cause** : a record with a `byte[]` component inherits the array's identity `equals`/`hashCode`/`toString`. Fixed by overriding them with `Arrays`.

## BUG-20261028-01 — JSON-B: a null `JsonStructure` member cannot be read

- **Date** : 2026-10-28
- **Statut** : FIXED (quality/sonar-zero)
- **Module touché** : `champollion-jsonb` — `RuntimeReadRegistry.Builtins.lookup` (JSON-P types)
- **Symptôme** : a JSON `null` read into a member of type `JsonStructure` fails with
  `JsonbException: Failed to instantiate record ...: argument type mismatch` (cause: `ClassCastException`,
  `JsonValueImpl` cannot be cast to `JsonStructure`).
- **Reproduction minimale** : `record R(JsonStructure s) {}`, `jsonb.fromJson("{\"s\":null}", R.class)`.
- **Hypothèse de cause** : the reader shared by `JsonStructure` and `JsonValue` returns `JsonValue.NULL` for a JSON
  `null`. That is right for `JsonValue` but `JsonValue.NULL` is not a `JsonStructure`; the `JsonStructure` reader
  should return `null`, like the `JsonObject` and `JsonArray` ones.
- **Investigations** :
  - 2026-10-28 : not fixed with the coverage tests: `Builtins.lookup` has a cognitive complexity of 188, so touching
    it brings Sonar's S3776 back as a new issue; the fix wants that method split by family of types first.
  - 2026-10-28 : once `Builtins.lookup` was split by family, fix = `JsonStructure` shares the reader of `JsonString`
    and `JsonNumber` (null for a JSON `null`); only `JsonValue` reads it as `JsonValue.NULL`. Red then green.
- **Regression test** : `JsonbBuiltinTypesTest.jsonp_values_read_explicit_nulls`.

## BUG-20261028-02 — JSON-B: an untyped integer beyond the `long` range throws `ArithmeticException`

- **Date** : 2026-10-28
- **Statut** : FIXED (branch `test/jsonb-coverage`)
- **Module touché** : `champollion-jsonb` — `RuntimeReadRegistry.dynamicValue`
- **Symptôme** : `jsonb.fromJson("123456789012345678901234567890", Object.class)` (or such a number inside a raw
  `List`/`Map`) fails with `java.lang.ArithmeticException: BigInteger out of long range`, not even a
  `JsonbException`. Integers outside the `int` range but inside the `long` one already read as a `BigInteger`.
- **Reproduction minimale** : `UntypedBigIntegerRegressionTest`.
- **Cause** : to decide between `Integer` and `BigInteger`, the reader converted the value with `longValueExact()`,
  which throws for anything wider than 64 bits.
- **Investigations** :
  - 2026-10-28 : fix = compare the bit length of the `BigInteger` with `Integer.SIZE`: an `Integer` when it fits, a
    `BigInteger` of any size otherwise (same results as before inside the `long` range). Red then green on
    `UntypedBigIntegerRegressionTest`, including both `int` boundaries.

## BUG-20261028-03 — JSON-B: a date pattern breaks reading a `Duration` or `Period` bean property

- **Date** : 2026-10-28
- **Statut** : FIXED (branch `test/jsonb-coverage`)
- **Module touché** : `champollion-jsonb` — `RuntimeReadRegistry.dateReaderFor`
- **Symptôme** : with `JsonbConfig.withDateFormat(...)`, or a `@JsonbDateFormat` on the property, a bean with a
  `Duration` or `Period` setter fails to read with `Setter argument mismatch ... got java.lang.String`. The writer
  leaves these types in their ISO 8601 form whatever the pattern, so the reader could not read back what it wrote.
- **Reproduction minimale** : `DurationWithDateFormatRegressionTest` (config pattern, property pattern).
- **Cause** : the bean reader built a pattern-based date reader for every "date-like" type, `Duration` and `Period`
  included; that reader has no case for them and returned the raw string (or failed on a pattern that is not a
  `DateTimeFormatter` one).
- **Investigations** :
  - 2026-10-28 : fix = no date reader for `Duration` / `Period` (the built-in ISO reader applies), as on the writer
    side. The setter / getter / field lookup moved to `dateFormatMember` to keep `dateReaderFor` under Sonar's
    complexity threshold. Red then green on `DurationWithDateFormatRegressionTest`.

## BUG-20261028-04 — JSON-B: a visibility strategy cannot make a private field without accessor visible

- **Date** : 2026-10-28
- **Statut** : FIXED (quality/sonar-zero)
- **Module touché** : `champollion-jsonb` — `RuntimeBindingRegistry.resolvePojo` (and the bean reader)
- **Symptôme** : with `JsonbConfig.withPropertyVisibilityStrategy(s)` where `s.isVisible(Field)` returns `true`, a
  private field that has no getter is not written (and not read); only public fields and getters are candidates.
- **Reproduction minimale** : a bean with `private String hidden = "h"` and no accessor, a strategy whose
  `isVisible(Field)` is always `true`: `toJson` gives `{}` instead of `{"hidden":"h"}`
  (`JsonbConfigVariantsTest.a_visibility_strategy_from_the_config` leaves that member unasserted).
- **Hypothèse de cause** : the strategy only filters the properties found by the default discovery (public fields
  and getters); JSON-B 3.0 §4.6.1 lets the strategy decide for every field and method.
- **Investigations** :
  - 2026-10-28 : not fixed with the coverage tests: the discovery lives in `resolvePojo`, a method well above Sonar's
    complexity threshold, which must be split first.
  - 2026-10-28 : write side fixed once `resolvePojo` was split: when a strategy applies, the non-public instance
    fields of the hierarchy that no getter or public field covers (most specific declaration first, not static,
    transient, `@JsonbTransient` nor hidden by a non-public getter, and only if they can be made accessible) are
    offered to the strategy with the public members. Red then green on
    `VisibilityStrategyPrivateFieldRegressionTest`; `JsonbConfigVariantsTest.a_visibility_strategy_from_the_config`
    now asserts the private member on write.
  - 2026-10-28 : read side fixed in `RuntimeReadRegistry.applyVisibility`: when a strategy applies, the non-public,
    non-final instance fields of the hierarchy that no setter or public field covers are offered to it as
    `FieldSetter`s before the visibility filter. Red then green on the three read tests of
    `VisibilityStrategyPrivateFieldRegressionTest` (regression test for both sides).

## BUG-20261028-05 — JSON-B: the properties after a member read by a `JsonbDeserializer` are dropped

- **Date** : 2026-10-28
- **Statut** : FIXED (quality/sonar-zero)
- **Module touché** : `champollion-jsonb` — `RuntimeReadRegistry.readObjectAndApply` (escape hatch of BUG-20260611-01)
- **Symptôme** : in a bean, a property with `@JsonbTypeDeserializer` whose deserializer reads an object value exactly
  (from its `START_OBJECT` to its own `END_OBJECT`, the usual pattern) ends the reading of the enclosing object: the
  properties that follow it in the document are silently left at their defaults.
- **Reproduction minimale** : `JsonbSerializersAndAdaptersTest.Annotated` read from
  `{"publicShape":{"size":5},"shape":{"size":1}}`: `shape` stays `null`.
- **Hypothèse de cause** : after a user deserializer, the bean reader stops when `currentEvent() == END_OBJECT`, to
  accept the TCK deserializers that consume the parent's `END_OBJECT`; it cannot tell that case from a deserializer
  that consumed only its own object. It needs depth tracking (count the starts and ends the deserializer consumes).
- **Investigations** :
  - 2026-10-28 : not fixed with the coverage tests: `readObjectAndApply` is above Sonar's complexity threshold and the
    fix touches the TCK-sensitive escape hatch; the tests read such a property last.
  - 2026-10-28 : once `readObjectAndApply` was split, fix = depth tracking. The deserializer of a bean member gets
    a `DepthTrackingParser` that counts the structures it opens and closes (`next()`, `getObject()`, `getArray()`,
    `getValue()`, `skipObject()`, `skipArray()`); the bean stops only when that count goes below zero, i.e. when the
    deserializer consumed the bean's own `END_OBJECT` (the TCK `AnimalListDeserializer` pattern). Red then green.
- **Regression test** : `UserDeserializerMemberRegressionTest`, and
  `JsonbSerializersAndAdaptersTest.per_property_serializer_deserializer_and_adapter` (the repro above).

## BUG-20261028-06 — JSON-B: an `@JsonbSubtype` that is not a subtype is accepted on read

- **Date** : 2026-10-28
- **Statut** : FIXED (quality/sonar-zero)
- **Module touché** : `champollion-jsonb` — `RuntimeReadRegistry.polymorphicReader`
- **Symptôme** : `@JsonbTypeInfo(key = "@t", value = @JsonbSubtype(alias = "z", type = NotAPet.class))` on
  `interface Animal`, `NotAPet` not implementing it: `toJson(..., Animal.class)` is rejected (JSON-B 3.0 §4.8), but
  `fromJson("{\"@t\":\"z\"}", Animal.class)` returns a `NotAPet`, which the caller then fails to cast.
- **Reproduction minimale** : `JsonbGetterDiscoveryTest.an_alias_that_is_not_a_subtype_is_rejected` (write side only).
- **Hypothèse de cause** : the reader validates each subtype with `validateTypeInfo(sub.type(), info)`; the bearer of
  the annotation is looked up from the subtype, is not found when the subtype does not implement it, and the
  assignability check is skipped. It should validate from the annotated type.
- **Investigations** :
  - 2026-10-28 : not fixed with the coverage tests: `polymorphicReader` and `validateTypeInfo` are both above Sonar's
    complexity threshold.
  - 2026-10-28 : once `polymorphicReader` was split, fix = it also validates the `@JsonbTypeInfo` from the type being
    read, whose bearer is always found, as the writer does from the concrete type; `validateTypeInfo` is unchanged.
    Red then green.
- **Regression test** : `JsonbGetterDiscoveryTest.an_alias_that_is_not_a_subtype_is_rejected` (now both sides).

## BUG-20261028-11 — Protobuf: the stream encoder under-counts the bytes it has written

- **Date** : 2026-10-28
- **Statut** : FIXED (test/protobuf-coverage)
- **Module touché** : `champollion-protobuf` — `CodedOutputStream.StreamEncoder.writeRawBytes`
- **Symptôme** : `getTotalBytesWritten()` of a `CodedOutputStream` over an `OutputStream` returns less than the bytes
  actually written once a block larger than the free space of its buffer is written.
- **Reproduction minimale** : `CodedOutputStream.newInstance(out, 4)`, `writeRawByte(0)`, `writeRawBytes(12 bytes)`,
  `flush()`: 13 bytes reach `out`, `getTotalBytesWritten()` says 10.
- **Cause** : when the block does not fit, the first chunk fills the end of the buffer and is flushed, but only the
  remainder was added to the counter.
- **Regression test** : `CodedStreamsRoundTripTest.stream_encoder_counts_every_byte_written`.

## BUG-20261028-12 — Protobuf JSON: a map value of a well-known type cannot be read back

- **Date** : 2026-10-28
- **Statut** : FIXED (test/protobuf-coverage)
- **Module touché** : `champollion-protobuf` — `ProtobufJsonRuntime.readMapValue` (MESSAGE case)
- **Symptôme** : a `map<string, google.protobuf.Timestamp>` (or any other well-known type as the map value) is written
  in the well-known type's JSON form, `{"k":"1970-01-01T00:00:01Z"}`, but reading that JSON fails with
  `IOException: Expected message object`.
- **Reproduction minimale** : `ProtobufJson.fromJson(Values.class, "{\"times\":{\"k\":\"1970-01-01T00:00:01Z\"}}")`
  where `times` is `@ProtobufField(type = MAP, mapValue = MESSAGE) Map<String, Timestamp>`.
- **Cause** : the writer goes through the well-known type mapping for map values, the reader only accepted a JSON
  object and parsed it field by field.
- **Regression test** : `MapJsonTest.well_known_type_values_are_read_from_their_json_form`.

## BUG-20261028-13 — Protobuf JSON: a nested message loses its maps and writes its unknown enum numbers twice

- **Date** : 2026-10-28
- **Statut** : FIXED (test/protobuf-coverage)
- **Module touché** : `champollion-protobuf` — `ProtobufJsonRuntime.writeMessageBody`
- **Symptôme** : in the JSON of a message nested in another one (or flattened in an `Any`), (1) a map field is
  silently dropped, (2) an explicit-presence field holding its default value is dropped, (3) the unknown enum
  numbers kept in the nested `UnknownFieldSet` are written as a second member of the same name after the other
  fields, `{"roles":["ADMIN"],"login":"x","role":7,"roles":[9]}`, instead of `{"role":7,"roles":["ADMIN",9],...}`.
  The top-level message was written correctly in all three cases.
- **Reproduction minimale** : `ProtobufJson.toJson(new Config(new Settings(Map.of("k", "v"), 0)))` gives
  `{"settings":{}}`; see `NestedMessageJsonTest`.
- **Cause** : nested messages went through a second field loop, `writeMessageBody`, that predates map fields,
  explicit presence and the unknown-enum merge of the top-level writer (its scalar switch has no MAP case).
- **Fix** : `writeMessage` opens the object and delegates to `writeMessageBody`, which now runs the top-level field
  loop, so both levels share one writer.
- **Regression test** : `NestedMessageJsonTest`.

## BUG-20261028-14 — Protobuf APT: the static parser decodes an enum by ordinal, not by `@ProtoEnumValue`

- **Date** : 2026-10-28
- **Statut** : FIXED (test/protobuf-coverage)
- **Module touché** : `champollion-protobuf-codegen` — `ProtobufStaticProcessor` (the enum read it generates)
- **Symptôme** : with `@ProtobufStatic`, an enum field whose constants declare `@ProtoEnumValue` numbers reads back
  wrong: the writer emits the declared number, the generated parser indexes `values()` with it, so a declared
  number reads as `null` or as another constant.
- **Reproduction minimale** : `enum Status { @ProtoEnumValue(0) NONE, @ProtoEnumValue(7) ACTIVE }` in a
  `@ProtobufStatic` record: encode `ACTIVE`, parse with the generated `$$Parser`: `null`.
- **Cause** : the generated `lookupEnumOrNull(values, ordinal)` helper predates `@ProtoEnumValue`, which the
  runtime binding honours through `EnumValueMap`.
- **Fix** : the processor emits one decoder per enum type, a switch from the declared number (or the ordinal when
  none is declared) to the constant, computed at compile time; unknown numbers still read as `null`.
- **Regression test** : `ProtobufStaticProcessorTest.generated_parser_decodes_enums_by_their_declared_number`.

## BUG-20261028-15 — Protobuf codegen: an unterminated block comment leaks its last character as a token

- **Date** : 2026-10-28
- **Statut** : FIXED (test/protobuf-coverage)
- **Module touché** : `champollion-protobuf-codegen` — `internal.ProtoLexer.skipWhitespaceAndComments`
- **Symptôme** : a `.proto` file ending inside a `/* ...` comment is not rejected; the last character of the comment
  is lexed as a token, so `/* tail }` closes the enclosing message and the file parses.
- **Reproduction minimale** : `new ProtoLexer("message M {}\n/* tail }").tokenize()` yields an `RBRACE` token after the
  message instead of an error.
- **Cause** : the comment loop stops one character before the end of the input when no `*/` is found, and the
  closing step is skipped without reporting anything, leaving that character to the tokenizer.
- **Fix** : the block comment skip moves to its own method and reports an unterminated comment, as protoc does.
- **Regression test** : `ProtoGrammarEdgeTest.an_unterminated_block_comment_is_rejected`.

## BUG-20261028-16 — JSON-P: `skipObject()` / `skipArray()` do nothing inside a container

- **Date** : 2026-10-28
- **Statut** : OPEN (found while writing the BUG-20261028-05 regression tests)
- **Module touché** : `champollion-jsonp` — `ChampollionJsonParser.skipUntilCloseOf`, `JsonValueParser.skipObject` /
  `skipArray`
- **Symptôme** : both parsers skip only when the current event is `START_OBJECT` / `START_ARRAY`. The JSON-P 2.1
  javadoc says the parser is advanced to the `END_OBJECT` (`END_ARRAY`) of the enclosing container whenever it is in
  object (array) context, i.e. anywhere between the container's start and its end.
- **Reproduction minimale** : parser over `{"a":1,"b":{"c":2},"d":3}`, `next()` twice (`START_OBJECT`, `KEY_NAME`),
  `skipObject()`, `next()` returns `VALUE_NUMBER` instead of the spec's position after the skip (`END_OBJECT` is the
  current event, `hasNext()` is false).
- **Hypothèse de cause** : `skipUntilCloseOf` returns at once unless `lastEvent` is the opening event; it should
  use the parser's nesting state (`nestBits` / `depth`), taking the look-ahead of a previous `hasNext()` into
  account. The in-memory `JsonValueParser` has the same shortcut.
- **Investigations** :
  - 2026-10-28 : reproduced with `ChampollionJsonParser` (jshell). Not fixed in the Sonar clean-up branch: it changes
    parser behaviour checked by the JSON-P TCK and the JSON-B deserializer escape hatch, so it wants its own change.

## BUG-20261029-01 — JSON-B: a generated binding ignored the `JsonbConfig`

- **Date** : 2026-10-29
- **Statut** : FIXED 2026-10-29 (branch `fix/static-binding-config`, generated-code audit)
- **Module touché** : `champollion-jsonb` — `ChampollionJsonb` (static-first lookup at the root, and below it through
  `RuntimeBindingRegistry.resolveClass`)
- **Symptôme** : a type with a generated `$$Binding` (`@JsonbStatic`) was always written and read by it, whatever the
  `JsonbConfig`. With `withNullValues(true)`, `new Person(null, "Hugo")` gave `{"lastName":"Hugo"}` instead of
  `{"firstName":null,"lastName":"Hugo"}`; with `LOWER_CASE_WITH_UNDERSCORES`, `firstName` instead of `first_name`.
  The same type without a generated binding honoured both.
- **Reproduction minimale** : `JsonbStaticBindingTest.ConfigurationAware` (`champollion-jsonb`).
- **Hypothèse de cause** : the generated code implements the default mapping, and the lookup consulted it before any
  configuration.
- **Investigations** :
  - 2026-10-29 : found by the generated-code audit. Fix: `ChampollionJsonb` drops the generated bindings when the
    configuration sets anything they do not implement (null values, naming/order/visibility strategy, date format,
    locale, binary strategy, adapters, serializers, deserializers, strict I-JSON, required creator parameters,
    `jsonb.fail-on-unknown-properties`); formatting and encoding keep them. Documented in `concepts.adoc`.

## BUG-20261029-02 — JSON-B runtime mapping: inherited accessors chosen by their least specific declaration

- **Date** : 2026-10-29
- **Statut** : FIXED 2026-10-29 (branch `fix/inherited-accessors`, generated-code audit)
- **Module touché** : `champollion-jsonb` — `RuntimeBindingRegistry.discoverGetters`, `RuntimeReadRegistry.discoverSetters`,
  `jsonbNameFromMethod`, `annotatedMember`
- **Symptôme** :
  - `Base { protected String getName() }` and `Sub extends Base { @Override public String getName() }`: `toJson(new
    Sub())` gave `{}`. The protected declaration hid the property although the override is public; the setter side
    did the same (`fromJson` left the property unset).
  - A getter inherited from an interface `default` method was never written.
  - `@JsonbProperty("full_name")` (or `@JsonbDateFormat`, `@JsonbNumberFormat`) on an overridden getter was lost when
    the override did not repeat it.
- **Reproduction minimale** : `JsonbGetterDiscoveryTest` (`a_public_override_of_a_protected_getter_is_written`,
  `a_public_override_of_a_protected_setter_is_read`, `a_default_getter_of_an_interface_is_written`,
  `an_override_keeps_the_property_name_of_the_overridden_getter`).
- **Hypothèse de cause** : the discovery walked `getDeclaredMethods()` up the superclasses and judged every
  declaration, overridden or not; interfaces were never walked; annotations were read on the most specific method only.
- **Investigations** :
  - 2026-10-29 : found by the generated-code audit. Fix: `accessorCandidates` keeps each signature's most specific
    declaration along the superclasses, then the interfaces' default methods; an interface's default getter sorts with
    the first class that implements it. `overriddenWith` finds an annotation on the nearest overridden method when
    the override does not carry it.

## BUG-20261029-03 — Static processor: nested records crash javac, quoted or duplicate names, stale services entries

- **Date** : 2026-10-29
- **Statut** : FIXED (fix/static-processor-robustness)
- **Module touché** : `champollion-codegen-apt` — `JsonbStaticProcessor`; `champollion-jsonb` — `internal.StaticBindings`
- **Symptôme** :
  - a `@JsonbStatic` record nested in a class makes the processor throw a `ClassCastException` out of javac
    (`getEnclosingElement()` cast to `PackageElement`) instead of reporting an error;
  - a `@JsonbProperty` name containing `"` or `\` is written unescaped into a `case` label, so the generated source
    does not compile; two components with the same JSON name give a duplicate `case` label, again in generated code;
  - an incremental build keeps the services entry of a record that lost `@JsonbStatic` (or was deleted), and
    `ServiceLoader` then fails on the missing class, which made `StaticBindings.fromServiceLoader` throw and disable
    every static binding of the loader.
- **Reproduction minimale** : `JsonbStaticProcessorDiagnosticsTest` (4 cases) and
  `StaticBindingsClassLoaderTest.aStaleServiceEntryIsSkipped`.
- **Cause** : unchecked cast, raw string concatenation of the JSON name in the generated switch, the services file
  merged with its previous content without checking the entries, and a `ServiceLoader` loop with no per-provider
  error handling.
- **Fix** : a nested record is a compile error ("top-level"); the labels go through the literal escaper; a duplicate
  JSON name is a compile error naming both components; the processor drops entries whose record is gone or no
  longer `@JsonbStatic`; `fromServiceLoader` skips (and logs at WARNING) a provider that fails to load.
- **Regression tests** : see reproduction. JSON-B TCK 295/0/5 in runtime and `--static` modes.

## BUG-20261029-04 — JSON-B adapters are never CDI beans when CDI lives in the application's class loader

- **Date** : 2026-10-29
- **Statut** : FIXED (fix/cdi-resolver-loader)
- **Module touché** : `champollion-jsonb` — `internal.CdiResolver`
- **Symptôme** : an `@ApplicationScoped` adapter, serializer or deserializer is instantiated with `new` instead of
  being looked up in the CDI container (JSON-B 3.0 §5) when the CDI API is visible to the application's class loader
  but not to Champollion's, as in a Vidocq application where the container sits in the application layer. A CDI
  lookup that failed for another reason than a missing container was also dropped without a trace.
- **Reproduction minimale** : `CdiResolverTest.aBeanIsResolvedThroughTheCdiApiItsOwnClassLoaderSees`: a loader that
  defines a minimal CDI API and a scoped bean, a child of the platform loader.
- **Cause** : the CDI handles were resolved once, in a static initializer, with the single-argument `Class.forName`,
  so through Champollion's own class loader.
- **Fix** : the handles are resolved from the bean class's loader, once per class (`ClassValue`). A missing
  container still falls back silently; any other CDI failure is logged at WARNING before the no-argument fallback.
- **Regression tests** : `CdiResolverTest` (5 cases). JSON-B TCK 295/0/5 in runtime and `--static` modes.

## BUG-20261029-05 — JSON-B: a property typed by a type variable a subclass binds is read as a generic value

- **Date** : 2026-10-29
- **Statut** : FIXED (fix/type-variables)
- **Module touché** : `champollion-jsonb` — `internal.RuntimeReadRegistry`
- **Symptôme** : with `class Base<T> { public T value; public List<T> items; }` and
  `class Dto extends Base<Address> {}`, `fromJson(json, Dto.class)` fills `value` and the elements of `items` with
  `LinkedHashMap`s instead of `Address` instances (JSON-B 3.0 §3.11); the caller then fails with a
  `ClassCastException` far from the cause. Setters typed by `T` behave the same.
- **Reproduction minimale** : `JsonbTypeVariableTest` (3 cases).
- **Cause** : the readers were built from the member's own generic type; a `TypeVariable` fell back to the dynamic
  reader, which maps a JSON object to a `Map`.
- **Fix** : `GenericTypes.resolve` substitutes the variables the bound class's superclasses and interfaces bind
  (passed on through intermediate classes too) inside the member's type, for fields, setters and polymorphic
  subtypes. Writing was not affected: it follows the runtime class of each value.
- **Regression tests** : `JsonbTypeVariableTest`, `GenericTypesTest`. JSON-B TCK 295/0/5 in runtime and `--static`
  modes.

## BUG-20261029-06 — Static bindings: a JSON null container throws, `@JsonbNillable` only works on a String

- **Date** : 2026-10-29
- **Statut** : FIXED (fix/static-nulls)
- **Module touché** : `champollion-codegen-apt` — `JsonbStaticProcessor`
- **Symptôme** : with a `@JsonbStatic` record, `{"list":null}` for a `List`, `Map` or array component threw
  `IllegalStateException: Expected START_ARRAY` (or `START_OBJECT`) where the runtime binding reads `null`; and
  `@JsonbNillable` on a null `List`, `Map`, array, enum or nested record wrote nothing where the runtime binding writes
  `null` (JSON-B 3.0 §3.14.1, §4.3).
- **Reproduction minimale** : `DifferentialBindingTest.a_json_null_container_reads_as_null`,
  `nillable_writes_null_for_every_component_type`.
- **Cause** : the generated reads of containers expected their opening event unconditionally, and the generator
  emitted the `@JsonbNillable` `else` branch for `String` components only.
- **Fix** : a JSON `null` sets a container component to `null`; every non-`Optional` component honours
  `@JsonbNillable`.
- **Regression tests** : see reproduction. JSON-B TCK 295/0/5 in runtime and `--static` modes.

## BUG-20261029-07 — JSON-B: a `Jsonb` called again while it reads lost the enclosing read

- **Date** : 2026-10-29
- **Statut** : FIXED 2026-10-29 (branch `fix/reentrant-parser-pool`, generated-code audit)
- **Module touché** : `champollion-jsonb` — `ChampollionJsonb` (P9 parser pool, `fromJson(String)` and
  `fromJson(Reader)`)
- **Symptôme** : an adapter (or deserializer) that reads its own JSON with the same `Jsonb` instance, on the same
  thread, made the enclosing read fail with `NoSuchElementException: No more JSON events`. The properties after the
  adapted one were never read.
- **Reproduction minimale** : `ChampollionJsonbReentrancyTest` (`champollion-jsonb`): a `Shape` whose `origin` goes
  through a `JsonbAdapter<Point, String>` that calls `jsonb.fromJson(json, Point.class)`.
- **Hypothèse de cause** : the pool kept one parser per thread in a `ThreadLocal`. The nested read took the same
  parser and `reset` it onto the adapter's string while the enclosing read was still using it. The `ThreadLocal`
  also broke architecture constraint 4, and on virtual threads (one read per thread) it never reused a parser.
- **Investigations** :
  - 2026-10-29 : found while auditing the `ThreadLocal` noted by the generated-code audit. Fix: a single slot
    (`AtomicReference`). A read takes the parser out and puts it back when it is done, so a nested or concurrent
    read finds the slot empty and uses a parser of its own.

## BUG-20261029-09 — JSON-B runtime mapping: "Recursive update" reading a parameterized type made of parameterized types

- **Date** : 2026-10-29
- **Statut** : FIXED 2026-10-29 (branch `fix/nested-parameterized-reader-recursion`)
- **Module touché** : `champollion-jsonb` — `RuntimeReadRegistry.typeCache` (`readerFor`, `readerForGeneric`)
- **Symptôme** : `fromJson(json, List<Order>)`, where `Order` has a `List<Item>` member, sometimes failed with
  `IllegalStateException: Recursive update` from `ConcurrentHashMap.computeIfAbsent`. Seen in the
  `JsonbReadBenchStatic` LARGE benchmark (`List<Order>`): it failed in one JVM run and passed in the next.
- **Reproduction minimale** : `NestedParameterizedReaderTest` (`champollion-jsonb`): a record with about forty
  parameterized members read as `List<Wide>` fails on every run before the fix.
- **Hypothèse de cause** : the reader of a parameterized type was built inside `typeCache.computeIfAbsent`, and
  building it resolves the readers of its members, parameterized ones included, through the same map. A nested
  `computeIfAbsent` on a `ConcurrentHashMap` throws when the inner key falls in the bin the outer one is computing.
  Since BUG-20261029-08 the keys are the types themselves, whose hash codes are identity hash codes: whether two
  keys share a bin changes from one JVM run to the next. With the former keys (type names, stable hashes) the
  benchmark's pair did not collide, but the code had the same flaw.
- **Investigations** :
  - 2026-10-29 : fix: the reader is built outside the map and published with `putIfAbsent`; two threads may build
    the same reader, the first one stored is kept. The write side builds its parameterized writers without a map.

## BUG-20261029-08 — JSON-B runtime mapping: parameterized types of the same name shared one reader

- **Date** : 2026-10-29
- **Statut** : FIXED 2026-10-29 (branch `fix/type-cache-identity`, generated-code audit item 11)
- **Module touché** : `champollion-jsonb` — `RuntimeReadRegistry.typeCache` (`readerFor`, `readerForGeneric`)
- **Symptôme** : `List<Item>` was read with the reader built for the first `List<Item>` met, even when this `Item` came
  from another class loader. The list then held instances of the other loader's `Item`. A layered runtime (one
  class loader per application) is affected when two applications bind same-named classes with one `Jsonb`.
- **Reproduction minimale** : `JsonbTypeIdentityTest` (`champollion-jsonb`): a loader defines its own copy of `Item`,
  and `fromJson(.., List<copy>)` returned the original `Item`.
- **Hypothèse de cause** : the cache of parameterized-type readers was keyed by `Type.getTypeName()`, which also built
  a string on every read.
- **Investigations** :
  - 2026-10-29 : fix: the key is the type itself when the JDK made it (its `equals` compares the types it is made
    of), and a value with the same parts otherwise, so that a `ParameterizedType` a caller builds on each call
    without `equals` does not grow the cache.

## BUG-20261031-01 — JSON-B: a type whose package is not opened fails with no word about the module

- **Date** : 2026-10-31
- **Statut** : FIXED 2026-10-31 (branch `fix/jsonb-access-cause`)
- **Module touché** : `champollion-jsonb` — `RuntimeBindingRegistry.readProperty`, the constructors, setters and
  fields of `RuntimeReadRegistry`
- **Symptôme** : the Vidocq tutorial returned a `Book` from a REST resource whose package the application module did
  not open: `GET` answered 500 `JsonbException: Failed to read property author`, `POST` 500
  `JsonbException: ctor failed for class com.example.lib.book.Book`. Neither message named the module access problem,
  which only showed deep in the cause.
- **Reproduction minimale** : `AccessFailuresTest` (`champollion-jsonb`): an `IllegalAccessException` on a type of a
  package its module opens to nobody.
- **Hypothèse de cause** : the reflective binding wrapped every failure in the same message, whatever the cause.
- **Investigations** :
  - 2026-10-31 : fix: `AccessFailures.wrap` adds, when the cause chain holds an `IllegalAccessException` or an
    `InaccessibleObjectException` and the type's named module does not open its package to Champollion,
    `: module M does not open package P to io.vidocq.champollion.jsonb — add `opens P;` to its module-info.java`.
    Reported by the vidocq-tutorial session (finding R3); Vidocq's `vidocq:check-module-info` now reports the same
    packages at build time (Vidocq BUG-20261031-04).

## BUG-20261101-01 — JSON-B runtime: a record holding an `Optional` of itself overflows the stack

- **Date** : 2026-11-01
- **Statut** : FIXED 2026-11-01 (branch `feat/jsonb-bindings-pojo-hierarchy`): wider than reported — any type holding
  itself through a member (`Optional<Entry>`, `List<Node>`, `Map<String, Tree>`, a class's `List<Person>`) overflowed,
  on write and on read. Both registries now keep the set of classes whose writer/reader is being built; a member whose
  type names one of them gets a writer/reader resolved at its first use (`memberWriter`, `memberReader`,
  `GenericTypes.mentionsAny`). No `ThreadLocal`: the set is shared, a class another thread is building only makes a
  member lazy for nothing. `SelfReferencingTypesTest` (3, red before).
- **Module touché** : `champollion-jsonb` — `RuntimeBindingRegistry.resolveRecord` → `writerFor` → `parameterizedWriter`
- **Symptôme** : `toJson` of `record Entry(..., Optional<Entry> next)` throws `StackOverflowError`
  (`typeInfoOfDirectParents` / `findTypeInfo` / `resolveClass` repeated): resolving the writer of `Entry` resolves the
  writer of `Optional<Entry>` eagerly, which resolves `Entry` again before the first resolution is cached.
- **Reproduction minimale** : found by `RestBindingsTest` (`champollion-codegen-apt`) while comparing a generated binding
  with the runtime; the test now uses a non-recursive type and checks the recursive one
  (`aSelfReferencingRecordRoundTrips`) on the generated binding alone, which handles it.
- **Hypothèse de cause** : the writer of a component is resolved when the record's writer is built, not at first use;
  a self-reference needs a lazy (or placeholder) writer, as `ClassValue` does not guard re-entrant computation.

## BUG-20261101-02 — JSON-B: the types a Vidocq REST resource exchanges are bound by reflection, and need `opens`

- **Date** : 2026-11-01
- **Statut** : FIXED 2026-11-01 (branch `feat/jsonb-build-time-bindings`)
- **Module touché** : `champollion-codegen-apt` (`JsonbStaticProcessor`, new `RestBindings`, `RestBindingEmitter`),
  `champollion-jsonb` (`JsonbLeaf`, `LeafCodecs`, `RuntimeReadRegistry`)
- **Symptôme** : the processor generated bindings for `@JsonbStatic` records only; nothing marks the DTOs of a Vidocq
  application, so JSON-B bound them by reflection at run time, and their packages had to be opened to Champollion
  (Vidocq BUG-20261030-24, BUG-20261031-04, champollion BUG-20261031-01).
- **Correctif** : the processor also reads the JAX-RS resources of the compilation (`@Path` classes, their resource
  methods' return types and entity parameters, through collections, maps, optionals and `CompletionStage`), follows the
  types their properties hold, and generates a binding for each record and final Object-rooted class whose mapping it
  can reproduce: properties from components, public getters/setters or public fields, `@JsonbProperty`,
  `@JsonbTransient`, `@JsonbNillable`, enums, collections, `Map<String, V>`, `Optional`, the native types (through the
  new exported `JsonbLeaf`, the runtime's own codecs), and the bindings of the types they hold. A type outside that
  (non-final class, a superclass, another JSON-B annotation, an array…) keeps the runtime mapping, with a compiler note
  saying why. The read side of the runtime now uses a generated binding below the root too, as the write side did.
  Processor option `champollion.jsonb.rest=false` turns it off.
- **Vérification** : `RestBindingsTest` compares each generated binding with the runtime (same JSON text, equal
  objects read back) on records, final classes with accessors or public fields, nested and cross-package types, every
  native type, nulls; `JsonbStaticBindingTest` (reads below the root), `JsonbLeafTest`.

## BUG-20261101-03 — JSON-B: the generated bindings skipped non-final classes and classes with a superclass

- **Date** : 2026-11-01
- **Statut** : FIXED 2026-11-01 (branch `feat/jsonb-bindings-pojo-hierarchy`)
- **Module touché** : `champollion-codegen-apt` (`RestBindings`)
- **Symptôme** : BUG-20261101-02 generated bindings for records and final Object-rooted classes only; the classic DTO
  (`class Product extends BaseEntity`, getters and setters) kept the reflective mapping and its package's `opens`.
- **Correctif** : any concrete class with a no-argument constructor gets a binding, its inherited properties included
  (each class of the hierarchy from the top down, lexicographically within a class — the runtime's order); a
  non-public accessor is ignored as JSON-B's default visibility does, unless it hides a public field; a property
  declared on several classes of the hierarchy, or an interface default getter, keeps the runtime. The subclasses of a
  reached class compiled with it get their own binding. Checked against the runtime first: Champollion maps a value
  by its **declared** type (a `SpecialProduct` under `Product` is written with `Product`'s properties, at the root and
  as a member), so the binding of the declared type needs no check of the instance's class.
- **Vérification** : `RestBindingsTest` (POJO hierarchy written and read as the runtime, a subclass under its declared
  type and as the root, a public accessor inherited from a private class, an overridden getter and an abstract class
  kept on the runtime with their note).

## BUG-20261101-05 — APT: the generated bindings missed the JSON-B annotations of a package or a superclass

- **Date** : 2026-11-01
- **Statut** : FIXED 2026-11-01 (branch `feat/jsonb-bindings-formats-creators`)
- **Module touché** : `champollion-codegen-apt` (`RestBindings`)
- **Symptôme** : the processor looked only at the class itself for type-level JSON-B annotations. A `package-info`
  carrying `@JsonbNillable`, `@JsonbDateFormat` or `@JsonbVisibility`, or a superclass carrying one, changed what the
  runtime wrote, but the generated binding ignored it: the two diverged.
- **Repro minimal** : `@JsonbNillable package shop;` + `record Book(String note)` returned by a resource: the runtime
  writes `{"note":null}`, the binding `{}`.
- **Cause** : `classAnnotations` checked `type.getAnnotationMirrors()` only.
- **Correctif** : every class of the hierarchy and their packages are checked; `@JsonbNillable`, `@JsonbDateFormat`
  and `@JsonbNumberFormat` there are implemented (they apply to the properties, in the runtime's lookup order), any
  other JSON-B annotation keeps the runtime mapping with a note. An annotated interface method a getter implements
  keeps the runtime too. `@JsonbNillable(false)` and `@JsonbProperty(nillable = true)` on a member are honoured.
- **Vérification** : `RestBindingsCustomisationsTest` (package `@JsonbNillable`/`@JsonbDateFormat`, package
  `@JsonbVisibility`, superclass `@JsonbPropertyOrder`, annotated interface method).

## BUG-20261101-06 — JSON-B: record components and public fields ignored part of the format annotations

- **Date** : 2026-11-01
- **Statut** : FIXED 2026-11-01 (branch `feat/jsonb-bindings-formats-creators`)
- **Module touché** : `champollion-jsonb` (`RuntimeBindingRegistry`, `RuntimeReadRegistry`)
- **Symptôme** : a record component took `@JsonbDateFormat` from its accessor only: not from the record type or its
  package, and `@JsonbNumberFormat` not at all; its read ignored the format's locale; `@JsonbNillable` on the record
  type or package was ignored. A public field without a setter was written with its format but read without it.
- **Repro minimal** : `@JsonbDateFormat("dd/MM/yyyy") record Dated(LocalDate on)` writes `{"on":"1862-04-03"}`;
  `record Priced(@JsonbNumberFormat("#0.00") BigDecimal amount)` writes `{"amount":1.50}`; a class with
  `@JsonbDateFormat("dd/MM/yyyy") public LocalDate on;` cannot read back what it writes.
- **Cause** : records had their own date writer and reader (`customDateWriter`/`customDateReader`), and the field
  reader skipped the format lookup.
- **Correctif** : a record component goes through the bean lookup (accessor or field, then the type chain, then the
  packages) for both formats and nillability; the field reader applies the formats.
- **Vérification** : `RecordCustomisationsTest`; JSON-B 3.0 TCK unchanged (295 run, 0 failures, 0 errors, 5 skipped).

## BUG-20261101-07 — JSON-B: a `@JsonbNumberFormat` was shared across threads

- **Date** : 2026-11-01
- **Statut** : FIXED 2026-11-01 (branch `feat/jsonb-bindings-formats-creators`)
- **Module touché** : `champollion-jsonb`
- **Symptôme** : concurrent writes or reads of a property under `@JsonbNumberFormat` could produce or parse wrong
  numbers.
- **Repro minimal** : 2,000 virtual threads writing and reading a bean whose `double` getter carries
  `@JsonbNumberFormat("#,##0.00")`: some round trips give another number.
- **Cause** : the writer and the reader built one `DecimalFormat` per property and used it from every thread;
  `DecimalFormat` is not thread-safe.
- **Correctif** : each write and read formats with its own copy (`clone()`) of the prepared format.
- **Vérification** : `RecordCustomisationsTest.aNumberFormatIsSafeAcrossThreads` (red before, green after).

## BUG-20261101-08 — JSON-B: a JSON `null` for a primitive `@JsonbCreator` parameter leaked an `IllegalArgumentException`

- **Date** : 2026-11-01
- **Statut** : FIXED 2026-11-01 (branch `feat/jsonb-bindings-creators-adapters`)
- **Module touché** : `champollion-jsonb` (`RuntimeReadRegistry.invokeCreator`)
- **Symptôme** : `fromJson("{\"number\":null}", Account.class)` with `@JsonbCreator Account(@JsonbProperty("number")
  long number)` threw a raw `IllegalArgumentException` instead of a `JsonbException`.
- **Cause** : the reflective call of the creator wrapped `ReflectiveOperationException` only; passing `null` for a
  primitive parameter fails with `IllegalArgumentException`.
- **Correctif** : that exception is wrapped too.
- **Vérification** : `CreatorParametersTest`; found by the differential test of the generated creators.

## BUG-20261101-09 — APT: a class's static `@JsonbCreator` factory was ignored by its generated binding

- **Date** : 2026-11-01
- **Statut** : FIXED 2026-11-01 (branch `feat/jsonb-bindings-creators-adapters`)
- **Module touché** : `champollion-codegen-apt` (`RestBindings`)
- **Symptôme** : a class with a public no-argument constructor and a static `@JsonbCreator` factory got a binding that
  read through `new T()` and the setters, while the runtime reads through the factory.
- **Cause** : the static methods were skipped before the creator check, which only looked at the constructors and
  instance methods.
- **Correctif** : constructors and static factories carrying `@JsonbCreator` are both found; the binding calls the
  creator (or the type keeps the runtime, with a note, when it cannot).
- **Vérification** : `RestBindingsCustomisationsTest.aCreatorFactoryWinsOverTheNoArgumentConstructor`.

## BUG-20261101-10 — APT: a record implementing a `@JsonbTypeInfo` interface lost its discriminator

- **Date** : 2026-11-01
- **Statut** : FIXED 2026-11-01 (branch `feat/jsonb-bindings-serializers-polymorphism`)
- **Module touché** : `champollion-codegen-apt` (`RestBindings`)
- **Symptôme** : `record Circle(double radius) implements Shape`, with `@JsonbTypeInfo` on `Shape`, returned by a
  resource: the runtime writes `{"@type":"circle","radius":1.5}`, but the binding generated for `Circle` wrote
  `{"radius":1.5}` and, being static, replaced the runtime's mapping.
- **Cause** : the type-level annotations were looked for on the class and its superclasses only; a record's
  `@JsonbTypeInfo` comes from the interfaces it implements.
- **Correctif** : the processor builds the runtime's `@JsonbTypeInfo` chain (interfaces included). A record listed by
  its `@JsonbSubtype` writes and checks its discriminator; a class with such a chain keeps the runtime.
- **Vérification** : `RestBindingsCustomisationsTest` (`Circle` alone, and through the sealed `Shape`).

## BUG-20261101-13 — JSON-B: eight threads write less than one

- **Date** : 2026-11-01
- **Statut** : CLOSED 2026-11-02 — not reproducible, a measurement artifact
- **Module touché** : none
- **Symptôme** : `JsonbRestBindingBench.write_generated_adapters` with 20 lines wrote 1.48–1.52 ops/µs on one
  thread and 0.715–0.721 ops/µs in total on eight (BENCH-20261101-02).
- **Repro minimal** :
  ```bash
  java -jar champollion-bench/target/benchmarks.jar 'JsonbRestBindingBench.write_generated_adapters' -f 1 -wi 3 -w 2s -i 5 -r 2s -t 1 -p lines=20
  java -jar champollion-bench/target/benchmarks.jar 'JsonbRestBindingBench.write_generated_adapters' -f 1 -wi 3 -w 2s -i 5 -r 2s -t 8 -p lines=20
  ```
- **Investigation** : the same commands on the commit BENCH-20261101-02 measured (feab6af) give 1.60 → 9.71 ops/µs
  (6.1× on 8 threads, M5 Max), and the same on `main`. JMH `-prof stack` at 8 threads: 89.6 % RUNNABLE, all in
  serialization code (`WriterSink.append`, `GeneratorSink.appendLong`, the generated `write`), no BLOCKED thread.
  `-prof gc`: 5928 B/op whatever the thread count. On a quiet reference host (BENCH-20261102-01, macuntu, 6 cores),
  platform and virtual threads scale up to the cores and stay flat beyond.
- **Cause** : the original `-f 1` runs were taken while other builds loaded the Mac: CPU starvation, not contention.
- **Correctif** : none in code. Benchmarks are now taken with the pinned protocol of BENCH-20261102-01 (`-f 2`,
  warm-ups, a quiet host, load recorded before and after).

## BUG-20261101-11 — JSON-B: `@JsonbPropertyOrder` on a record was ignored

- **Date** : 2026-11-01
- **Statut** : FIXED 2026-11-01 (branch `feat/jsonb-bindings-response-entities`)
- **Module touché** : `champollion-jsonb` (`RuntimeBindingRegistry`)
- **Symptôme** : `@JsonbPropertyOrder({"c", "a"}) record Ordered(String a, String b, String c)` was written
  `{"a":…,"b":…,"c":…}`: the record path never applied the annotation.
- **Cause** : `resolveRecord` and `propertiesFromRecord` built the components in declaration order and stopped
  there; `applyPropertyOrder` only ran for beans.
- **Correctif** : the components the annotation names come first, in its order, the others in their declaration
  order. JSON-B 3.1's TCK checks it (`RecordPropertyOrderTest.testCustomPropertyOrderOnRecord`, see
  `docs/tck-proposals.md`).
- **Vérification** : `RecordCustomisationsTest.aPropertyOrderOnARecordPutsItsComponentsFirst`; the generated
  binding does the same (`RestBindingsCustomisationsTest`).

## BUG-20261101-12 — JSON-B: CDI adapters were kept forever, and shared across containers

- **Date** : 2026-11-01
- **Statut** : FIXED 2026-11-01 (branch `feat/jsonb-cdi-instances`)
- **Module touché** : `champollion-jsonb` (`CdiResolver`, the registries), new `champollion-jsonb-cdi`
- **Symptôme** : an adapter, serializer or deserializer resolved from CDI was resolved once per property, when the
  `Jsonb` built its mapping, and kept for the life of that `Jsonb`: a `@Dependent` one was never destroyed, and a
  `Jsonb` shared across containers (Cassini's is JVM-wide) went on using the first container's instance after it
  stopped.
- **Repro minimal** : one `Jsonb`, two Weld containers one after the other, a `@Dependent` adapter with a
  `@PreDestroy`: the second container gets the first one's instance, never destroyed.
- **Cause** : `CdiResolver.resolve` returned `CDI.current().select(type).get()`, captured by the writer and the reader.
- **Correctif** : the writers and readers ask, on each call, the registry `champollion-jsonb-cdi` installs per
  container (instances destroyed when it stops); without one, the instance obtained from `CDI.current()` is kept
  through its `Instance.Handle` and destroyed when the `Jsonb` closes.
- **Vérification** : `JsonbCdiRegistryTest` (two containers in one JVM, a `@Dependent` adapter destroyed on
  shutdown, the runtime's path, the `Jsonb` closing without the registry); `champollion-it-weld` and
  `champollion-it-openliberty` (the registry serves on Weld and on Liberty).

## BUG-20261101-14 — JSON-B CDI: Vauban could not create the registry of `champollion-jsonb-cdi` on the module path

- **Date** : 2026-11-01
- **Statut** : FIXED 2026-11-01 (branch `fix/jsonb-cdi-vauban-components`)
- **Module touché** : `champollion-jsonb-cdi` (`pom.xml`, `module-info.java`)
- **Symptôme** : a Vidocq application started on the original jar, not on the copy `vidocq:generate` enriched (the
  Vidocq examples' `@VidocqTest`, which did not bind `vidocq:test-path`), failed at start: `Cannot reflectively access
  io.vidocq.champollion.jsonb.cdi.JsonbCdiRegistry on the module path`, from the registry's `Startup` observer
  (vidocq#474, `vidocq-runtime-cassini-rest-example`).
- **Repro minimal** : `vidocq-runtime-cassini-rest-example` with `champollion-jsonb-cdi` in the REST bundle,
  `./mvnw -pl vidocq-runtime-examples/vidocq-runtime-external-rest-lib,vidocq-runtime-examples/vidocq-runtime-cassini-rest-example package`.
- **Cause** : the jar shipped no Vauban components, so Vauban fell back to reflection on a package that is not open.
- **Correctif** : the Vauban processor runs when the module compiles, as for `mansart-data-cdi` (mansart#68): the jar
  ships its `_VaubanComponents` (`provides io.vidocq.vauban.api.VaubanComponentProvider`), and opens nothing.
  `vauban-api` is a plain dependency: optional, Weld's reflective discovery could not load `_VaubanComponents` and
  gave up the registry (`champollion-it-weld`); with CDI exclusions, the declaration won Maven's mediation in a Vidocq
  codegen bundle and stripped the CDI API from the processor path.
- **Vérification** : `JsonbCdiModuleTest` (provides the components, opens nothing); `TodoResourceTest` of the example;
  `champollion-it-weld` and `champollion-it-openliberty` unchanged.

## BUG-20261102-01 — JSON-B: after a `vidocq dev` hot reload, the entities of the new layer are bound by reflection and need `opens`

- **Date** : 2026-11-02
- **Statut** : FIXED 2026-11-02 (branch `fix/hot-reload-static-bindings`). Reported by the vidocq-tutorial session
  (tutorial step 11).
- **Module touché** : `champollion-jsonb` — `StaticBindings`
- **Symptôme** : `GET /books` answers 200 with no `opens` on the entity package; after one `vidocq dev` hot reload it
  answers 500, `JsonbException: Failed to read property author: module … does not open package … to
  io.vidocq.champollion.jsonb`. A cold restart is fine. `Book$$Binding.class` exists in `target/classes`.
- **Reproduction minimale** : `vidocq create --name hrapp -x cassini-rest`, a public POJO `Book` with private fields
  and getters in a package `book` that the module does not open, a `@Path` resource returning `List<Book>`;
  `vidocq dev`, `GET /books` (200), touch the resource, wait for "Hot reload - restarting on a fresh application layer",
  `GET /books` (500). Reproduced end to end on the 0.4.0-SNAPSHOT of 2026-11-02.
- **Cause** : the `Jsonb` that Cassini serves requests with (`CassiniJsonbReaderWriter.DefaultJsonb`) is created once
  per JVM, in the parent layer, and outlives the application layer. `StaticBindings.fromServiceLoader` read the
  `META-INF/services/…JsonbBinding` files of the loaders it saw when the `Jsonb` was built (the library's and the
  context class loader's) and indexed the bindings by `Class`. A hot reload boots a fresh application layer under the
  same library: its `Book` is a new `Class` defined by a new loader, absent from that index, so `staticBindings.get`
  answered `null` and the runtime registries mapped the type through reflection. Whether the first layer was found at
  all depended on the context class loader of the thread that built the `Jsonb`.
- **Correctif** : an index made from `ServiceLoader` also answers for a type it does not hold: it reads, once, the
  services files of the type's own class loader and of the context class loader (unless the index was made from
  them), and keeps the answer in a `ClassValue`, that is with the class itself, so that the hit and the miss cost one
  lookup per class and a discarded layer stays collectable. The per-loader index is held through a weak reference.
  The reflective fallback is unchanged.
- **Vérification** : `StaticBindingsLaterLayerTest` (3: a layer booted after the `Jsonb`, a type without binding
  keeps the runtime mapping, a discarded layer is collected; red before); the TCK JSON-B 3.0 (295, 5 skipped as
  before); end to end on the tutorial-like app above: three hot reloads, `GET /books` 200 each time, no `opens`
  written to the module-info.

## BUG-20261102-02 — JSON-B: a `Jsonb` kept every application layer a `vidocq dev` hot reload discarded

- **Date** : 2026-11-02
- **Statut** : FIXED 2026-11-02 (branch `fix/jsonb-layer-leak`). Found by the static audit of the hot-reload leak (item 25
  of Vidocq/vidocq-workspace#40): 15 reloads of the audit app left 16 live `VaubanClassLoader`s; JFR `path-to-gc-roots`
  named the chain after Cassini and Cyrano had released their own statics (cassini#114, cyrano#64).
- **Module touché** : `champollion-jsonb` — `ComputedClassValue` (used by `RuntimeBindingRegistry.cache`,
  `RuntimeReadRegistry.classCache` and `StaticBindings.later`); `champollion-protobuf` — four static `Class` maps.
- **Symptôme** : memory grows by one application layer (~0.8 MB in the audit app) per hot reload; the old classes,
  their loader and the generated bindings stay reachable although nothing of the application refers to them.
- **Reproduction minimale** : `JsonbLayerLeakTest` — a layer defined by a child loader with a services file listing a
  generated binding, a `Jsonb` built under it, `toJson` of the entity, of a `List.of(e, e, e)` and of a `Map` holding
  it, the layer closed and dropped, `System.gc()`: the layer is still reachable.
- **Cause** : the registries cache a writer or reader per class in a per-instance `ClassValue`. For a class the JVM
  never discards (`ImmutableCollections$ListN`, `MapN`, …) the entry lives in the class for good; its value (the writer
  of the collection) is a lambda over the registry, and the registry reaches the `ClassValue` that is the weak key of
  the entry in that class: a weak key held by its own value is never cleared. The registry holds the generated
  bindings of the layer it was built for (`staticBindings.byType`), hence the layer's classes and loader. The audit
  heap dump could not show it: the chain ends in a "Class Loader Data" root. The static `ConcurrentHashMap<Class<?>, …>`
  caches of the Protobuf runtime (`Descriptors`, `EnumValueMap`, `RuntimeBinding`, `ProtobufJsonRuntime`) held the
  classes of the message records strongly.
- **Correctif** : `ComputedClassValue` keeps the value of a class of the bootstrap, platform or application loader in
  a map the cache owns (collected with the registry) and any other class's value in a `ClassValue`, in the class
  itself, as before. The four Protobuf maps became `ClassValue`s. The reflective fallback and the champollion#82
  later-layer lookup are unchanged.
- **Vérification** : `JsonbLayerLeakTest` (2; the first is red on `main`: a dropped `Jsonb` keeps its layer alive; the
  second guards a `Jsonb` kept across layers); unit tests of the reactor (`mvn test`: jsonb 693, protobuf 448, …);
  TCK JSON-B 3.0 `all` and `all --static`: 295 tests, 5 skipped as before; BENCH-20261102-02 (no measurable change).
  The loader count of the audit app after a hot-reload series is reported on the pull request.
