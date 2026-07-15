# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Prerequisites

- **Java 25** + **Maven 3.9.16** (`.sdkmanrc` provided — use `sdk env`)
- Official TCKs must be installed in the local M2 (non-public artifacts):
  - `jakarta.json:jakarta.json-tck:2.1.x` (JSON-P 2.1)
  - `jakarta.json.bind:jakarta.json.bind-tck:3.0.x` (JSON-B 3.0)

## Essential Commands

```bash
# Build reactor (without TCK)
mvn -ntp install -DskipTests

# Unit tests
mvn test

# JMH benchmarks
mvn -pl champollion-bench -am package
java -jar champollion-bench/target/benchmarks.jar

# JSON-P TCK — smoke test only
./run-official-tck-jsonp-2.1.sh

# JSON-B TCK — smoke test only
./run-official-tck-jsonb-3.0.sh
```

> `champollion-tck` is **out-of-reactor** (standalone POM Model 4.0.0) to work around
> ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0 — same constraint as `cassini-tck`
> and `foy-tck`. Do not change this model.

## Architecture

Champollion is a Jakarta JSON Processing 2.1 + Jakarta JSON Binding 3.0 implementation,
**zero dependencies beyond Jakarta specs**, virtual threads, strict Java Modules, and static
compilation of bindings via APT to avoid runtime reflection.

```
champollion-api        ← Jakarta spec only (re-exposing jakarta.json + jakarta.json.bind)
champollion-jsonp      ← JSON-P 2.1 implementation (parser, generator, JsonValue, JsonPatch, JsonPointer, JsonMergePatch)
champollion-jsonb      ← JSON-B 3.0 implementation (Jsonb, JsonbBuilder, ser/deser, customization)
champollion-codegen    ← APT + Maven plugin: generates JSON-B serializers/deserializers at compile time
champollion-bench      ← JMH: comparison Parsson/Yasson/Jackson, throughput/latency, allocations
champollion-examples   ← Usage examples
champollion-tck        ← Official JSON-P 2.1 and JSON-B 3.0 TCK runners (OUT OF REACTOR)
```

**JSON-P flow:** `JsonParser` (pull) ↔ `JsonGenerator` (push) on `Reader/Writer/InputStream/OutputStream`.
Object model `JsonObject/JsonArray/JsonValue` on top. Patch/Pointer/MergePatch as leaf layer.

**JSON-B flow:** `Jsonb.toJson(obj)` → `BindingPlan` (resolved once per class) → series of
`PropertyWriter` that push into a `JsonGenerator` (champollion-jsonp). Symmetric on reading.

**Two binding modes:**
- **Runtime mode** (default): introspection on first encounter of the class via `MethodHandles`,
  concurrent cache. No reflection on every call — cost amortized after warmup.
- **Static mode** (recommended): APT `champollion-codegen` generates a `BindingFactory` per type
  annotated `@JsonbStatic` (or detected by classpath scan via the Maven plugin). No runtime reflection,
  AOT-compatible (GraalVM, Leyden CDS). ServiceLoader automatically resolves the generated factory.

## Architecture Constraints Not to Violate

1. **Zero dependencies beyond Jakarta specs** in `champollion-jsonp` and `champollion-jsonb`.
   JUnit/JMH only in `scope=test`/`scope=provided`.
2. **`champollion-jsonb` depends on `champollion-jsonp`** but never the reverse — the binding
   layer knows how to compose on the processing layer, not vice versa.
3. **Strict Java Modules**: all modules have a `module-info.java`, `internal.*` packages not exported,
   SPI exposed only via `provides ... with`.
4. **No `synchronized`, no `ThreadLocal`** — virtual-thread-friendly. Use `ScopedValue`
   for contextual propagation (e.g. `JsonbContext.CURRENT` during a `toJson`).
5. **No `setAccessible(true)` reflection** except in explicitly documented runtime mode;
   prefer `MethodHandles.privateLookupIn` + `module.addOpens` on the consumer side.
6. **JSON-P and JSON-B TCK PASS at 100%** is a hard contract before any structural merge on `jsonp`/`jsonb`.

## Conventions

- **Explicit Java modules**: all modules have a `module-info.java`.
- **Packages**:
  - `io.vidocq.champollion.spi.*` = stable public SPI (third-party extensions)
  - `io.vidocq.champollion.internal.*` = internal code (may break between versions)
- **Maven groupId**: `io.vidocq.champollion`.
- **Records** for all immutable DTOs; **sealed interfaces** for closed hierarchies
  (`JsonValue`, `JsonEvent`, `BindingNode`).
- **Exhaustive pattern matching** on switch — no `if/else if` chains.
- **`java.lang.foreign`** considered for the hottest JSON scanner (SIMD-friendly parser).
- **Language** — commit messages, Javadoc, and all `.md` file content must be written in **English**.

## Current Roadmap

See `ROADMAP.md` for the detailed phase-by-phase plan (M0..M7).

## TDD — Test-Driven Development (mandatory)

Champollion is developed with **strict TDD**, in this order:

1. **Red** — write the test describing the expected behavior (cite the spec or RFC in JavaDoc comments).
   The test must fail for the right reason (compilation OK, assertion KO).
2. **Green** — write the minimum code to make the test pass. No optimization, no abstraction
   anticipating a future test.
3. **Refactor** — clean up while keeping tests green. Run the full module suite before any commit.

Concrete rules:

- **One test per public class**, named `<Class>Test`, in the same package (`src/test/java`).
- **No Mockito** — hand-written doubles; the code decoupling lends itself to this.
- **Spec fixture tests**: for each referenced RFC 8259 / Jakarta JSON-P 2.1 / Jakarta JSON-B 3.0
  section, a test named `<method>_rfc8259_section6_4()` or similar. Enables spec ↔ test traceability.
- **JSONTestSuite** (`nst/JSONTestSuite`) integrated from M1 in `champollion-jsonp/src/test/resources/`
  for an RFC 8259 conformance harness independent of the TCK.
- **Coverage measured** but not used as a gate; test quality takes precedence over percentage.
- **Differential testing** between reflective runtime (M4) and static codegen (M5): for each tested
  type, verify that `runtime.toJson(o).equals(static.toJson(o))` and symmetrically on reading.

## TCK — Technology Compatibility Kits

Two official TCKs, run in an out-of-reactor module (`champollion-tck`, POM Model 4.0.0)
to work around ShrinkWrap Maven Resolver 3.3:

| TCK | Artifact | Target |
|---|---|---|
| Jakarta JSON Processing 2.1 | `jakarta.json:jakarta-json-tck:2.1.x` | 100% PASS (hard contract) |
| Jakarta JSON Binding 3.0 | `jakarta.json.bind:jakarta-json-bind-tck:3.0.x` | 100% PASS (hard contract) |

The `run-official-tck-jsonp-2.1.sh` and `run-official-tck-jsonb-3.0.sh` scripts:

- support `smoke` (default), `all`, and targeted `-Dtest=TestName`;
- install the reactor locally (`mvn install -DskipTests`) before invocation;
- produce a `target/tck-report.txt` report with the PASS/FAIL/SKIP score.

**Release discipline:**

- **No structural merge** on `champollion-jsonp`/`champollion-jsonb` without TCK PASS.
- Any challenges (disabled tests for spec interpretation or TCK bug) are documented
  in `TCK.md` with spec citation, test hash, and reactivation plan. Follows the `cassini/TCK.md` model.
- **Differential mode**: the TCK must pass in both runtime mode and static codegen mode.
  The script `run-official-tck-jsonb-3.0.sh --static` recompiles TCK fixtures with APT to validate
  codegen consistency.

## AI Principles — Collaboration on This Repository

- **Plan mode by default** on any structural change (new module, new wire format,
  modification of public SPI).
- **Balanced elegance**: prefer a simple design that passes the TCK over a perfect design that
  does not. Document trade-offs in ADRs (`docs/adr/`).
- **No laziness on specs**: cite the RFC 8259 / Jakarta JSON-P 2.1 / Jakarta JSON-B 3.0
  section in code comments when the implementation directly responds to it.
- **Zero dependencies**: if an external library seems necessary, the decomposition is wrong.

## Documentation (Antora) conventions

The project documentation lives in `docs/en` and `docs/fr` as Antora modules and is
aggregated by the **vidocq-docs** site, which provides a **shared UI bundle** (banner,
logo, fonts, colours, footer). **Never customise the documentation UI per project** —
all visual harmonisation is centralised in `vidocq-docs/ui-bundle`.

### Gold reference
**Vauban** is the reference implementation for documentation structure. Mirror its
`docs/en` + `docs/fr` layout when creating or updating docs. **Chappe** (HTTP server)
and **Vidocq** (runtime orchestrator) are *special cases*, not references: they are not
Jakarta EE / MicroProfile spec implementations.

### Repository layout
- `docs/en/antora.yml` → `name: <project>`, `title:`, `version: ~`, `nav:`, `lang: en`.
- `docs/fr/antora.yml` → `name: <project>-fr`, same `title`, `lang: fr`.
- Pages in `modules/ROOT/pages/`, navigation in `modules/ROOT/nav.adoc`, images in
  `modules/ROOT/images/`.
- **EN/FR parity**: every page exists in both languages with translated content.

### Canonical navigation (section order)
`index` → `getting-started` → `usage` → `concepts` → `internals` → `tck` →
`performance` → `reference` → `migration`

Multi-module projects (e.g. Vidocq, Mansart) may append `modules/*` / `sub-modules/*`
sub-pages after `migration`.

### TCK / Performance rule (not mutually exclusive)
- Every **spec implementation** — i.e. **all projects except Chappe and Vidocq** — MUST
  have a **`tck`** section documenting TCK coverage/status.
- Projects with a performance story (e.g. **Chappe**) keep their **`performance`** section.
- When **both** sections exist, order them **TCK first, then Performance**.
- **Chappe** and **Vidocq** do not require a `tck` section (not spec implementations).

### `index.adoc` structure
Follow Vauban's `index.adoc`: page title (`= <Project>`), `:description:`, a centred logo
(`image::<project>-logo.png[...,role=module-logo]`), a `[.lead]` paragraph, then
`== Origin of the name`, an `== At a glance` table, and ecosystem / quick-links sections.

### Logo
Provide `modules/ROOT/images/<project>-logo.png` (PNG), referenced from `index.adoc`.

> When you change these documentation rules, keep `AGENTS.md` and `CLAUDE.md` in sync.

## Terminology

Use **Java Modules** (or **Java module** for a single module) when referring to
the Java Platform Module System. Do **not** use the abbreviation **JPMS** — in
prose, identifiers, or documentation.
