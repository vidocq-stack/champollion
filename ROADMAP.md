# Champollion — Implementation plan

> Jakarta JSON Processing 2.1 (JSON-P) + Jakarta JSON Binding 3.0 (JSON-B)
> implementation in the Vidocq style: zero dependency, JDK 25, virtual threads, strict JPMS,
> static binding compilation via APT/Maven plugin.

## Design principles

| Principle | Concrete application |
|---|---|
| Zero dependency | No Parsson/Yasson/Jackson in `champollion-jsonp`/`champollion-jsonb`. Only the Jakarta spec APIs are compiled. |
| Virtual threads | No `synchronized`, no `ThreadLocal`. `ConcurrentHashMap`/`ClassValue` caches. Propagation via `ScopedValue`. |
| Static compilation (2 separate artifacts) | `champollion-codegen-apt` = pure JDK Annotation Processor (usable on its own, without Maven). `champollion-codegen-maven-plugin` = Mojo that scans the classpath and delegates to the APT for non-annotatable classes. |
| Reflective runtime fallback | If no `BindingFactoryProvider` is found for a type, the introspective runtime takes over. Enables progressive bootstrap and compatibility with non-recompilable third-party classes. |
| JPMS strict | `module-info.java` everywhere, `internal.*` not exported, SPI via `provides/uses`. |
| Strict TDD | Red → Green → Refactor. Tests written before production code. See the TDD section below. |
| TCK PASS 100 % | Hard contract on JSON-P 2.1 and JSON-B 3.0, in both runtime and static codegen modes. |
| Measured performance | JMH from M1 onward, systematic comparison with Parsson/Yasson/Jackson, baseline ratchet. |

## Methodology: TDD + TCK as parallel safeguards

Champollion is developed with **strict TDD** (Red → Green → Refactor). No production line
is written before a test justifies it. Beyond the internal TDD cycle:

- **Layer 1 — TDD unit tests**: drive the design of each class.
- **Layer 2 — JSONTestSuite (RFC 8259)**: integrated scanner conformance harness from M1 onward; it does
  not depend on the TCK and remains reliable for RFC coverage.
- **Layer 3 — official TCKs** (`jakarta.json-tck` + `jakarta.json.bind-tck`): 100% PASS contract
  before any structural merge on `jsonp`/`jsonb`. Module outside the reactor (POM Model 4.0.0).
- **Layer 4 — Differential testing**: between reflective runtime and static codegen, across 100+ heterogeneous
  types, on every commit touching `jsonb` or `codegen-apt`.

The TCKs are run in two modes:
- **runtime** (JSON-B introspection) — validates spec compliance.
- **static** (recompiling TCK fixtures with the APT) — validates that codegen produces the same
  observable behavior as the runtime.

## Phases

### M0 — Bootstrap (this session)

- [x] `.sdkmanrc`, `.gitignore`, `.mvn/maven.config`
- [x] parent `pom.xml` (Model 4.1.0, multi-module, Jakarta dependency management)
- [x] `CLAUDE.md` (TDD + TCK included)
- [x] `ROADMAP.md`
- [x] Creation of the 7 submodules: `champollion-api`, `champollion-jsonp`, `champollion-jsonb`,
      `champollion-codegen-apt`, `champollion-codegen-maven-plugin`, `champollion-bench`,
      `champollion-examples` (with skeletal `pom.xml` + `module-info.java`)
- [ ] `LICENSE` (Apache 2.0)
- [ ] `README.md`
- [ ] Validation that `mvn -ntp install -DskipTests` succeeds (empty reactor)

**Deliverable:** `mvn -ntp install -DskipTests` succeeds on an empty reactor, JPMS resolves all modules.

---

### M1 — JSON-P 2.1 streaming (pull parser / push generator)

**Scope spec:** §3 (Streaming API) of Jakarta JSON Processing 2.1.

| Task | Notes |
|---|---|
| `JsonParser` pull-based on `Reader` and `InputStream` | UTF-8 / UTF-16 BE/LE / UTF-32 BE/LE BOM detection; strict RFC 8259 |
| `JsonParserFactory` + `Json.createParserFactory(Map)` | `pretty-printing`, `buffer-pool-size`, etc. |
| `JsonGenerator` push-based on `Writer` and `OutputStream` | Pretty-printing, indentation, RFC 8259 §7-compliant escaping |
| `JsonGeneratorFactory` | Same on the config side |
| Buffer pool (`JsonProvider#createBufferPool`) | Simple, dependency-free `MpmcBoundedBuffer` |
| `JsonProvider` impl + `META-INF/services/jakarta.json.spi.JsonProvider` | ServiceLoader entry point |
| Character-by-character scanner, as branchless as possible | RFC 8259 state machine transition table |
| Tests: RFC 8259 conformance suite (NSTI, JSONTestSuite) | Open source, integrated in `src/test/resources` |
| JMH bench: parser throughput vs Parsson | Target: ≥ Parsson at the 90th percentile |

**Deliverable:** `Json.createParser(reader).next()` and `Json.createGenerator(writer).write(...)` work,
RFC 8259 tests green, benchmark published.

---

### M2 — JSON-P 2.1 object model

**Scope spec:** §4 (Object Model) + §6 (Json class factory methods).

| Task | Notes |
|---|---|
| `JsonValue`, `JsonString`, `JsonNumber` (sealed) | Immutable records; `JsonNumber` backed by lazy `BigDecimal` |
| `JsonObject` (LinkedHashMap-backed, insertion order) | Iteration order preserved (see spec §4.2) |
| `JsonArray` | Immutable `List<JsonValue>` |
| `JsonObjectBuilder` / `JsonArrayBuilder` | Mutable during construction, `build()` returns immutable |
| `JsonReader` / `JsonWriter` (value-oriented) | Backed by the M1 parser/generator |
| `JsonString`: UTF-16 surrogate escaping | RFC 8259 §7 compliant |
| `JsonNumber`: integer vs decimal, exact match | `intValueExact`, `bigIntegerValueExact` |
| JSON-P 2.1 TCK tests — object-model section | Must pass |

**Deliverable:** JSON-P 2.1 TCK green on the Streaming + Object Model sections.

---

### M3 — JSON-P 2.1 Patch / Pointer / Merge Patch

**Scope spec:** §5 (Patch RFC 6902, Pointer RFC 6901, Merge Patch RFC 7396).

| Task | Notes |
|---|---|
| `JsonPointer` | RFC 6901, escape `~0`/`~1`, indexation tableau |
| `JsonPatch` | RFC 6902, operations `add`/`remove`/`replace`/`move`/`copy`/`test` |
| `JsonMergePatch` | RFC 7396 |
| `JsonPatchBuilder` / `JsonMergePatchBuilder` | Fluent builder API |
| JSON-P 2.1 TCK tests — patch/pointer section | Must pass |

**Deliverable:** JSON-P 2.1 TCK 100% PASS (contract target).

---

### M4 — JSON-B 3.0 runtime (reflective mode)

**Scope spec:** Jakarta JSON Binding 3.0, sections 3 (Default Mapping) to 4.7 (Custom Mapping).

| Task | Notes |
|---|---|
| `JsonbBuilder` SPI + `JsonbProvider` impl | ServiceLoader, `Jsonb.create()` entry point |
| `JsonbConfig` | Properties standard : `JSONB_NULL_VALUES`, `JSONB_FORMATTING`, `JSONB_LOCALE`, etc. |
| `BindingPlan` per class, `ClassValue<BindingPlan>` cache | Computed once, lock-free reads |
| `PropertyWriter` / `PropertyReader` backed by `MethodHandles` | No hot `setAccessible` |
| Default adapters: primitives, String, Number, BigDecimal, BigInteger, `java.time` dates | `Date`/`Calendar` deprecated but supported (TCK) |
| `Collection`, `Map`, `Optional`, arrays, enums | Spec §3.5 compliant |
| Polymorphism: `@JsonbTypeInfo`, `@JsonbSubtype` | New in JSON-B 3.0 |
| Customization: `@JsonbProperty`, `@JsonbTransient`, `@JsonbDateFormat`, `@JsonbNumberFormat`, `@JsonbAdapter` | Full coverage |
| `JsonbCreator` factory | Native records + immutable classes |
| Unit tests + initial JSON-B 3.0 TCK passes | Coverage ≥ 80% before M5 |

**Deliverable:** `Jsonb.create().toJson(obj)` / `fromJson(...)` work in runtime mode,
records bound without config, JSON-B 3.0 TCK Default + Customization sections green.

---

### M5 — JSON-B 3.0 static codegen (APT + Maven plugin)

**Scope:** generate a `BindingFactory<T>` at compile time for each target type and register it
via ServiceLoader. The reflective runtime (M4) remains the **fallback** when no generated factory is found.

**Two separate artifacts** (decided):

- `champollion-codegen-apt` — pure JDK Annotation Processor (`Processor` SPI). Usable on its own via
  `javac -processorpath`, without Maven. Target: generated Java code, **not bytecode**.
- `champollion-codegen-maven-plugin` — Maven Mojo that scans the host project classpath and invokes
  the APT on detected JSON-B types not annotated `@JsonbStatic`. For third-party classes.

| Task | Notes |
|---|---|
| `champollion-codegen-apt`: `Processor` for `@JsonbStatic` | `javax.annotation.processing.Processor`; ServiceLoader `META-INF/services` |
| Generation of `<FQN>$$Binding.java` (writer + reader) | Pure Java code, no bytecode, readable and debuggable |
| Generation of the `BindingFactoryProvider` ServiceLoader file source | `META-INF/services/io.vidocq.champollion.jsonb.spi.BindingFactoryProvider` |
| `champollion-codegen-maven-plugin` `generate` Mojo | Bound to `generate-sources`; `<targets>`/`<excludes>` parameter |
| The Mojo delegates 100% to `champollion-codegen-apt` | No duplication — the plugin is an orchestrator |
| Runtime fallback: `BindingFactoryProvider` not found → M4 introspection | Logged at INFO; `champollion.jsonb.warn-on-fallback=true` flag for audit |
| Optimizations: precompiled escape for property names (UTF-8 byte array constants) | `private static final byte[] PROP_NAME = {...}` |
| **Differential testing**: static binding vs runtime → same results on 100+ classes | Automated suite, gate before merge |
| JMH bench: static vs runtime, vs Yasson | Target: >2× Yasson throughput, allocs ≈ 0 |

**Deliverable:** generated factory auto-discovered via ServiceLoader, zero reflection in `Jsonb.toJson(obj)`
when the class was compiled with the APT or scanned by the Maven plugin. Reflective runtime intact
as fallback.

---

### M6 — Official TCKs (outside the reactor)

| Task | Notes |
|---|---|
| `champollion-tck/pom.xml` standalone Model 4.0.0 | Same as `cassini-tck`/`foy-tck` |
| `run-official-tck-jsonp-2.1.sh` | Smoke + full + targeted |
| `run-official-tck-jsonb-3.0.sh` | Same |
| `TCK.md` | Documents any challenges (tests disabled with spec justification) |
| Contract score: 100% PASS on both TCKs | Blocking regression |

**Deliverable:** shell scripts + reproducible TCK report on every release.

---

### M7 — Vidocq ecosystem integration

| Task | Notes |
|---|---|
| Adapt `cassini-champollion` (Cassini side): `MessageBodyReader/Writer<JsonValue>` and `<Object>` via JSON-B | Replaces Parsson/Yasson in Cassini |
| Adapt `chappe-champollion` (optional): JSON `BodyHandler` for Chappe | Non-JAX-RS case |
| Documentation: `docs/integration-cassini.md`, `docs/integration-chappe.md` | Mermaid diagrams |
| End-to-end bench: Cassini + Champollion vs Cassini + Yasson | JSON request throughput / sec |

**Deliverable:** Cassini ships a release with no Parsson/Yasson dependency.

---

## Priority order — why this one?

1. **M1 → M3 (JSON-P)** first because JSON-B depends on it strictly. No shortcut.
2. **M4 (JSON-B runtime)** before M5 (codegen) because the runtime is the reference oracle: we
   compare codegen outputs against runtime outputs in differential testing. Without the runtime,
   codegen flies without a net.
3. **M6 (TCK)** is an ongoing activity from M2/M3 onward for JSON-P, from M4 onward for JSON-B, but
   the "100% PASS" objective only becomes a contract at the end of each scope.
4. **M7 (integration)** comes last: we will not pollute Cassini before Champollion is solid. The swap
   will happen behind a dedicated PR.

## Known risks

| Risk | Mitigation |
|---|---|
| Strict RFC 8259 JSON parser conformance (edge cases like "JSONTestSuite") | Integrate the `nst/JSONTestSuite` corpus from M1 onward, parallel to the TCK |
| JSON-B 3.0 polymorphism (`@JsonbTypeInfo`) — new feature, few examples | Read the spec and then the TCK before coding; study Yasson 3.x as a reference implementation |
| APT codegen and JPMS: `provides` generated dynamically, but `module-info` is fixed in source | The Maven plugin generates a `module-info-extra.java` or adds `provides` via `--add-modules`; otherwise manual descriptor + factory of factories |
| Accessible official JSON-P/JSON-B TCKs? | Check availability in the local Eclipse Foundation M2; otherwise use the community TCK |
| GraalVM AOT compatibility | Test `native-image` on `champollion-examples` from M5 onward to validate the absence of reflection |

## Decided decisions

- ✅ **Two separate artifacts** for static compilation: `champollion-codegen-apt` (pure JDK)
  and `champollion-codegen-maven-plugin` (Maven orchestrator). The APT does not depend on Maven.
- ✅ **Reflective runtime kept as fallback**: enables progressive bootstrap and compatibility with
  non-recompilable third-party classes. Behavior logged.
- ✅ **Strict TDD** on all production modules.
- ✅ **TCK PASS 100 %** as a hard contract, in both runtime and static modes.

## Open decisions

- [ ] Should we expose a "stream-as-iterator" mode for JSON-P (beyond the spec contract)?
- [ ] Adopt `java.lang.foreign` for the UTF-8 scanner from M1 onward or defer to M5? → Defer; pure JDK baseline first.
- [ ] Static-mode `@JsonbTypeInfo` polymorphism: requires a sealed enum of subtypes at compile time.
  How should openness (dynamic extensions) be handled? → To be addressed in M5.
