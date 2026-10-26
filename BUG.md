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
