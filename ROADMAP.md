# Champollion — Plan d'attaque

> Implémentation Jakarta JSON Processing 2.1 (JSON-P) + Jakarta JSON Binding 3.0 (JSON-B)
> dans le style Vidocq : zéro dépendance, JDK 25, virtual threads, JPMS strict,
> compilation statique des bindings via APT/Maven plugin.

## Principes directeurs

| Principe | Application concrète |
|---|---|
| Zéro dépendance | Pas de Parsson/Yasson/Jackson dans `champollion-jsonp`/`champollion-jsonb`. Seules les API specs Jakarta sont compilées. |
| Virtual threads | Pas de `synchronized`, pas de `ThreadLocal`. Caches `ConcurrentHashMap`/`ClassValue`. Propagation via `ScopedValue`. |
| Compilation statique (2 artifacts séparés) | `champollion-codegen-apt` = Annotation Processor JDK pur (utilisable seul, sans Maven). `champollion-codegen-maven-plugin` = Mojo qui scanne le classpath et délègue à l'APT pour les classes non-annotables. |
| Runtime reflectif en fallback | Si aucun `BindingFactoryProvider` n'est trouvé pour un type, le runtime introspectif prend le relais. Permet le bootstrap progressif et la compatibilité avec les classes tierces non recompilables. |
| JPMS strict | `module-info.java` partout, `internal.*` non exporté, SPI via `provides/uses`. |
| TDD strict | Red → Green → Refactor. Tests écrits avant le code de prod. Cf. section TDD ci-dessous. |
| TCK PASS 100 % | Contrat dur sur JSON-P 2.1 et JSON-B 3.0, en mode runtime ET en mode codegen statique. |
| Performance mesurée | JMH dès M1, comparatif systématique avec Parsson/Yasson/Jackson, baseline ratchet. |

## Méthodologie : TDD + TCK comme garde-fous parallèles

Champollion est développé en **TDD strict** (Red → Green → Refactor). Aucune ligne de production
n'est écrite avant un test qui la justifie. Au-delà du cycle TDD interne :

- **Couche 1 — tests unitaires TDD** : pilotent la conception de chaque classe.
- **Couche 2 — JSONTestSuite (RFC 8259)** : harnais de conformité scanner intégré dès M1 ; ne dépend
  pas du TCK et reste fiable pour le coverage RFC.
- **Couche 3 — TCK officiels** (`jakarta.json-tck` + `jakarta.json.bind-tck`) : contrat 100 % PASS
  avant tout merge structurel sur `jsonp`/`jsonb`. Module hors reactor (POM Model 4.0.0).
- **Couche 4 — Differential testing** : entre runtime reflectif et codegen statique, sur 100+ types
  hétérogènes, à chaque commit qui touche `jsonb` ou `codegen-apt`.

Les TCK sont exécutés en deux modes :
- **runtime** (introspection JSON-B) — valide la conformité à la spec.
- **static** (recompilation des fixtures TCK avec l'APT) — valide que le codegen produit le même
  comportement observable que le runtime.

## Phases

### M0 — Bootstrap (cette session)

- [x] `.sdkmanrc`, `.gitignore`, `.mvn/maven.config`
- [x] `pom.xml` parent (Model 4.1.0, multi-module, dependency management Jakarta)
- [x] `CLAUDE.md` (TDD + TCK inclus)
- [x] `ROADMAP.md`
- [x] Création des 7 sous-modules : `champollion-api`, `champollion-jsonp`, `champollion-jsonb`,
      `champollion-codegen-apt`, `champollion-codegen-maven-plugin`, `champollion-bench`,
      `champollion-examples` (avec `pom.xml` + `module-info.java` squelettes)
- [ ] `LICENSE` (Apache 2.0)
- [ ] `README.md`
- [ ] Validation `mvn -ntp install -DskipTests` réussit (reactor vide)

**Livrable :** `mvn -ntp install -DskipTests` réussit sur un reactor vide, JPMS résout tous les modules.

---

### M1 — JSON-P 2.1 streaming (parser pull / generator push)

**Scope spec :** §3 (Streaming API) de Jakarta JSON Processing 2.1.

| Tâche | Notes |
|---|---|
| `JsonParser` pull-based sur `Reader` et `InputStream` | UTF-8 / UTF-16 BE/LE / UTF-32 BE/LE détection BOM ; RFC 8259 strict |
| `JsonParserFactory` + `Json.createParserFactory(Map)` | `pretty-printing`, `buffer-pool-size`, etc. |
| `JsonGenerator` push-based sur `Writer` et `OutputStream` | Pretty-printing, indentation, escaping conformes RFC 8259 §7 |
| `JsonGeneratorFactory` | Identique côté config |
| Buffer pool (`JsonProvider#createBufferPool`) | `MpmcBoundedBuffer` simple, sans dépendance |
| `JsonProvider` impl + `META-INF/services/jakarta.json.spi.JsonProvider` | Point d'entrée ServiceLoader |
| Scanner caractère par caractère, branchless le plus possible | Tableau de transitions state machine RFC 8259 |
| Tests : RFC 8259 conformance suite (NSTI, JSONTestSuite) | Source ouverte, intégrée en `src/test/resources` |
| Bench JMH : parser throughput vs Parsson | Cible : ≥ Parsson sur le 90e percentile |

**Livrable :** `Json.createParser(reader).next()` et `Json.createGenerator(writer).write(...)` opérationnels,
tests RFC 8259 verts, bench publié.

---

### M2 — JSON-P 2.1 object model

**Scope spec :** §4 (Object Model) + §6 (Json class factory methods).

| Tâche | Notes |
|---|---|
| `JsonValue`, `JsonString`, `JsonNumber` (sealed) | Records immuables ; `JsonNumber` adossé à `BigDecimal` lazy |
| `JsonObject` (LinkedHashMap-backed, ordre d'insertion) | Iteration order préservé (cf. spec §4.2) |
| `JsonArray` | `List<JsonValue>` immuable |
| `JsonObjectBuilder` / `JsonArrayBuilder` | Mutables pendant la construction, `build()` rend immuable |
| `JsonReader` / `JsonWriter` (orientés value) | Adossés à parser/generator de M1 |
| `JsonString` : escape UTF-16 surrogates | Conforme RFC 8259 §7 |
| `JsonNumber` : entier vs décimal, exact match | `intValueExact`, `bigIntegerValueExact` |
| Tests TCK JSON-P 2.1 — section object-model | Doit passer |

**Livrable :** TCK JSON-P 2.1 vert sur les sections Streaming + Object Model.

---

### M3 — JSON-P 2.1 Patch / Pointer / Merge Patch

**Scope spec :** §5 (Patch RFC 6902, Pointer RFC 6901, Merge Patch RFC 7396).

| Tâche | Notes |
|---|---|
| `JsonPointer` | RFC 6901, escape `~0`/`~1`, indexation tableau |
| `JsonPatch` | RFC 6902, opérations `add`/`remove`/`replace`/`move`/`copy`/`test` |
| `JsonMergePatch` | RFC 7396 |
| `JsonPatchBuilder` / `JsonMergePatchBuilder` | API builder fluide |
| Tests TCK JSON-P 2.1 — section patch/pointer | Doit passer |

**Livrable :** TCK JSON-P 2.1 PASS à 100 % (objectif contrat).

---

### M4 — JSON-B 3.0 runtime (mode reflectif)

**Scope spec :** Jakarta JSON Binding 3.0, sections 3 (Default Mapping) à 4.7 (Custom Mapping).

| Tâche | Notes |
|---|---|
| `JsonbBuilder` SPI + `JsonbProvider` impl | ServiceLoader, point d'entrée `Jsonb.create()` |
| `JsonbConfig` | Properties standard : `JSONB_NULL_VALUES`, `JSONB_FORMATTING`, `JSONB_LOCALE`, etc. |
| `BindingPlan` par classe, cache `ClassValue<BindingPlan>` | Calculé une fois, lecture lock-free |
| `PropertyWriter` / `PropertyReader` adossés à `MethodHandles` | Pas de `setAccessible` à chaud |
| Adapters par défaut : primitives, String, Number, BigDecimal, BigInteger, dates `java.time` | `Date`/`Calendar` deprecated mais supportés (TCK) |
| `Collection`, `Map`, `Optional`, arrays, enums | Conformes spec §3.5 |
| Polymorphisme : `@JsonbTypeInfo`, `@JsonbSubtype` | Nouveau en JSON-B 3.0 |
| Customization : `@JsonbProperty`, `@JsonbTransient`, `@JsonbDateFormat`, `@JsonbNumberFormat`, `@JsonbAdapter` | Couverture complète |
| `JsonbCreator` factory | Records natifs + classes immuables |
| Tests unitaires + premiers passes TCK JSON-B 3.0 | Couverture ≥ 80 % avant M5 |

**Livrable :** `Jsonb.create().toJson(obj)` / `fromJson(...)` opérationnels en mode runtime,
records bindés sans config, TCK JSON-B 3.0 sections Default + Customization vertes.

---

### M5 — JSON-B 3.0 codegen statique (APT + Maven plugin)

**Scope :** générer à la compilation un `BindingFactory<T>` pour chaque type cible et l'enregistrer
via ServiceLoader. Le runtime reflectif (M4) reste **fallback** quand aucune factory générée n'est trouvée.

**Deux artifacts séparés** (décision actée) :

- `champollion-codegen-apt` — Annotation Processor JDK pur (`Processor` SPI). Utilisable seul via
  `javac -processorpath`, sans Maven. Cible : code Java généré, **pas de bytecode**.
- `champollion-codegen-maven-plugin` — Mojo Maven qui scanne le classpath du projet hôte et invoque
  l'APT sur les types JSON-B détectés mais non annotés `@JsonbStatic`. Pour les classes tierces.

| Tâche | Notes |
|---|---|
| `champollion-codegen-apt` : `Processor` pour `@JsonbStatic` | `javax.annotation.processing.Processor` ; ServiceLoader `META-INF/services` |
| Génération de `<FQN>$$Binding.java` (writer + reader) | Code Java pur, pas de bytecode, lisible et debuggable |
| Génération du `BindingFactoryProvider` ServiceLoader fichier-source | `META-INF/services/io.vidocq.champollion.jsonb.spi.BindingFactoryProvider` |
| `champollion-codegen-maven-plugin` Mojo `generate` | Lié à `generate-sources` ; param `<targets>`/`<excludes>` |
| Le Mojo délègue 100 % à `champollion-codegen-apt` | Pas de duplication — le plugin est un orchestrateur |
| Runtime fallback : `BindingFactoryProvider` non trouvé → introspection M4 | Logué en INFO ; flag `champollion.jsonb.warn-on-fallback=true` pour audit |
| Optimisations : escape précompilé pour les noms de propriétés (constantes UTF-8 byte arrays) | `private static final byte[] PROP_NAME = {...}` |
| **Differential testing** : binding statique vs runtime → mêmes résultats sur 100+ classes | Suite automatisée, gate avant merge |
| Bench JMH : statique vs runtime, vs Yasson | Cible : > 2× Yasson en throughput, allocs ≈ 0 |

**Livrable :** factory générée auto-découverte via ServiceLoader, zéro réflexion à `Jsonb.toJson(obj)`
quand la classe a été compilée avec l'APT ou scannée par le plugin Maven. Runtime reflectif intact
en fallback.

---

### M6 — TCK officiels (hors reactor)

| Tâche | Notes |
|---|---|
| `champollion-tck/pom.xml` Model 4.0.0 standalone | Idem `cassini-tck`/`foy-tck` |
| `run-official-tck-jsonp-2.1.sh` | Cible smoke + full + ciblé |
| `run-official-tck-jsonb-3.0.sh` | Idem |
| `TCK.md` | Documente les éventuels challenges (tests désactivés avec justification spec) |
| Score contrat : 100 % PASS sur les deux TCK | Régression bloquante |

**Livrable :** scripts shell + rapport TCK reproductible à chaque release.

---

### M7 — Intégration écosystème Vidocq

| Tâche | Notes |
|---|---|
| Adapter `cassini-champollion` (côté Cassini) : `MessageBodyReader/Writer<JsonValue>` et `<Object>` via JSON-B | Remplace Parsson/Yasson dans Cassini |
| Adapter `chappe-champollion` (optionnel) : `BodyHandler` JSON pour Chappe | Cas non-JAX-RS |
| Documentation : `docs/integration-cassini.md`, `docs/integration-chappe.md` | Diagrammes mermaid |
| Bench end-to-end : Cassini + Champollion vs Cassini + Yasson | Throughput requêtes JSON / sec |

**Livrable :** Cassini livre une release sans aucune dépendance Parsson/Yasson.

---

## Ordre de priorité — pourquoi celui-ci ?

1. **M1 → M3 (JSON-P)** d'abord parce que JSON-B en dépend strictement. Pas de raccourci.
2. **M4 (runtime JSON-B)** avant M5 (codegen) car le runtime est l'oracle de référence : on
   compare les sorties du codegen contre celles du runtime sur differential testing. Sans le runtime,
   le codegen vole sans filet.
3. **M6 (TCK)** est une activité continue dès M2/M3 sur JSON-P, dès M4 sur JSON-B, mais
   l'objectif "100 % PASS" ne devient un contrat qu'à la fin de chaque scope.
4. **M7 (intégration)** vient en dernier : on ne polluera pas Cassini avant que Champollion soit
   solide. Le swap se fera derrière une PR dédiée.

## Risques connus

| Risque | Mitigation |
|---|---|
| Parser JSON conformance RFC 8259 stricte (cas tordus type "JSONTestSuite") | Intégrer le test corpus `nst/JSONTestSuite` dès M1, suite parallèle au TCK |
| JSON-B 3.0 polymorphisme (`@JsonbTypeInfo`) — feature nouvelle, peu d'exemples | Lire la spec puis le TCK avant de coder ; étudier l'impl Yasson 3.x comme référence |
| Codegen APT et JPMS : `provides` généré dynamiquement, mais `module-info` est figé en source | Le plugin Maven génère un `module-info-extra.java` ou ajoute des `provides` via `--add-modules` ; sinon descriptor manuel + factory de factories |
| TCK officiels JSON-P/JSON-B accessibles ? | Vérifier dispo dans M2 local Eclipse Foundation ; sinon TCK communautaire |
| GraalVM AOT compatibility | Tester `native-image` sur `champollion-examples` dès M5 pour valider l'absence de réflexion |

## Décisions actées

- ✅ **Deux artifacts séparés** pour la compilation statique : `champollion-codegen-apt` (JDK pur)
  et `champollion-codegen-maven-plugin` (orchestrateur Maven). L'APT ne dépend pas de Maven.
- ✅ **Runtime reflectif gardé en fallback** : permet bootstrap progressif et compatibilité avec
  classes tierces non recompilables. Comportement loggé.
- ✅ **TDD strict** sur tous les modules de production.
- ✅ **TCK PASS 100 %** comme contrat dur, en mode runtime ET statique.

## Décisions ouvertes

- [ ] Faut-il exposer un mode "stream-as-iterator" pour JSON-P (au-delà du contrat spec) ?
- [ ] Adopter `java.lang.foreign` pour le scanner UTF-8 dès M1 ou différer en M5 ? → Différer ; baseline pure JDK d'abord.
- [ ] Polymorphisme `@JsonbTypeInfo` en mode statique : impose une enum sealed des sous-types à la compilation.
  Comment gérer l'ouverture (extensions dynamiques) ? → À traiter en M5.
