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
- **Statut** : OPEN (found while covering the Sonar cleanup with unit tests)
- **Module touché** : `champollion-protobuf` — `RuntimeBinding.readScalarTyped` (ENUM case)
- **Symptôme** : a map whose values are an enum with explicit `@ProtoEnumValue` numbers round-trips wrong: the writer emits the declared number, the reader looks the number up as an ordinal. A value declared `-1` comes back as `null`.
- **Reproduction minimale** : a message with `Map<String, Status>` where `Status` has `@ProtoEnumValue(-1) UNKNOWN`; encode `{"a": UNKNOWN}`, decode it.
- **Hypothèse de cause** : the map value reader uses `values()[n]` instead of the `@ProtoEnumValue` lookup the field reader uses.

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
- **Statut** : OPEN (found while covering the Sonar cleanup with unit tests)
- **Module touché** : `champollion-codegen-apt` — `JsonbStaticProcessor` (list element reading, `readLeaf`)
- **Symptôme** : a `@JsonbStatic` record with a `List<Float>` component makes the annotation processor emit code that fails to compile.
- **Reproduction minimale** : `@JsonbStatic record Sample(List<Float> values) {}` compiled with the processor.
- **Hypothèse de cause** : `readLeaf` returns a `double` for `Float` elements, which the generated code adds to a `List<Float>` without a narrowing conversion.

## BUG-20261027-04 — Protobuf JSON: `UInt64Value` is written signed

- **Date** : 2026-10-27
- **Statut** : OPEN (found while covering the Sonar cleanup with unit tests)
- **Module touché** : `champollion-protobuf` — the JSON writer of `Wrappers.UInt64Value`, at top level and as a field
- **Symptôme** : a value above `Long.MAX_VALUE` is written as a negative number.
- **Reproduction minimale** : write a `UInt64Value` holding `-1L` (2^64 - 1 unsigned): the JSON says `"-1"`, the proto3 JSON mapping expects `"18446744073709551615"`.
- **Hypothèse de cause** : the writer uses `Long.toString` instead of `Long.toUnsignedString`.

## BUG-20261027-05 — Protobuf: `BytesValue` compares and prints the array, not the bytes

- **Date** : 2026-10-27
- **Statut** : FIXED (fix/sonar-overall-reliability-security, Sonar S6218)
- **Module touché** : `champollion-protobuf` — `wkt.Wrappers.BytesValue` (and `wkt.Any.toString`)
- **Symptôme** : two `BytesValue` holding the same bytes are not equal and hash differently; `toString` prints `[B@…`. `Any` already compared its bytes but printed the array identity.
- **Reproduction minimale** : `new BytesValue(new byte[]{1}).equals(new BytesValue(new byte[]{1}))` is `false`.
- **Hypothèse de cause** : a record with a `byte[]` component inherits the array's identity `equals`/`hashCode`/`toString`. Fixed by overriding them with `Arrays`.

## BUG-20261028-01 — JSON-B: a null `JsonStructure` member cannot be read

- **Date** : 2026-10-28
- **Statut** : OPEN (found while covering the JSON-B registries with unit tests)
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
- **Statut** : OPEN (found while covering the JSON-B registries with unit tests)
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

## BUG-20261028-05 — JSON-B: the properties after a member read by a `JsonbDeserializer` are dropped

- **Date** : 2026-10-28
- **Statut** : OPEN (found while covering the JSON-B registries with unit tests)
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

## BUG-20261028-06 — JSON-B: an `@JsonbSubtype` that is not a subtype is accepted on read

- **Date** : 2026-10-28
- **Statut** : OPEN (found while covering the JSON-B registries with unit tests)
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
