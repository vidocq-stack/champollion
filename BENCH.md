# Champollion — JMH Benchmarks vs Yasson / Parsson / Jackson

Performance comparison between **Champollion** (the in-house Jakarta JSON-P 2.1
and JSON-B 3.0 implementation) and the reference implementations commonly used
as baselines.

> **TL;DR** — Champollion is **comparable to Yasson** on every workload,
> **2-3× slower than Parsson** on JSON-P streaming, and **3-4× slower than
> Jackson** on binding (expected — Jackson benefits from 15 years of POJO-specific
> tuning and bytecode codegen for accessors). There is no performance cliff that
> would make Champollion unusable in production.

---

## 2026-06-11 — P11: in-buffer fast paths + the decisive allocation diagnostic

- **Hardware / JVM**: Apple M4 Max, 128 GB RAM, OpenJDK 25 LTS (Temurin).
- **Command**: `java -jar champollion-bench/target/benchmarks.jar "JsonpParseBench.(champollion|parsson)" -f 1 -wi 2 -i 3 -w 2s -r 3s -bm thrpt [-prof gc]`

**P11 changes** (`JsonReaderTokenizer`): whitespace, strings and numbers are now
scanned directly in the `char[512]` buffer in tight loops — single
`new String(buf, start, len)` materialization when no escape/boundary, bulk
line/column/offset tracking (strings and numbers cannot contain raw newlines,
so `getLocation()` stays exact), one-pass RFC 8259 number validation on the
slice. Slow paths (escapes, refill boundaries) keep the original per-char loops
and their precise error messages. Correctness: 493 jsonp tests + the full
JSONTestSuite corpus (318/318) green.

**Throughput (ops/µs)** — measurably unchanged vs the P1 baseline:

| Workload | Champollion P11 | Parsson | ratio |
|---|---:|---:|---:|
| SMALL  | 3,573 | 9,106 | 0.39× |
| MEDIUM | 0,579 | 1,284 | 0.45× |
| LARGE  | 0,007 | 0,015 | 0.47× |

**The decisive diagnostic (`-prof gc`, LARGE)**:

| | alloc.rate.norm |
|---|---:|
| Champollion | **307 840 B/op** |
| Parsson | **32 288 B/op** |

A ~10× allocation gap. The per-char scanning cost was never the bottleneck —
the GC pressure is: this benchmark drains events without calling
`getString()`, and Parsson materializes nothing in that case, while our
tokenizer allocates a `String` + a `StringToken`/`NumberToken` record for
every scalar regardless of consumption.

**Next step (the real path to 0.8×): lazy value materialization.** The
tokenizer should record the value as a (buffer range | scratch builder)
handle, materializing the `String` only when `getString()`/`getBigDecimal()`
is actually called. Design constraint identified: the parser's `hasNext()`
look-ahead may trigger a `refill()` that clobbers a still-unread range — the
promotion (range → scratch copy, no String) must happen inside `refill()`
when a pending un-materialized value exists. Touches the
tokenizer⇄parser token contract (`JsonToken` records → type enum + accessors)
and must be re-validated against both TCKs, the corpus and the jsonb
differential suite — a dedicated chantier.

---

## 1. Methodology

### Environment

- **Platform**: macOS (Darwin 25.4.0), Apple Silicon CPU
- **JVM**: OpenJDK Java 25 (HotSpot, Compiler Blackholes enabled)
- **Heap**: `-Xms1G -Xmx1G -XX:+UseG1GC` (same flags for all implementations)
- **JMH**: 1.37
- **Date**: 2026-05-04

### JMH configuration

| Parameter | Value |
|---|---|
| Forks | 1 |
| Warmup iterations | 2 × 2 s |
| Measurement iterations | 3 × 3 s |
| Modes | `Throughput` (ops/µs) + `AverageTime` (µs/op) |
| Output | `Blackhole.consume` to prevent DCE |

> Forks and iterations are intentionally reduced to stay under 15 minutes
> wall-clock time. For publication-grade results, rerun with `-f 5 -wi 5 -i 5`
> (~30 minutes). The orders of magnitude below are stable.

### Compared implementations

| Library | Version | Role |
|---|---|---|
| **Champollion** | 0.1.0-SNAPSHOT | In-house zero-dep, strict-JPMS JSON-P 2.1 + JSON-B 3.0 |
| **Yasson** | 3.0.4 | Eclipse JSON-B 3.0 reference |
| **Parsson** | 1.1.7 | Eclipse JSON-P 2.1 reference |
| **Jackson databind** | 2.18.2 | General-purpose binding (perf reference) |
| **Jackson-jr** | 2.18.2 | Minimal Jackson tier |

### Workloads

| Size | Shape | Volume |
|---|---|---|
| **SMALL** | `record User(long id, String name, boolean active)` | ~50 B JSON |
| **MEDIUM** | `Order(long, String, List<Item>×5, Address, double, boolean)` | ~700 B JSON |
| **LARGE** | `List<Order>×100` | ~70 KB JSON |

See `champollion-bench/src/main/java/io/vidocq/champollion/bench/Workloads.java`
for the definitions.

### How to reproduce

```bash
mvn -ntp -pl champollion-bench package -DskipTests
java -jar champollion-bench/target/benchmarks.jar -f 1 -wi 2 -i 3 -w 2s -r 3s
```

To target a subset: `java -jar benchmarks.jar JsonpParseBench -p size=MEDIUM`.

For a publication-grade run: `-f 5 -wi 5 -i 5` (~30 min).

---

## 2. JSON-P — pull parser (`JsonParser`)

Full scan of the event stream (`hasNext`/`next`) without building an object
model. Pure tokenizer + state-machine measurement.

### Throughput (ops/µs, higher is better)

| Workload | Champollion baseline | Champollion **P1 (buf char[512])** | Parsson | P1/Parsson |
|---|---:|---:|---:|---:|
| SMALL  | 3,612 | 3,568 | 9,381 | 0.38× |
| MEDIUM | 0,437 | **0,614** (+40%) | 1,419 | 0.43× |
| LARGE  | 0,005 | **0,008** (+60%) | 0,016 | **0.50×** |

### Latency (µs/op, lower is better)

| Workload | Champollion P1 | Parsson |
|---|---:|---:|
| SMALL  | ~0.28 µs | 0.105 µs |
| MEDIUM | ~1.63 µs | 0.71 µs |
| LARGE  | ~125 µs | 62 µs |

**Reading**: since the **P1 — tokenizer `char[512]` buffering** optimization
(`<P1>`), the LARGE gap vs Parsson shrank from **0.32× to 0.50×**
(+60% throughput) with no regression on SMALL. MEDIUM gained +40%.
`BUF_SIZE=512` is the sweet spot: larger hurts SMALL (non-amortized `char[]`
allocation), smaller cancels the LARGE gain.

**Remaining work (P4 ROADMAP)**: move to `java.lang.foreign` SIMD scanning for
whitespace and string-literal detection. Long-term target: ~0.8× Parsson on LARGE.

---

## 3. JSON-P — push generator (`JsonGenerator`)

Emits a structured object equivalent to `mediumJson()` via the streaming API.

> **Methodology note for P2** — the benchmark uses `StringWriter` as its target,
> which is already internally buffered. Therefore the P2 optimization
> (`BufferedWriter` around any writer that is not already buffered) does not show
> up in this table — it will matter on real `OutputStreamWriter` targets (the REST
> `entityStream` case). To measure P2, rerun with
> `OutputStreamWriter(new FileOutputStream(...))` or equivalent.

### Throughput (ops/µs) — `StringWriter` target (already buffered)

| Workload | Champollion | Parsson | Ratio |
|---|---:|---:|---:|
| SMALL  | 5,918 | 15,147 | 0.39× |
| MEDIUM | 0,620 | 2,218 | 0.28× |

### Throughput (ops/µs) — `OutputStreamWriter` target (real REST case)

Benchmark `JsonpGenerateOSBench`, sink = `OutputStreamWriter(NullOutputStream, UTF-8)`.

| Workload | Without P2 | **P2 (BufferedWriter 256)** | Δ |
|---|---:|---:|---:|
| SMALL  | 3,594 | **4,385** | **+22%** |
| MEDIUM | 0,402 | **0,594** | **+48%** |

**Reading**: on `OutputStreamWriter` (what Cassini uses via
`MessageBodyWriter.writeTo(... entityStream ...)`), P2 brings +22% to +48%
depending on payload size. A 256-char buffer is the sweet spot: larger values
(1024) regress SMALL by -27% because allocation cost is not amortized, smaller
values erase the MEDIUM gain. On the `StringWriter` target (bench-only), P2 is a
no-op: Champollion detects `StringWriter` and does not wrap it.

### Latency (µs/op)

| Workload | Champollion | Parsson |
|---|---:|---:|
| SMALL  | 0.170 µs | 0.066 µs |
| MEDIUM | 1.615 µs | 0.460 µs |

**Reading**: roughly 3× slower than Parsson. Same optimization direction as the
parser (buffering + direct writes).

---

## 4. JSON-B — write (`Jsonb.toJson`)

POJO + record to JSON string.

### Throughput (ops/µs)

| Workload | Champollion record | Yasson record | Jackson record | Jackson-jr POJO |
|---|---:|---:|---:|---:|
| SMALL  | 4,580 | **30,001 ⚠️** | 11,563 | 10,240 |
| MEDIUM | 0,514 | 0,575 | 1,786 | 1,639 |
| LARGE  | 0,005 | 0,006 | 0,020 | 0,018 |

| Workload | Champollion POJO | Yasson POJO | Jackson POJO |
|---|---:|---:|---:|
| SMALL  | 4,485 | 4,179 | 11,253 |
| MEDIUM | 0,512 | 0,567 | 1,797 |
| LARGE  | 0,005 | 0,006 | 0,020 |

### Latency (µs/op)

| Workload | Champollion | Yasson | Jackson | Jackson-jr |
|---|---:|---:|---:|---:|
| SMALL POJO  | 0,224 | 0,243 | 0,088 | 0,097 |
| SMALL record| 0,219 | **0,037 ⚠️** | 0,085 | — |
| MEDIUM      | 1,957 | 1,773 | 0,557 | 0,618 |
| LARGE       | 183 µs | 177 µs | 50 µs | 56 µs |

⚠️ **Interesting point — Yasson SMALL record**: 30 ops/µs throughput, suggesting
a cache or short-circuit specific to 3-field records. The advantage disappears on
MEDIUM/LARGE.

**Reading**:
- **Champollion ≈ Yasson** on most workloads (±10%). Champollion handles
  SMALL/MEDIUM records better in practice because it does not require `@JsonbCreator`
  boilerplate, unlike Yasson on a strict module path (see `JSON-ROADMAP.md` §1).
- **Jackson is 3-4× faster**: that is the market reference ceiling. Jackson uses
  ASM codegen for accessors and a highly optimized internal buffer.
- **Jackson-jr** is almost as fast as Jackson databind on SMALL/MEDIUM
  (~10% slower only).

---

## 5. JSON-B — read (`Jsonb.fromJson`)

JSON string to POJO + record.

### Latency (µs/op, lower is better)

| Workload | Champollion | Yasson | Jackson | Jackson-jr |
|---|---:|---:|---:|---:|
| SMALL POJO   | 0,307 | 0,425 | 0,132 | 0,154 |
| SMALL record | 0,317 | — | 0,169 | — |
| MEDIUM       | 2,645 | 3,645 | 1,033 | 1,034 |
| LARGE        | (n/c) | 348 µs | 104 µs | 158 µs |

> Note: Champollion LARGE read is not directly measured in this table — see
> below; the benchmark loads `List<Order>` via `TypeReference` for Jackson only
> (Champollion also uses `largeType`, but the result is in the full log).

**Reading**:
- **Champollion beats Yasson** on reads (~25-30% faster on SMALL and MEDIUM).
- **Jackson is 2-3× faster** than Champollion on reads, as expected.
- **Jackson-jr is nearly equivalent to Jackson databind** on SMALL/MEDIUM, a bit
  slower on LARGE.

---

## 6. Summary

### Positioning

```
        Relative throughput (Champollion = 1.0 on each line)
        ┌──────────────────────────────────────────────────────┐
JSON-P  │ Champollion ━━━ │ Parsson ━━━━━━━━━━━━━━━━━━━━━━━━━━━ │   (Parsson ≈ 2.5×)
parse   └──────────────────────────────────────────────────────┘
        ┌──────────────────────────────────────────────────────┐
JSON-P  │ Champollion ━━━ │ Parsson ━━━━━━━━━━━━━━━━━━━━━━━━━━━ │   (Parsson ≈ 3×)
gen     └──────────────────────────────────────────────────────┘
        ┌──────────────────────────────────────────────────────┐
JSON-B  │ Champollion ━━━━━━ │ Yasson ━━━━━━ │ Jackson ━━━━━━━━━━━━━━━━━━━━━━━━━━ │   (Jackson ≈ 3-4×)
write   └──────────────────────────────────────────────────────┘
        ┌──────────────────────────────────────────────────────┐
JSON-B  │ Champollion ━━━━━━━━━━ │ Yasson ━━━━━━━━ │ Jackson ━━━━━━━━━━━━━━━━━━━━━━━━━━ │   (Jackson ≈ 3×)
read    └──────────────────────────────────────────────────────┘
```

### Conclusions

1. **Champollion is competitive with Yasson** across the JSON-B spectrum —
   that is the primary target, since this is what Cassini replaces.
2. **Champollion is slower than Parsson** on JSON-P streaming (~2.5-3×).
   Acceptable for a v0.1 zero-dependency implementation; `java.lang.foreign`
   SIMD scanning remains a future optimization path.
3. **The gap with Jackson is expected** — Jackson has 15 years of native
   optimizations and a codegen ecosystem (ASM, jr/object, smile). Matching Jackson
   will require significant R&D (bytecode codegen for accessors, ad hoc char/byte
   buffers).
4. **Champollion handles records natively** without `@JsonbCreator`. Yasson on a
   strict module path breaks silently on records (see `JSON-ROADMAP.md` §1) —
   that is the practical differentiating advantage.

### Non-measured aspects (to explore)

- **Cold start** (first serialization after init) — useful for serverless / short-lived functions.
- **GC allocation rate** (`-prof gc`) — Champollion uses `LinkedHashMap` everywhere; compare churn.
- **Memory footprint** (`-prof stack`).
- **Real REST workload** — end-to-end Cassini benchmark with Yasson vs Champollion
  (to do after M-INT integration).

---

## 7. Static codegen (M5) — `@JsonbStatic`

The `champollion-codegen-apt` APT generates, for each record annotated
`@JsonbStatic`, a `<Type>$$Binding` (direct bytecode, final class, zero reflection).
At runtime, `ChampollionJsonb` checks these bindings through `ServiceLoader`
**before** the introspective runtime path: if a binding exists, it branches
directly to `JsonbBinding.write/read`.

Dedicated benchmark: `JsonbWriteBenchStatic` / `JsonbReadBenchStatic` on top-level
annotated records (`SmallStaticRecord`, `OrderStaticRecord`, `ItemStaticRecord`,
`AddressStaticRecord`). The `champollion_runtime` mode of the same benchmark
forces `withStaticBindings(List.of())` to bypass `ServiceLoader` and measure the
introspective baseline (MethodHandles + cache).

### 7.1 Stable run — 2026-05-04 (post-`writeString` fast-path)

> JMH: `-f 2 -wi 2 -w 2s -i 3 -r 2s`. Yasson on SMALL/MEDIUM write produces
> abnormally high scores (28 / 19 ops/µs) — suspected Blackhole DCE in
> compiler mode on a `Jsonb.toJson(record)` return value that HotSpot may have
> hoisted. Not published in the main comparison (noted in the table header).

#### Write — Throughput (ops/µs, higher = better)

| Workload | champollion_runtime | **champollion_static** | APT gain | jacksonJr | jackson | yasson† |
|---|---:|---:|---:|---:|---:|---:|
| **SMALL**  | 5.75 ±0.27 | **9.59 ±0.98** | **+67%** | 10.28 ±0.15 | 11.56 ±0.41 | (28.08†) |
| **MEDIUM** | 0.69 ±0.02 | **1.14 ±0.06** | **+66%** | 1.62 ±0.02 | 1.76 ±0.04 | (18.78†) |

`champollion_static / jacksonJr` ≈ **0.93× on SMALL** (near parity), 0.70× on MEDIUM.
`champollion_static / jackson`   ≈ **0.83× on SMALL**, 0.65× on MEDIUM.
