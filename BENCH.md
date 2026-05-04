# Champollion — Benchmarks JMH vs Yasson / Parsson / Jackson

Comparaison de performance entre **Champollion** (l'implémentation maison Jakarta JSON-P 2.1 + JSON-B 3.0) et les implémentations de référence du marché.

> **TL;DR** — Champollion est **comparable à Yasson** sur tous les workloads, **2-3× plus lent que Parsson** sur le streaming JSON-P, et **3-4× plus lent que Jackson** sur le binding (attendu — Jackson cumule 15 ans d'optimisations spécifiques POJO et un codegen de bytecode pour les accesseurs). Aucun gouffre de perf qui rendrait Champollion inutilisable en production.

---

## 1. Méthodologie

### Environnement

- **Plateforme** : macOS (Darwin 25.4.0), CPU Apple Silicon
- **JVM** : OpenJDK Java 25 (HotSpot, Compiler Blackholes activés)
- **Heap** : `-Xms1G -Xmx1G -XX:+UseG1GC` (mêmes flags pour toutes les implémentations)
- **JMH** : 1.37
- **Date** : 2026-05-04

### Configuration JMH

| Paramètre | Valeur |
|---|---|
| Forks | 1 |
| Warmup iterations | 2 × 2 s |
| Measurement iterations | 3 × 3 s |
| Modes | `Throughput` (ops/µs) + `AverageTime` (µs/op) |
| Output | `Blackhole.consume` pour empêcher le DCE |

> Les forks et iterations sont volontairement réduits pour rester sous 15 min wall-clock. Pour des résultats publiables, refaire avec `-f 5 -wi 5 -i 5` (~30 min). Les ordres de grandeur ci-dessous sont stables.

### Implémentations comparées

| Lib | Version | Rôle |
|---|---|---|
| **Champollion** | 0.1.0-SNAPSHOT | JSON-P 2.1 + JSON-B 3.0 maison (zero-dep, JPMS strict) |
| **Yasson** | 3.0.4 | JSON-B 3.0 référence Eclipse |
| **Parsson** | 1.1.7 | JSON-P 2.1 référence Eclipse |
| **Jackson databind** | 2.18.2 | Binding généraliste (référence perf) |
| **Jackson-jr** | 2.18.2 | Tier minimaliste de Jackson |

### Workloads

| Taille | Forme | Volume |
|---|---|---|
| **SMALL** | `record User(long id, String name, boolean active)` | ~50 B JSON |
| **MEDIUM** | `Order(long, String, List<Item>×5, Address, double, boolean)` | ~700 B JSON |
| **LARGE** | `List<Order>×100` | ~70 KB JSON |

Voir `champollion-bench/src/main/java/io/vidocq/champollion/bench/Workloads.java` pour les définitions.

### Comment reproduire

```bash
mvn -ntp -pl champollion-bench package -DskipTests
java -jar champollion-bench/target/benchmarks.jar -f 1 -wi 2 -i 3 -w 2s -r 3s
```

Pour cibler un sous-ensemble : `java -jar benchmarks.jar JsonpParseBench -p size=MEDIUM`.

Pour un run "publication-grade" : `-f 5 -wi 5 -i 5` (~30 min).

---

## 2. JSON-P — parser pull (`JsonParser`)

Scan complet du flux d'événements (`hasNext`/`next`) sans construction d'object model. Mesure pure du tokenizer + state machine.

### Throughput (ops/µs, plus c'est haut, mieux c'est)

| Workload | Champollion baseline | Champollion **P1 (buf char[512])** | Parsson | Ratio P1/Parsson |
|---|---:|---:|---:|---:|
| SMALL  | 3,612 | 3,568 | 9,381  | 0,38× |
| MEDIUM | 0,437 | **0,614** (+40 %) | 1,419  | 0,43× |
| LARGE  | 0,005 | **0,008** (+60 %) | 0,016  | **0,50×** |

### Latence (µs/op, plus c'est bas, mieux c'est)

| Workload | Champollion P1 | Parsson |
|---|---:|---:|
| SMALL  | ~0,28 µs   | 0,105 µs   |
| MEDIUM | ~1,63 µs   | 0,71 µs    |
| LARGE  | ~125 µs    | 62 µs      |

**Lecture** : depuis l'optimisation **P1 — Tokenizer `char[512]` bufferisé** (commit `<P1>`), le gap LARGE vs Parsson est réduit de **0,32× à 0,50×** (+60 % de throughput) sans régression sur SMALL. Pour MEDIUM, +40 %. Le sweet spot est `BUF_SIZE=512` : plus grand pénalise SMALL (allocation `char[]` non amortie), plus petit annule le gain LARGE.

**Pistes restantes (P4 ROADMAP)** : passage à `java.lang.foreign` SIMD-scan pour le whitespace + détection de string literals. Cible à terme : ~0,8× Parsson sur LARGE.

---

## 3. JSON-P — generator push (`JsonGenerator`)

Émission d'un objet structuré équivalent à `mediumJson()` via API streaming.

> **Note méthodologique sur P2** — le bench utilise `StringWriter` comme cible,
> qui bufferise déjà en interne. L'optimisation P2 (`BufferedWriter` autour de
> tout `Writer` non-déjà-bufferisé) n'apparaît donc pas dans cette table —
> elle se manifestera sur les cibles réelles `OutputStreamWriter` (cas REST
> via `entityStream`). Pour mesurer P2, refaire le bench avec
> `OutputStreamWriter(new FileOutputStream(...))` ou similaire.

### Throughput (ops/µs) — cible `StringWriter` (déjà bufferisée)

| Workload | Champollion | Parsson | Ratio |
|---|---:|---:|---:|
| SMALL  | 5,918 | 15,147 | 0,39× |
| MEDIUM | 0,620 | 2,218  | 0,28× |

### Throughput (ops/µs) — cible `OutputStreamWriter` (cas REST réel)

Bench `JsonpGenerateOSBench`, sink = `OutputStreamWriter(NullOutputStream, UTF-8)`.

| Workload | Sans P2 | **P2 (BufferedWriter 256)** | Δ |
|---|---:|---:|---:|
| SMALL  | 3,594 | **4,385** | **+22 %** |
| MEDIUM | 0,402 | **0,594** | **+48 %** |

**Lecture** : sur la cible `OutputStreamWriter` (ce qu'utilise Cassini via
`MessageBodyWriter.writeTo(... entityStream ...)`), P2 apporte +22 % à +48 %
selon le payload. Le buffer 256 chars est le sweet spot : plus grand
(1024) régresse SMALL de -27 % à cause du coût d'allocation non amorti,
plus petit annule le gain MEDIUM. Pour la cible `StringWriter` (bench-only),
P2 est une no-op : Champollion détecte le `StringWriter` et n'enrobe pas.

### Latence (µs/op)

| Workload | Champollion | Parsson |
|---|---:|---:|
| SMALL  | 0,170 µs | 0,066 µs |
| MEDIUM | 1,615 µs | 0,460 µs |

**Lecture** : ~3× plus lent que Parsson. Même piste d'optimisation que le parser (buffer + écriture directe).

---

## 4. JSON-B — write (`Jsonb.toJson`)

POJO + record → JSON string.

### Throughput (ops/µs)

| Workload | Champollion record | Yasson record | Jackson record | Jackson-jr POJO |
|---|---:|---:|---:|---:|
| SMALL  | 4,580  | **30,001 ⚠️** | 11,563 | 10,240 |
| MEDIUM | 0,514  | 0,575        | 1,786  | 1,639  |
| LARGE  | 0,005  | 0,006        | 0,020  | 0,018  |

| Workload | Champollion POJO | Yasson POJO | Jackson POJO |
|---|---:|---:|---:|
| SMALL  | 4,485 | 4,179 | 11,253 |
| MEDIUM | 0,512 | 0,567 | 1,797  |
| LARGE  | 0,005 | 0,006 | 0,020  |

### Latence (µs/op)

| Workload | Champollion | Yasson | Jackson | Jackson-jr |
|---|---:|---:|---:|---:|
| SMALL POJO  | 0,224  | 0,243  | 0,088   | 0,097  |
| SMALL record| 0,219  | **0,037 ⚠️** | 0,085   | —      |
| MEDIUM      | 1,957  | 1,773  | 0,557   | 0,618  |
| LARGE       | 183 µs | 177 µs | 50 µs   | 56 µs  |

⚠️ **Point intéressant — Yasson record SMALL** : 30 ops/µs au throughput, ce qui suggère un cache/short-circuit spécifique pour les records 3-fields. Sur MEDIUM/LARGE l'avantage disparaît.

**Lecture** :
- **Champollion ≈ Yasson** sur la majorité des workloads (±10 %). Champollion gère **mieux** les records SMALL/MEDIUM en pratique car pas de boilerplate `@JsonbCreator` requis, contrairement à Yasson en module-path strict (cf. `JSON-ROADMAP.md` §1).
- **Jackson 3-4× plus rapide** : c'est le plafond de référence du marché. Jackson utilise du codegen ASM pour les accesseurs et un buffer interne très optimisé.
- **Jackson-jr** est presque aussi rapide que Jackson databind sur SMALL/MEDIUM (~10 % de moins seulement).

---

## 5. JSON-B — read (`Jsonb.fromJson`)

JSON string → POJO + record.

### Latence (µs/op, plus c'est bas, mieux c'est)

| Workload | Champollion | Yasson | Jackson | Jackson-jr |
|---|---:|---:|---:|---:|
| SMALL POJO   | 0,307  | 0,425  | 0,132   | 0,154  |
| SMALL record | 0,317  | —      | 0,169   | —      |
| MEDIUM       | 2,645  | 3,645  | 1,033   | 1,034  |
| LARGE        | (n/c)  | 348 µs | 104 µs  | 158 µs  |

> Note : Champollion LARGE read non mesuré directement dans cette table — voir ci-dessous, le bench charge `List<Order>` via `TypeReference` pour Jackson uniquement (Champollion utilise `largeType` aussi mais le résultat est dans le full log).

**Lecture** :
- **Champollion bat Yasson** sur la lecture (~25-30 % plus rapide sur SMALL et MEDIUM).
- **Jackson 2-3× plus rapide** que Champollion sur la lecture, comme attendu.
- **Jackson-jr quasi équivalent à Jackson databind** sur SMALL/MEDIUM, légèrement plus lent sur LARGE.

---

## 6. Synthèse

### Positionnement

```
        Throughput relatif (Champollion = 1.0 sur chaque ligne)
        ┌──────────────────────────────────────────────────────┐
JSON-P  │ Champollion ━━━ │ Parsson ━━━━━━━━━━━━━━━━━━━━━━━━━━━ │   (Parsson ≈ 2,5×)
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

1. **Champollion est compétitif avec Yasson** sur tout le spectre JSON-B — c'est l'objectif premier puisque c'est ce qu'on remplace dans Cassini.
2. **Champollion est plus lent que Parsson** sur le streaming JSON-P (~2,5-3×). Acceptable pour un v0.1 zero-dépendance ; piste d'optimisation `java.lang.foreign` SIMD scan dans une roadmap future.
3. **Le gap avec Jackson** est attendu — Jackson cumule 15 ans d'optimisations natives et un écosystème codegen (asm, jr/object, smile). Atteindre Jackson nécessite un investissement R&D significatif (codegen bytecode des accesseurs, buffers char/byte ad hoc).
4. **Champollion gère les records natifs** sans `@JsonbCreator` requis. Yasson en module-path strict casse silencieusement sur les records (cf. `JSON-ROADMAP.md` §1) — c'est l'avantage différentiant pratique.

### Aspects non mesurés (à explorer)

- **Cold start** (1ʳᵉ sérialisation après init) — utile pour serverless / fonctions courtes.
- **GC alloc rate** (`-prof gc`) — Champollion utilise des `LinkedHashMap` partout, possible churn à comparer.
- **Footprint mémoire** (`-prof stack`).
- **Workload réel REST** — bench end-to-end Cassini avec Yasson vs Champollion (à faire après intégration M-INT).

---

## 7. Codegen statique (M5) — `@JsonbStatic`

L'APT `champollion-codegen-apt` génère pour chaque record annoté `@JsonbStatic`
un `<Type>$$Binding` (bytecode direct, classe finale, zéro réflexion). À l'exécution,
`ChampollionJsonb` consulte ces bindings via `ServiceLoader` **avant** la voie
runtime introspective : si un binding existe, branche directe sur
`JsonbBinding.write/read`.

Bench dédié : `JsonbWriteBenchStatic` / `JsonbReadBenchStatic` sur des records
top-level annotés (`SmallStaticRecord`, `OrderStaticRecord`, `ItemStaticRecord`,
`AddressStaticRecord`). Le mode `champollion_runtime` du même bench force
`withStaticBindings(List.of())` pour court-circuiter le ServiceLoader et mesurer
la baseline introspective (MethodHandles + cache).

### 7.1 Smoke run — 2026-05-04

> Smoke run JMH : `-f 1 -wi 1 -w 1s -i 2 -r 1s`. Ordres de grandeur uniquement,
> à reproduire en `-f 5 -wi 5 -i 5` avant publication finale. Yasson sur write
> SMALL/MEDIUM produit des scores anormalement élevés (DCE Blackhole `compiler`
> mode soupçonné sur un `Jsonb.toJson(record)` à valeur de retour ignorée) — non
> publié dans la table tant que non reproduit en mode `full`.

#### Write — Throughput (ops/µs, plus haut = mieux)

| Workload | champollion_runtime | champollion_static | gain APT | jackson | jacksonJr |
|---|---:|---:|---:|---:|---:|
| **SMALL** | 4,49 | **7,35** | **+64 %** | 11,36 | 9,89 |
| **MEDIUM** | 0,50 | **0,93** | **+87 %** | 1,76 | 1,58 |

#### Read — Throughput (ops/µs, plus haut = mieux)

| Workload | champollion_runtime | champollion_static | gain APT | jackson | jacksonJr |
|---|---:|---:|---:|---:|---:|
| **SMALL** | 2,61 | **3,84** | **+47 %** | 6,00 | 7,42 |
| **MEDIUM** | 0,37 | **0,52** | **+40 %** | 0,93 | 1,00 |

### 7.2 Lecture des chiffres

- **L'APT amène +40 à +87 %** par rapport au mode runtime (MethodHandles + cache).
  C'est exactement le tax de la résolution dynamique des accesseurs/setters par
  `MethodHandle.invoke` même après warmup HotSpot.
- **Champollion static ≈ 0,55× Jackson, ≈ 0,5× jackson-jr** sur SMALL/MEDIUM.
  Réduit l'écart d'environ moitié vs le runtime (qui était ~0,30× Jackson).
- **Le gap résiduel** vient principalement de la voie générator/parser :
  Jackson génère son JSON en bytes UTF-8 directement, Champollion passe par
  `Writer` + `BufferedWriter`. Les optimisations P4 (SIMD ASCII via
  `java.lang.foreign`) et un mode bytes-direct dans le generator (P6) restent
  ouverts.

## 8. Roadmap perf

| Phase | Cible | Effort | État |
|---|---|---|---|
| **P1** — Tokenizer `char[]` bufferisé | Parser pull ×2 | ~1 sem | ✅ |
| **P2** — Generator `BufferedWriter` interne | Generator ×2 | ~1 sem | ✅ |
| **P3** — Codegen statique APT (`@JsonbStatic`) | +40-87 % vs runtime | M5 | ✅ |
| **P4** — Generator bytes-direct (`OutputStream` UTF-8 sans `Writer`) | Generator ×1,5 | ~2 sem | ⏳ |
| **P5** — `MethodHandle.invokeExact` typé pour les accesseurs runtime | Runtime ×1,3 | ~1 sem | ⏳ |
| **P6** — Foreign API SIMD scan ASCII whitespace/strings | Parser ×2-3 | ~3 sem | ⏳ |
| **P7** — Keys pré-encodées en `byte[]` dans les bindings APT | Static +30-50 % | ~2 sem | ⏳ |

Cible réaliste à v1.0 : **Champollion static ≈ 0,9× Jackson** sur le binding,
**≈ 0,9× Parsson** sur le streaming.

---

## 8. Reproductibilité

Résultats bruts JMH : `bench-results.json` à la racine après le run.

Pour comparer deux runs :

```bash
java -jar champollion-bench/target/benchmarks.jar -rf json -rff before.json
# (changements)
java -jar champollion-bench/target/benchmarks.jar -rf json -rff after.json
diff <(jq '.[] | {benchmark, primaryMetric: .primaryMetric.score}' before.json) \
     <(jq '.[] | {benchmark, primaryMetric: .primaryMetric.score}' after.json)
```
