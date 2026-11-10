# Champollion — Current status

> Progress summary as of 2026-06-10. For the phase-by-phase plan, see `ROADMAP.md`.
> For conventions and constraints, see `CLAUDE.md`.

## Meta

- **Branch**: `main`
- **Build**: `mvn clean install -DskipTests` ✅ on 8 modules (parent + 7 submodules)
- **Tests**: **290/290** ✅ (`mvn test` on the reactor)
- **JSON-P 2.1 TCK**: **178/179 PASS, 0 FAIL** ✅ — the only ERROR is `JSONPSigTest.signatureTest`, an environmental challenge (signature file not shipped in the 2.1.0 TCK ZIP). Pluggability suite 18/18. See `TCK.md`. Re-verified 2026-06-10.
- **JSON-B 3.0 TCK**: **289/295 PASS, 0 FAIL** ✅ — 1 ERROR = `JSONBSigTest.signatureTest` (same environmental challenge), 5 SKIP are tests disabled upstream inside the TCK itself (eclipse-ee4j/jsonb-api#180, jakartaee-tck#103 — every implementation skips them). Re-verified 2026-06-10.
- **Discipline**: strict TDD on every commit, RFC citation in `@DisplayName`

## Modules

| Module | Status | Notes |
|---|---|---|
| `champollion-api` | ✅ | Re-exposes `jakarta.json` + `jakarta.json.bind`. No public SPI exported yet (to be added when we have a stable extension point). |
| `champollion-jsonp` | ✅ **complete for the public spec** | 161 tests. Streaming + object model + builders + Reader/Writer + Pointer + Patch + MergePatch + ServiceLoader. |
| `champollion-jsonb` | ✅ **runtime + lookup-first static** | 52 tests. Runtime `toJson` + `fromJson` operational (primitives, `java.time`, UUID, enum, records, POJOs, containers). Public SPI `JsonbBinding<T>` + `@JsonbStatic` exposed, ServiceLoader, lookup-first, runtime fallback. |
| `champollion-codegen-apt` | ✅ **functional MVP** | 18 tests. `JsonbStaticProcessor` APT generates one `JsonbBinding<T>` per `@JsonbStatic` record + ServiceLoader file. Covers primitives + String + enums + `List<E>` + `Optional<E>` + `Map<String,V>` + primitive arrays + `String[]` + nested `@JsonbStatic` records. Automated differential testing static vs runtime. |
| `champollion-codegen-maven-plugin` | ✅ **active** (`packaging=maven-plugin`) | 3 tests. `generate` mojo writes `<FQN>$$Trigger.java` triggers annotated `@JsonbStatic`, then runs `javac` with `JsonbStaticProcessor` on the host project compile classpath. `maven-plugin-plugin 4.0.0-beta-2` supports Java 25. |
| `champollion-bench` | ✅ active | JMH workloads (parser + JSON-B read/write) vs Parsson/Yasson/Jackson — see `BENCH.md`. |
| `champollion-examples` | 🟡 empty | POM ready, no example written yet. |
| `champollion-tck` | ✅ **created** (out of reactor) | Out-of-reactor module (Model 4.0.0 POM). Profiles `-Pjsonp-tck` (JUnit 5) and `-Pjsonb-tck` (TestNG). Root shell scripts `run-official-tck-{jsonp-2.1,jsonb-3.0}.sh`. Clean skip (exit 78) if the official TCK is unavailable. |

## Progress by phase (see `ROADMAP.md`)

| Phase | Scope | Status |
|---|---|---|
| M0 | Multi-module bootstrap, Java Modules, ServiceLoader | ✅ |
| M1.1 | JSON-P RFC 8259 tokenizer | ✅ 19 tests |
| M1.2 | JsonParser pull (events) | ✅ 21 tests |
| M1.3 | JsonGenerator push | ✅ 27 tests |
| M1.4 | JsonProvider + ServiceLoader | ✅ 10 tests |
| M2.1 | Object model `JsonValue/JsonObject/JsonArray/...` | ✅ 20 tests |
| M2.2 | Builders + Reader/Writer | ✅ 17 tests |
| M3.1 | JsonPointer RFC 6901 | ✅ 18 tests |
| M3.2 | JsonPatch RFC 6902 (12 ops, A.1..A.10 + copy + dash) | ✅ 16 tests |
| M3.3 | JsonMergePatch RFC 7396 + diff | ✅ 13 tests |
| **M4.1** | Runtime `Jsonb.toJson` — primitives + records | ✅ 14 tests |
| **M4.2** | Symmetric runtime `Jsonb.fromJson` | ✅ 16 tests |
| **M4.3-write** | Containers (List/Set/Map/Array/Optional) | ✅ 16 tests |
| **M3.4** | `Json.createDiff` (JsonPatch diff) | ✅ implemented (validated by the JSON-P TCK) |
| **M4.4a** | `@JsonbProperty` + `@JsonbTransient` runtime | ✅ 6 tests |
| **M4.4b** | `@JsonbProperty` + `@JsonbTransient` APT bytecode | ✅ 3 tests |
| **M4.4c** | `@JsonbNillable` runtime + bytecode | ✅ 3 tests |
| **M4.4d** | `@JsonbDateFormat` runtime (`java.time`) | ✅ 3 tests |
| **M4.4e** | `@JsonbCreator` runtime (ctor + static factory) | ✅ 2 tests |
| **M4.4f** | `@JsonbTypeAdapter` runtime | ✅ 3 tests |
| **M4.4g** | `@JsonbVisibility` + `@JsonbNumberFormat` | ✅ delivered during the TCK campaign (M7.6 / M7.9) |
| **M4.6** | POJO JavaBean conventions (getters/setters) | ✅ 6 tests |
| **M4.7** | `JsonbConfig` (`FORMATTING`, `NULL_VALUES`, `DATE_FORMAT`) | ✅ 6 tests |
| **M4.5** | `@JsonbTypeInfo` / `@JsonbSubtype` polymorphism | ✅ 5 tests (records, MVP) |
| **M5.1** | `JsonbBinding` SPI + lookup-first | ✅ 6 tests |
| **M5.2** | `@JsonbStatic` + `JsonbStaticProcessor` APT | ✅ 4 tests |
| **M5.3** | APT containers (List/Optional/Arrays) | ✅ 4 tests |
| **M5.4** | Static vs runtime differential testing | ✅ 3 tests |
| **M5.5** | APT — `Map<String,X>` + nested records | ✅ 4 tests |
| **M5.6** | APT — enums as leaf type | ✅ 3 tests |
| **M5.7** | Direct APT bytecode (Class File API JDK 25) | ✅ primitives + String + enums in records |
| **M5.8** | Active `champollion-codegen-maven-plugin` | ✅ — 3 tests |
| **M5.9** | Extended APT bytecode: primitive arrays + `String[]` + `Optional<X>` | ✅ |
| **M5.10** | APT bytecode `List<X>`, `Map<String,V>`, nested `@JsonbStatic` | ✅ — **100% bytecode fast path** |
| **M5.11** | Pre-encoded property names + `writeKeyRaw` fast path | ✅ |
| **M5.12** | AOT validation by bytecode inspection (zero reflection) | ✅ — 5 tests |
| **M6.1** | Out-of-reactor `champollion-tck` module | ✅ |
| **M6.2** | `run-official-tck-jsonp-2.1.sh` | ✅ |
| **M6.3** | `run-official-tck-jsonb-3.0.sh` | ✅ |
| **M6.4** | `--static` TCK mode | ❌ deferred (not critical) |
| **M6.5** | `TCK.md` instructions + challenges | ✅ |
| **M6.6** | `install-tck.sh` — auto download + install in M2 | ✅ |
| **M6.7** | First TCK run + first fixes (M2.x stubs + M3.4) | ✅ baseline 65→69 PASS on JSON-P |
| **M6.8** | Methodical FAIL analysis (TCK sources) | ✅ done — 0 FAIL on both TCKs, see `TCK.md` |
| **M7** | Cassini integration (Yasson → Champollion swap) | ✅ `cassini-core` depends on `champollion-jsonp` + `champollion-jsonb` |

## Remaining work

### Short term — finish M3 and M4

✅ **All delivered during the TCK campaign** (see `TCK.md`, M6.x/M7.x fixes): `Json.createDiff`
(M3.4), the full JSON-B customization set (`@JsonbProperty`, `@JsonbTransient`,
`@JsonbDateFormat`/`@JsonbNumberFormat`, adapters, `@JsonbCreator`, `@JsonbVisibility`,
`JsonbConfig` properties, naming strategies), `@JsonbTypeInfo`/`@JsonbSubtype` polymorphism
(including multi-level cascades), and POJO getter/setter conventions with the full
visibility hierarchy.

### Medium term — M5 (static codegen) — partially delivered

**Delivered:**
- ✅ `JsonbBinding<T>` SPI (`champollion-jsonb.spi`) + `PrimedJsonParser` utility
- ✅ `@JsonbStatic` annotation (`champollion-jsonb.spi`, `RetentionPolicy.CLASS`)
- ✅ **100% bytecode `JsonbStaticProcessor`** (Class File API JDK 25, `Filer.createClassFile`) on the full subset:
  - primitives (8 types), String, enums
  - primitive arrays (`int[]`, `long[]`, `double[]`, `boolean[]`), `String[]`
  - `Optional<X>`, `List<X>`, `Map<String,V>` where X/V ∈ scalar / enum / nested `@JsonbStatic` record
  - nested `@JsonbStatic` records as direct components
  - The source slow path remains implemented as fallback but is no longer exercised by the current tests.
- ✅ `ChampollionJsonb` lookup-first on static bindings, introspective runtime fallback
- ✅ Automated runtime-vs-static differential testing
- ✅ Type coverage: primitives, String, enums, List<E>, Optional<E>, Map<String,V>, primitive arrays, `String[]`, nested `@JsonbStatic` records (direct reference by `new <X>$$Binding()`)

**To do:**
- **M5.13 (optional)** End-to-end `native-image` validation with GraalVM installed. Bytecode inspection validation (M5.12) already satisfies the contract; a `native-image` test would only confirm it in practice.

### Medium term — broader tests

- ~~**JSONTestSuite corpus**~~ — DONE 2026-06-11: the full `nst/JSONTestSuite` parsing corpus (318 files, MIT, vendored with its license) runs in `JsonTestSuiteConformanceTest`. **318/318 on first integration** — single adjustment: `i_number_huge_exp` materialization defers to `BigDecimal` (int-scale limit) exactly like Parsson.
- **JMH benchmark** in `champollion-bench`: throughput/latency/allocs comparison vs Parsson, Yasson, Jackson.
- **Examples** in `champollion-examples`: runtime vs static codegen demo, integration with `Json.*` and `JsonbBuilder`, custom adapter examples.

### Long term — M6 (TCK)

- Create `champollion-tck` **outside the reactor** (standalone Model 4.0.0 POM, ShrinkWrap constraint inherited from `cassini-tck`/`foy-tck`).
- Install the official TCKs in the local M2:
  - `jakarta.json:jakarta-json-tck:2.1.x`
  - `jakarta.json.bind:jakarta-json-bind-tck:3.0.x`
- Scripts `run-official-tck-jsonp-2.1.sh` and `run-official-tck-jsonb-3.0.sh` (smoke / all / `-Dtest=`).
- `TCK.md` documenting any challenges (disabled tests with spec citations).
- **Static mode**: run the TCK also with fixtures recompiled through APT to validate codegen consistency.
- **Hard contract**: 100% PASS on both TCKs before any structural merge.

### Long term — M7 (Vidocq ecosystem integration)

- Adapt Cassini side `cassini-champollion`: `MessageBodyReader/Writer<JsonValue>` and `<Object>` via Champollion JSON-B. Replace Parsson + Yasson in Cassini.
- Optional `chappe-champollion` adapter: JSON `BodyHandler` for non-JAX-RS Chappe.
- `docs/integration-cassini.md` and `docs/integration-chappe.md`.
- End-to-end benchmark Cassini + Champollion vs Cassini + Yasson.

## Open decisions (re-confirmed)

- Static mode in **2 separate artifacts**: `champollion-codegen-apt` (pure JDK) + `champollion-codegen-maven-plugin` (orchestrator). ✅ decided.
- **Reflective runtime kept as fallback** when no static factory is found. ✅ decided.
- `java.lang.foreign` for the UTF-8 scanner: deferred to post-M5.
- `@JsonbTypeInfo` polymorphism in static mode: requires a compile-time-known sealed tree. To be addressed in M5.

## Active risks



## Resolved risks

- ~~**JSONTestSuite edge cases**~~ — resolved 2026-06-11: the adversarial corpus is integrated (318/318, see above); marginal cases (surrogate escapes, invalid UTF-8, deep nesting, huge exponents) are now regression-locked.

- ~~**`maven-plugin-plugin` / Java 25**~~ — resolved: `maven-plugin-plugin 4.0.0-beta-2` reads major 69, `champollion-codegen-maven-plugin` uses `packaging=maven-plugin`.
- ~~**POJOs without public fields**~~ — resolved during M7.5/M7.6 (property visibility hierarchy, getter/setter conventions), validated by the JSON-B TCK.
