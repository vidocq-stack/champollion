# Champollion TCK

This document describes how to run the official Jakarta JSON-P 2.1 and
Jakarta JSON-B 3.0 TCKs against Champollion, and lists any challenges
(disabled tests with justification).

## Meta

- **Module**: `champollion-tck` (in-reactor, gated behind the **`tck` Maven profile**
  to avoid ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0)
- **Scripts**: `run-official-tck-jsonp-2.1.sh` and `run-official-tck-jsonb-3.0.sh`
  at the project root
- **Contract**: 100% PASS on both TCKs before any structural merge on
  `champollion-jsonp` or `champollion-jsonb`

---

## Installing the official TCKs

Jakarta TCKs are distributed by the Eclipse Foundation as ZIP files containing
the JARs and POMs (not published on public Maven Central).

### Automatic method (recommended)

The `install-tck.sh` script at the root downloads the official ZIPs from
`download.eclipse.org`, extracts the artifacts, and installs them with their
real POMs using `mvn install:install-file`.

```bash
./install-tck.sh          # JSON-P + JSON-B
./install-tck.sh jsonp    # JSON-P 2.1 only
./install-tck.sh jsonb    # JSON-B 3.0 only
```

Idempotent: it does not re-download if the JARs are already present in `~/.m2/`.

### Installed Maven coordinates

| Coordinates | Source |
|---|---|
| `jakarta.json:jakarta.json-tck-common:2.1.1` | JSON-P ZIP |
| `jakarta.json:jakarta.json-tck-tests:2.1.1` | JSON-P ZIP |
| `jakarta.json:jakarta.json-tck-tests-pluggability:2.1.1` | JSON-P ZIP |
| `jakarta.json.bind:jakarta.json.bind-tck:3.0.0` | JSON-B ZIP |

### Manual method

If you prefer:

1. Download https://download.eclipse.org/jakartaee/jsonp/2.1/jakarta-jsonp-tck-2.1.1.zip
2. Unzip it, go to `jsonp-tck/artifacts/`, and run for each JAR:
   ```bash
   mvn install:install-file -Dfile=<jar> -DpomFile=<pom>
   ```
3. Do the same for `jakarta-jsonb-tck-3.0.0.zip`.

---

## Running

### JSON-P 2.1 TCK

```bash
# smoke test
./run-official-tck-jsonp-2.1.sh

# full suite
./run-official-tck-jsonp-2.1.sh all

# targeted test
./run-official-tck-jsonp-2.1.sh -Dtest=TestName
```

Output: `champollion-tck/target/tck-report-jsonp.txt`

### JSON-B 3.0 TCK

```bash
# smoke test
./run-official-tck-jsonb-3.0.sh

# full suite, runtime introspection mode
./run-official-tck-jsonb-3.0.sh all

# full suite, static codegen mode (M5.13 — not delivered yet)
./run-official-tck-jsonb-3.0.sh all --static

# targeted test
./run-official-tck-jsonb-3.0.sh -Dtest=TestName
```

Output: `champollion-tck/target/tck-report-jsonb.txt`

### When the TCK is not installed

The scripts check for the TCK JAR in the local M2 and **exit with code 78**
(`EX_CONFIG`) if it is missing. CI runners interpret that as a **skip** (not a
failure). See `.forgejo/workflows/ci.yml`:

```yaml
- name: Jakarta JSON-P 2.1 TCK
  run: |
    if [ -x ./run-official-tck-jsonp-2.1.sh ]; then
        ./run-official-tck-jsonp-2.1.sh all
    else
        echo "::notice::TCK script not yet present — skipped."
    fi
```

---

## Static mode (`--static`, JSON-B only)

The `--static` option reruns the suite with fixtures recompiled through
`champollion-codegen-apt` to validate that **static codegen** (M5) produces the
same behavior as introspective runtime mode.

**M6 status**: implementation deferred. It requires:

1. Extracting the TCK JAR
2. Recompiling fixture classes (records or POJOs) with `JsonbStaticProcessor`
   while injecting `@JsonbStatic` on the target types
3. Repackaging and invoking the TCK with the enriched classpath

The current differential testing (`DifferentialBindingTest` in
`champollion-codegen-apt`) already gives a strong guarantee that static bindings
match runtime behavior — `--static` would only confirm that on the TCK corpus.

---

## First TCK run — 2026-05-03 baseline

### JSON-P 2.1

| Metric | 2026-05-03 baseline | After M2.x + M3.4 | After M6.x |
|---|---|---|---|
| Tests run | 197 | 179 (API only) | 179 |
| **PASS** | **65** (33%) | **168** (94%) | **179** (100%) ✅ |
| FAIL | 112 | 7 | 0 |
| ERROR | 20 | 4 | 0 |

**100% of applicable tests PASS** — the previous signature-test harness issue
has been fixed by providing `jimage.dir` and `signature.sigTestClasspath`.

**M6.x fixes**:
- `ChampollionJsonObject.getString/getInt/getBoolean/isNull` throw NPE if the
  key is missing (spec 2.1.4)
- `ChampollionJsonObjectBuilder.remove(null)` and `addAll(null)` throw NPE
- `JsonProvider.createValue(Number)` accepts `Integer/Long/Double/Float/Short/Byte/BigDecimal/BigInteger/AtomicLong/...`
- `JsonBuilderFactory.createObjectBuilder(JsonObject|Map)` and
  `createArrayBuilder(JsonArray|Collection)` overrides
- `autoDetectingReader` throws `JsonException` if the encoding cannot be determined
  (1-byte `0x00` → `jsonObjectUnknownEncoding.json`)
- `ChampollionJsonPointer`: malformed `~n` exception is deferred to resolution
  (the TCK `testResolvePathWithUnencodedTilde` catches the exception in `getValue()`,
  not in `createPointer()`)

> **Major issue**: the TCK `JsonProviderTest.systemProperty()` polluted
> `System.getProperty("jakarta.json.provider")` without cleanup, which caused
> `JsonProvider.provider()` to be shadowed by a mock in all subsequent tests.
> Fix: `forkCount=1, reuseForks=false` in Surefire + separate pluggability profile.

**Additional fixes** (commit `44a83d9`):
- `JsonConfig.KEY_STRATEGY` (FIRST/LAST/NONE) on the reader side
- `readArray/readObject` throw `JsonException` if the type is incompatible
- `close()` invalidates the reader (Spec §3.6)
- Tokenizer uses `JsonParsingException` (a `JsonException` subclass)
- `Builder.build()` resets the builder (Spec 2.1 §4.7/§4.8)
- `getConfigInUse()` filters supported properties

Main ERROR sources (all pointed to `UnsupportedOperationException` stubs that
were self-marked as TODO):
- `Json.createDiff()` → M3.4 deferred
- `parser.getObject()` / `parser.getValue()` → M2 marker
- `createObjectBuilder(Map<String,?>)` → M2.3 marker
- `JsonPointer /~n` rejected too early

### JSON-B 3.0

| Metric | 2026-05-03 baseline | After M7.x | After M7.8–M7.15 | After M7.16 (CDI §5) | **After M7.17 + harness fix** |
|---|---|---|---|---|---|
| Tests run | 295 | 295 | 295 | 295 | 295 |
| **PASS** | **78** (26.4%) | 248 (84.1%) | 287 (97.3%) | 288 (97.6%) | **290 (100% applicable)** ✅ |
| FAIL | 179 | 35 | 0 | 0 | 0 |
| ERROR | 33 | 7 | 3 (env) | 2 | **0** |
| SKIP | 5 | 5 | 5 | 5 | 5 |

**100% of functionally applicable tests PASS** — the previous signature-test
harness issue has been fixed by providing `jimage.dir` and
`signature.sigTestClasspath`.

**Modules at 100%**:
- `defaultmapping.basictypes.BasicJavaTypesMapping` (10/10)
- `defaultmapping.dates.DatesMapping` (24/24)
- `defaultmapping.collections.CollectionsMapping` (20/20)
- `defaultmapping.classes.ClassesMapping` (23/23)
- `defaultmapping.specifictypes.SpecificTypesMapping` (14/14)
- `defaultmapping.jsonptypes.JSONPTypesMapping` (10/10)
- `defaultmapping.attributeorder.AttributeOrderMapping` (2/2)
- `defaultmapping.identifiers.NamesAndIdentifiersMapping` (2/2)
- `defaultmapping.untyped.UntypedMapping` (2/2)
- `defaultmapping.uniqueness.PropertyUniqueness` (1/1)
- `defaultmapping.polymorphictypes.DefaultPolymorphicMapping` (1/1)
- `customizedmapping.binarydata.BinaryDataCustomization` (3/3)
- `customizedmapping.dateformat.DateFormatCustomization` (11/11)
- `customizedmapping.nullhandling.NullHandlingCustomization` (14/14)
- `customizedmapping.propertynames.PropertyNameCustomization` (20/20)
- `customizedmapping.propertyorder.PropertyOrderCustomization` (8/8)
- `customizedmapping.visibility.VisibilityCustomization` (3/3)

**Major structural fixes**:
- Bridge/synthetic methods filtered in POJO introspection (M7.2)
- Generic interface (`TypeContainer<T>`) → runtime `dynamicWriter` resolution (M7.2)
- Abstract classes (`Number`, `TimeZone`, etc.) → runtime resolution (M7.2)
- `GenericArrayType` (e.g. `Optional<String>[]`) handled explicitly (M7.5)
- `byte[]` strategy: BYTE / BASE_64 / BASE_64_URL via `JsonbConfig.BINARY_DATA_STRATEGY` (M7.1)
- Date/Time builtins: Duration, Period, LocalTime, OffsetTime, ZoneId, ZoneOffset, MonthDay, YearMonth, Year, Date, Calendar, TimeZone, SimpleTimeZone (M7.3)
- Raw collections → concrete impl (`Queue→LinkedList`, `Deque→ArrayDeque`, `SortedSet→TreeSet`, etc.) (M7.4)
- Property visibility hierarchy: `private getter` masks `public field` (M7.5)
- `final` fields skipped during reading (M7.5)
- Naming strategies (LOWER_CASE_WITH_DASHES/UNDERSCORES, UPPER_CAMEL_CASE, UPPER_CAMEL_CASE_WITH_SPACES, IDENTITY, CASE_INSENSITIVE) (M7.6)
- PropertyOrderStrategy (LEXICOGRAPHICAL/REVERSE/ANY) + `@JsonbPropertyOrder` + parent→child classes (M7.6)
- PropertyVisibilityStrategy via `JsonbConfig` / `@JsonbVisibility` on class / package (M7.6)
- `@JsonbTransient` + another Jsonb annotation → `JsonbException` (M7.6)
- Duplicate property name detection → `JsonbException` (M7.6)
- `@JsonbDateFormat` / `@JsonbNumberFormat` at multiple levels (member, type, package, config) with locale (M7.7)
- `@JsonbNillable` propagation type/package + `@JsonbProperty(nillable=true)` (M7.7)
- JSON-P types (`JsonObject`/`JsonArray`/`JsonValue`/`JsonString`/`JsonNumber`) treated natively (M7.7)
- `@JsonbTypeInfo` dispatch on non-record POJO (M7.7)
- `JsonbConfig.FAIL_ON_UNKNOWN_PROPERTIES`, `JsonbConfig.LOCALE` (M7.7)

**M7.8 → M7.15 fixes** (39 tests gained, 248 → 287):
- M7.8 — `@JsonbCreator` validations (multiplicity, factory return type, ctor + factory),
  setters/fields applied after creator (`testCustomConstructorPlusFields`),
  `Optional* / OptionalInt / OptionalLong / OptionalDouble` defaults,
  `JsonbConfig.CREATOR_PARAMETERS_REQUIRED`.
- M7.10 — multi-level `@JsonbTypeInfo` discriminator cascade:
  `findTypeInfo` tolerates linear inheritance chain (Labrador → Dog → Animal → LivingThing),
  `typeInfoChain` collects parent→child, `polymorphicWriter` / `polymorphicReader`
  write/read the full cascade in order.
- M7.11 — `@JsonbTypeInfo` validations (§4.8) (multi-inheritance, alias non-subtype, name collision).
- M7.12 — full strict I-JSON mode: `withStrictIJSON`, top-level non-object/array
  → `JsonbException`, `BinaryDataStrategy.BASE_64` forced, date format
  `yyyy-MM-dd'T'HH:mm:ss'Z'xxx`, Calendar/Date/LocalDate/Instant converted via
  UTC ZonedDateTime, Duration/Period preserved as ISO 8601.
- M7.13 — global adapters (`JsonbConfig.withAdapters`) + field-level `@JsonbTypeAdapter`,
  preservation of adapted-type generics (`AnimalListAdapter` ↔ `List<AnimalJson>`).
- M7.14 — custom serializers/deserializers: `@JsonbTypeSerializer` / `@JsonbTypeDeserializer`,
  `JsonbConfig.SERIALIZERS` / `DESERIALIZERS`, `ChampollionSerializationContext`
  and `ChampollionDeserializationContext`, `ReplayJsonParser` to replay the current event.
- M7.15 — `@JsonbCreator` in a polymorphic hierarchy (DateConstructor),
  `@JsonbDateFormat` / `@JsonbTypeAdapter` / `@JsonbTypeDeserializer` on creator parameters.
- M7.9 — `@JsonbNumberFormat`: `parseLocale("##default")` → `Locale.ROOT`
  (canonical JSON-B format), NBSP normalization (U+00A0 vs NNBSP U+202F on CLDR
  Java 13+ French). Writer + reader symmetry.
- M7.13/14 (final) — `readObjectAndApply` tolerates END_OBJECT consumed by a
  custom deserializer (over-consumption of the child value), `PrimedParser.currentEvent()` override.

**M7.16 → M7.17 fixes** (CDI §5 + custom deserializer split — 287 → 289):

- M7.16 — JSON-B §5 CDI support: `JsonbAdapter` / `JsonbSerializer` /
  `JsonbDeserializer` instances are resolved through `CDI.current()` when a CDI
  container is available (commit `bf8c3b2`). `CdiResolver` only delegates to CDI
  for actual managed beans, falling back to direct instantiation otherwise
  (commit `5fc08b5`).
- M7.17 — custom deserializer semantics split between creator parameters and
  regular properties: a `@JsonbTypeDeserializer` on a creator parameter is applied
  at creator-argument binding time, not as a regular property write (commit
  `17c6881`).

### Remaining challenges (final)

| Test | Suite | Nature |
|---|---|---|
| 5 SKIP (BasicJavaTypes, BigNumbers, Classes, Dates, Enum, PropertyUniqueness suites) | JSON-B 3.0 | Disabled **upstream inside the TCK itself** (`@Disabled` pointing to eclipse-ee4j/jsonb-api#180 and jakartaee-tck#103). Every implementation, including Yasson, skips them. |

---

## Verified status — 2026-06-16 (full rerun)

| Suite | Run | PASS | FAIL | ERROR | SKIP |
|---|---|---|---|---|---|
| JSON-P 2.1 (API) | 179 | **179** | 0 | 0 | 0 |
| JSON-P 2.1 (pluggability) | 18 | **18** | 0 | 0 | 0 |
| JSON-B 3.0 | 295 | **290** | 0 | 0 | 5 (TCK-upstream) |

**Both TCKs are functionally at 100% PASS.** The hard contract (no structural
merge without TCK PASS) is satisfied; the only delta is the five upstream-
disabled tests documented above.
