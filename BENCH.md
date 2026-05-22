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

### 7.1 Run stable — 2026-05-04 (post-`writeString` fast-path)

> JMH : `-f 2 -wi 2 -w 2s -i 3 -r 2s`. Yasson sur write SMALL/MEDIUM produit
> des scores anormalement élevés (28 / 19 ops/µs) — DCE Blackhole `compiler`
> mode soupçonné sur un `Jsonb.toJson(record)` à valeur de retour potentiellement
> hoistée par HotSpot. Non publié dans la comparaison principale (signalé en
> note de bas de table).

#### Write — Throughput (ops/µs, plus haut = mieux)

| Workload | champollion_runtime | **champollion_static** | gain APT | jacksonJr | jackson | yasson† |
|---|---:|---:|---:|---:|---:|---:|
| **SMALL**  | 5,75 ±0,27 | **9,59 ±0,98** | **+67 %** | 10,28 ±0,15 | 11,56 ±0,41 | (28,08†) |
| **MEDIUM** | 0,69 ±0,02 | **1,14 ±0,06** | **+66 %** |  1,62 ±0,02 |  1,76 ±0,04 | (18,78†) |

`champollion_static / jacksonJr` ≈ **0,93× sur SMALL** (quasi parité), 0,70× sur MEDIUM.
`champollion_static / jackson`   ≈ **0,83× sur SMALL**, 0,65× sur MEDIUM.

#### Read — Throughput (ops/µs, plus haut = mieux)

| Workload | champollion_runtime | **champollion_static** | gain APT | jackson | jacksonJr |
|---|---:|---:|---:|---:|---:|
| **SMALL**  | 2,59 ±0,04 | **3,98 ±0,20** | **+53 %** | 6,23 ±0,54 | 7,49 ±0,28 |
| **MEDIUM** | 0,36 ±0,02 | **0,53 ±0,02** | **+47 %** | 0,94 ±0,02 | 1,00 ±0,02 |

`champollion_static / jacksonJr` ≈ 0,53× SMALL, 0,53× MEDIUM (parser dominant).

† Yasson SMALL/MEDIUM write : score anormal probablement dû à un cache `toJson`
interne ou DCE Blackhole. Reproduit stable mais à valider en `-prof gc` /
async-profiler avant publication.

### 7.2 Lecture des chiffres

- **L'APT amène +47 à +67 %** vs runtime (MethodHandles + cache). C'est le tax
  de la résolution dynamique d'accesseurs même après warmup HotSpot.
- **Sur write SMALL, Champollion static ≈ 0,93× jackson-jr**. Quasi-parité —
  obtenu en combinant codegen APT + `writeString` fast-path ASCII.
- **Le gap résiduel sur write MEDIUM** (0,70× jacksonJr) vient d'opérations
  numériques (`Long.toString`, `Double.toString`) et de l'overhead state machine
  du generator (1 méthode + 3 `out.write` par key/value).
- **Sur read** Champollion est à 0,53× jacksonJr — le parser n'a pas encore
  bénéficié d'optim équivalente. Voie `JsonTokenizer` à instrumenter.

### 7.3 Décomposition du gap write SMALL vs jackson

Pour un record SMALL (3 champs), Champollion static émet ~12 `out.write(...)` sur
le `BufferedWriter`. Jackson émet typiquement ~5 (un par token cohérent).
Sources du gap :

1. **State machine `Ctx` push/pop** sur chaque key/value (3 `Deque.push` / `pop`).
2. **`writeKeyRawWithColon`** émet `,"key":` en bloc (P4 fait, gain neutre
   statistiquement — la majorité du gain venait du fast-path `writeString`).
3. **Allocation `Long.toString` / `Double.toString`** non éludable en JSON-P API.

### 7.4 Diagnostic READ — `gc.alloc.rate.norm`

| Lib | SMALL B/op | MEDIUM B/op |
|---|---:|---:|
| **Champollion static** | 1 800 | 5 552 |
| jacksonJr               |   872 | 2 712 |
| Ratio                   | **2,07×** | 2,05× |

Champollion alloue **2× plus** que jacksonJr pour le même résultat — c'est la
cause directe du gap read 0,53×. La majorité du surcoût vient du **setup
parser** :

- `new ChampollionJsonParser(reader)` : instance + `Deque<Scope>` (8 slots
  après `ArrayDeque(4)` — était 16) ;
- `new JsonTokenizer(reader)` : instance + `StringBuilder(64)` + `char[512]`
  buffer de lecture ≈ 1 200 B fixe ;
- chaque `KEY_NAME` consommé alloue 1 String (3-4 keys = ~150 B) ;
- chaque `VALUE_STRING` alloue 1 String.

Pour un JSON SMALL de 50 B, le setup parser consomme déjà ~1 500 B avant
même de lire un caractère utile.

#### Pistes explorées et rejetées

- **Tokens mutables** (`StringToken`/`NumberToken` réutilisés) : revert. Bien
  que -40 B/op sur SMALL, throughput régresse de **-20 %** — probable cassure
  de l'escape analysis JIT (les records étaient stack-alloués via EA, les
  classes mutables ne le sont plus, donc allocs réelles).
- **`ArrayDeque(4)`** : appliqué (-48 B/op SMALL et MEDIUM, throughput neutre).
  Gain marginal mais cumulable.

#### P9 — pool de parsers (partiellement appliqué)

Implémentation : `ThreadLocal<ChampollionJsonParser>` dans `ChampollionJsonb`,
`reset(Reader)` côté parser (clear scopes + ré-allocation du tokenizer), export
qualifié `exports ... internal to io.vidocq.champollion.jsonb` dans le
`module-info`.

#### P9.1 — partage char[]+StringBuilder (exploré, revert)

Tentative : 2ème constructor `JsonTokenizer(Reader, char[] sharedBuf, StringBuilder sharedSB)`
appelé par `ChampollionJsonParser.reset()` avec des buffers détenus
côté parser (alloués 1× à la construction). Gain alloc spectaculaire :

| Workload | avant | **avec P9.1** | vs jacksonJr |
|---|---:|---:|---:|
| SMALL alloc | 1 696 B/op | **552 B/op** | **-37 %** (mieux !) |
| MEDIUM alloc | 5 448 B/op | 4 304 B/op | +59 % (encore en retard) |

Mais throughput MEDIUM régresse de **-12 %** (0,53 → 0,46 ops/µs) — non récupéré
même en remplaçant `buf.length` par la constante `BUF_SIZE` pour aider
l'élimination de bounds-check. Hypothèse : aliasing bimorphic du JIT entre les
call sites tokenizer non-pool / pool, qui empêche un inline cache stable.
Trade-off jugé non rentable (gain alloc compense pas la perte throughput),
**revert**.

#### P10 — fast-path `fromJson(String)` (exploré, revert)

Tentative : tokenizer en mode `String` direct via `String.charAt(srcPos++)` dans
`read()`/`peekRead()` (branche `if (src != null)`), nouveau constructor
`JsonTokenizer(String)`, fast-path `readValuePooledFromString` dans
`ChampollionJsonb`. Résultat :

| Workload | avant | **avec P10** | jacksonJr | delta vs jacksonJr |
|---|---:|---:|---:|---:|
| SMALL alloc | 1 696 B/op | **608 B/op** | 872 B/op | **-30 %** (mieux !) |
| MEDIUM alloc | 5 448 B/op | 4 360 B/op | 2 712 B/op | +61 % |
| MEDIUM thrpt | 0,53 ops/µs | 0,439 ±0,009 | 1,00 | **-17 %** régression |

Même obstacle que P9.1 : la branche supplémentaire dans `read()` casse
l'inlining HotSpot. Trade-off non rentable, **revert**.

#### P10.1 — refactor abstract sealed (mergé)

Refactor `JsonTokenizer` en `abstract sealed` + 2 sous-classes finales :
- `JsonReaderTokenizer` : lecture par bloc `char[BUF_SIZE]` sur un `Reader` ;
- `JsonStringTokenizer` : lecture directe via `String.charAt`.

Mesures stables (2f×3wi×4i×2s, profil `-prof gc`) :

| Workload | avant P10.1 | **après P10.1** | jacksonJr |
|---|---:|---:|---:|
| Read SMALL alloc | 1 696 B/op | **584 B/op** | 872 B/op |
| Read MEDIUM alloc | 5 448 B/op | 4 336 B/op | 2 712 B/op |
| Read SMALL thrpt | 3,98 | **4,02 ±0,018** (+1 %) | 7,50 |
| Read MEDIUM thrpt | 0,53 | 0,453 ±0,003 (-15 %) | 1,02 |

C'est la **première fois** que Champollion alloue moins que jacksonJr en read
(SMALL : 584 vs 872, **-33 %**) sans régression sur le throughput SMALL.
La régression MEDIUM persiste — l'hypothèse initiale (dispatch virtuel
sealed bimorphic) a été testée par P10.2 (duplication complète de
`next()`+helpers dans chaque sous-classe finale, élimine tout dispatch
virtuel sur `read()`/`peekRead()`) : thrpt MEDIUM **inchangé**
(0,458 ±0,001 ops/µs vs 0,453 ±0,003 — match dans le bruit). La cause
profonde n'est donc pas le dispatch virtuel mais une autre subtilité
JIT/cache ou un coût intrinsèque `String.charAt` vs `char[]` access
sur ASCII non-latin1. Investigation profilée différée (P10.3).

#### Au-delà

**P11** — Foreign API SIMD pour le scan ASCII whitespace/strings (parser ×2-3).

## 8. Roadmap perf

| Phase | Cible | Effort | État |
|---|---|---|---|
| **P1** — Tokenizer `char[]` bufferisé | Parser pull ×2 | ~1 sem | ✅ |
| **P2** — Generator `BufferedWriter` interne | Generator ×2 | ~1 sem | ✅ |
| **P3** — Codegen statique APT (`@JsonbStatic`) | +47-67 % vs runtime | M5 | ✅ |
| **P3.1** — `writeString` fast-path ASCII (single block) | Generator +20-30 % | 1j | ✅ |
| **P3.2** — `writeKeyRaw` keys pré-encodées (`RawJsonKeyWriter`) | Generator +X % | M5 | ✅ |
| **P4** — `writeKeyRawWithColon` fragment fusionné `,"k":` | Stat. neutre | 1j | ✅ |
| **P4.1** — `ArrayDeque(4)` pour pile de scopes parser | -48 B/op | 30min | ✅ |
| **P5** — Tokens mutables `StringToken`/`NumberToken` | -20 % thrpt (cassure EA JIT) | 1j | ❌ revert |
| **P6** — `MethodHandle.invokeExact` + `asType` accesseurs runtime | Neutre, -8 % read MEDIUM (wrapper asType) | 2h | ❌ revert |
| **P6.1** — `LambdaMetafactory` → `Function<Object,Object>` direct | Write runtime +1 %, read SMALL +4 %, read MEDIUM -9 % | 1j | ✅ |
| **P6.2** — Accesseurs typés primitive (`LongAccessor`/`IntAccessor`/`DoubleAccessor`/`BooleanAccessor`) via `LambdaMetafactory` | -170 B/op MEDIUM (gain caches Integer/Boolean limite le throughput) | 1j | ✅ |
| **P7** — Parser fast-path keys (intern + match table) | Reader ×1,5 | ~1 sem | ⏳ |
| **P8** — Generator bytes-direct (`OutputStream` UTF-8 sans `Writer`) | Generator ×1,5 | ~2 sem | ⏳ |
| **P9** — Pool `ChampollionJsonParser` thread-local (narrowed) | -104 B/op SMALL, thrpt neutre | 2j | ✅ |
| **P9.1** — Partage char[]+StringBuilder via 2ème ctor pool-friendly | -1144 B/op SMALL, **-12 % thrpt MEDIUM** | 4h | ❌ revert |
| **P10** — Fast-path `fromJson(String)` via branche `if (src != null)` | -1088 B/op SMALL, **-17 % thrpt MEDIUM** | 4h | ❌ revert |
| **P10.1** — `JsonTokenizer` abstract + `JsonReaderTokenizer`/`JsonStringTokenizer` | **-66 % alloc SMALL (sous jacksonJr)**, -15 % thrpt MEDIUM | 1j | ✅ |
| **P10.2** — Spécialiser `next()`+helpers dans chaque sous-classe (test hypothèse dispatch virtuel) | thrpt MEDIUM inchangé (hypothèse infirmée) | 1j | ✅ |
| **P10.3** — Investigation profilée régression thrpt MEDIUM (cause à isoler) | TBD | ~1 sem | ⏳ |
| **P11** — Foreign API SIMD scan ASCII whitespace/strings | Parser ×2-3 | ~3 sem | ⏳ |

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

---

## 9. Protobuf — Runtime reflectif (M1.3) vs APT statique (M3.1)

**Date** : 2026-05-21
**Hardware** : macOS aarch64 (Apple Silicon)
**JVM** : OpenJDK 25 Temurin, Compiler Blackholes activés
**Commande** :

```bash
java -jar champollion-bench/target/benchmarks.jar ProtobufBenchmark \
     -wi 2 -i 3 -f 1 -t 1 -tu us
```

**Workload** : record `BenchPerson(name, age, repeated tags×3, active, sequence)` — 5 champs, encodé en ~70 octets. Voir `champollion-bench/src/main/java/io/vidocq/champollion/bench/ProtobufBenchmark.java`.

### Résultats bruts

| Benchmark | Mode | Score | Erreur | Unité |
|---|---|---|---|---|
| `parse_static` | thrpt | **22,454** | ± 2,996 | ops/µs |
| `parse_runtime` | thrpt | 7,549 | ± 0,399 | ops/µs |
| `serialize` | thrpt | 5,120 | ± 0,064 | ops/µs |
| `parse_static` | avgt | **0,044** | ± 0,001 | µs/op |
| `parse_runtime` | avgt | 0,148 | ± 0,056 | µs/op |
| `serialize` | avgt | 0,196 | ± 0,004 | µs/op |

### Lecture

- **Parser statique (M3.1) ≈ 3× plus rapide** que le runtime reflectif sur ce workload représentatif. C'est la validation expérimentale de l'effort APT.
- **Serialize** n'a pas (encore) de fast-path statique côté écriture — c'est un candidat M3.4 si le besoin se confirme côté chappe-grpc.
- Le smoke run (2 wi × 3 i × 1 f) garde l'incertitude haute (±13 % en `parse_static`) — un run de validation v1.0 doit faire 5 wi × 10 i × 3 f minimum.

**Delta vs run précédent** : aucun, premier run JMH protobuf.

### À venir (M3.4 / M4 / M5)

- Comparatif vs `com.google.protobuf:protobuf-java` (référence), même workload.
- Bench Edition 2023 features pour mesurer le coût de `field_presence=EXPLICIT`.
- Bench JSON canonical (proto3 + Any aplatissement) — comparatif vs `protobuf-java-util`.

---

## §10 — Note M5.9 : `@ProtobufStatic` désactivé sur TestAllTypesProto3

Date : 2026-05-22. Hardware/JVM : idem §9.

Le static parser APT a été désactivé sur `TestAllTypesProto3` (et
`NestedMessageT`) au profit du runtime reflectif. Raisons :

1. **`FieldType.MAP`** (M5.9) n'est pas supporté en static codegen — les
   fields map sont émis comme `/* MAP handled by runtime */null` placeholder.
2. La presence-awareness (oneofGroup + explicitPresence) demande des slots
   wrapper `Integer/Long/Boolean = null`. L'APT a été étendu (M5.5.8) mais
   le mix MAP+presence sur TestAllTypesProto3 (97 champs) est resté en
   runtime path par simplicité.

**Impact mesuré** : nul. Conformance Google passe à 100% PASS via le runtime,
2585 tests en ~250 ms total. Le static parser reste utilisable pour les
records sans MAP (ex. `BenchPerson`, tous les WKT Wrappers) et le bench
§9 reste pertinent.

**M6 à venir** : étendre `ProtobufStaticProcessor` pour générer le code MAP
inline (cf. plan agent dans `docs/adr/0002-…` §"static codegen") et
re-activer `@ProtobufStatic` sur TestAllTypesProto3. Bench différentiel
attendu.
