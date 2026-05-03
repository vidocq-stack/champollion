# Champollion — État de la situation

> Synthèse de l'avancement au 2026-05-01. Pour le plan détaillé phase par phase, voir `ROADMAP.md`.
> Pour les conventions et contraintes, voir `CLAUDE.md`.

## Méta

- **Branche** : `main`
- **Build** : `mvn clean install -DskipTests` ✅ sur 8 modules (parent + 7 sous-modules)
- **Tests** : **236/236** ✅ (`mvn test` sur le reactor)
- **Discipline** : TDD strict tenu sur tous les commits, citation RFC dans les `@DisplayName`

## Modules

| Module | État | Notes |
|---|---|---|
| `champollion-api` | ✅ | Re-expose `jakarta.json` + `jakarta.json.bind`. Pas encore de SPI propre exportée (à ajouter quand on aura un point d'extension à figer). |
| `champollion-jsonp` | ✅ **complet pour la spec publique** | 161 tests. Streaming + object model + builders + Reader/Writer + Pointer + Patch + MergePatch + ServiceLoader. |
| `champollion-jsonb` | ✅ **runtime + lookup-first static** | 52 tests. toJson + fromJson runtime opérationnels (primitives, java.time, UUID, enum, records, POJOs, containers). SPI publique `JsonbBinding<T>` + `@JsonbStatic` exposée, ServiceLoader, lookup-first, fallback runtime. |
| `champollion-codegen-apt` | ✅ **MVP fonctionnel** | 18 tests. APT `JsonbStaticProcessor` génère un `JsonbBinding<T>` par record annoté `@JsonbStatic` + ServiceLoader file. Couvre primitives + String + enums + List<E> + Optional<E> + Map<String,V> + arrays primitifs + String[] + nested records `@JsonbStatic`. Differential testing automatisé static vs runtime. |
| `champollion-codegen-maven-plugin` | 🟡 squelette (`packaging=jar`) | `GenerateMojo` minimal. Le packaging `maven-plugin` reviendra en M5 avec un descriptor compatible Java 25. |
| `champollion-bench` | 🟡 vide | POM JMH prêt, aucun benchmark écrit. |
| `champollion-examples` | 🟡 vide | POM prêt, aucun exemple écrit. |
| `champollion-tck` | ❌ pas créé | Module hors reactor à créer en M6 (POM Model 4.0.0, scripts shell). |

## Avancement par phase (cf. ROADMAP.md)

| Phase | Périmètre | État |
|---|---|---|
| M0 | Bootstrap multi-module, JPMS, ServiceLoader | ✅ |
| M1.1 | Tokenizer JSON-P RFC 8259 | ✅ 19 tests |
| M1.2 | JsonParser pull (events) | ✅ 21 tests |
| M1.3 | JsonGenerator push | ✅ 27 tests |
| M1.4 | JsonProvider + ServiceLoader | ✅ 10 tests |
| M2.1 | Object model `JsonValue/JsonObject/JsonArray/...` | ✅ 20 tests |
| M2.2 | Builders + Reader/Writer | ✅ 17 tests |
| M3.1 | JsonPointer RFC 6901 | ✅ 18 tests |
| M3.2 | JsonPatch RFC 6902 (12 ops, A.1..A.10 + copy + dash) | ✅ 16 tests |
| M3.3 | JsonMergePatch RFC 7396 + diff | ✅ 13 tests |
| **M4.1** | Jsonb.toJson runtime — primitives + records | ✅ 14 tests |
| **M4.2** | Jsonb.fromJson runtime symétrique | ✅ 16 tests |
| **M4.3-write** | Containers (List/Set/Map/Array/Optional) | ✅ 16 tests |
| **M3.4** | `Json.createDiff` (JsonPatch diff) | ❌ reporté |
| **M4.4** | Customization JSON-B (annotations) | ❌ pas commencé |
| **M4.5** | Polymorphisme `@JsonbTypeInfo`/`@JsonbSubtype` | ❌ pas commencé |
| **M5.1** | SPI `JsonbBinding` + lookup-first | ✅ 6 tests |
| **M5.2** | `@JsonbStatic` + APT `JsonbStaticProcessor` | ✅ 4 tests |
| **M5.3** | APT containers (List/Optional/Arrays) | ✅ 4 tests |
| **M5.4** | Differential testing static vs runtime | ✅ 3 tests |
| **M5.5** | APT — Map<String,X> + nested records | ✅ 4 tests |
| **M5.6** | APT — enums comme leaf type | ✅ 3 tests |
| **M5.7** | APT bytecode direct (Class File API JDK 25) | ✅ records primitives + String + enums |
| **M5.8** | Maven plugin `champollion-codegen-maven-plugin` | ❌ packaging=jar squelette |
| **M5.9** | APT bytecode étendu : arrays primitifs + `String[]` + `Optional<X>` | ✅ |
| **M5.10** | APT bytecode List<X>, Map<String,V>, nested @JsonbStatic | ✅ — **fast path bytecode 100 %** |
| **M5.11** | Pre-encoded property names + `writeKeyRaw` fast path | ✅ |
| **M5.12** | Validation AOT par inspection bytecode (zero reflection) | ✅ — 5 tests |
| **M6** | TCK officiels JSON-P 2.1 + JSON-B 3.0 | ❌ pas commencé |
| **M7** | Intégration Cassini (swap Yasson → Champollion) | ❌ pas commencé |

## Reste à faire

### Court terme — finir M3 et M4

- **M3.4** `Json.createDiff(JsonStructure, JsonStructure)` retournant un `JsonPatch` (RFC 6902 diff). Aujourd'hui marqué `UnsupportedOperationException` dans `ChampollionJsonProvider`. Algorithme non-trivial : il faut comparer deux structures et émettre la séquence minimale d'opérations `add/remove/replace/move/copy/test`.
- **M4.4 — Customization JSON-B 3.0** :
  - `@JsonbProperty(name)` — renommage de propriété
  - `@JsonbTransient` — exclusion d'une propriété
  - `@JsonbDateFormat`, `@JsonbNumberFormat` — formats spécifiques
  - `@JsonbAdapter` / `JsonbAdapter<T,R>` — adaptateurs personnalisés
  - `@JsonbCreator` — factory method explicite (au-delà du ctor canonique des records)
  - `@JsonbVisibility` — modifier la stratégie de propriétés
  - `JsonbConfig` properties : `JSONB_NULL_VALUES`, `JSONB_FORMATTING`, `JSONB_LOCALE`, `JSONB_DATE_FORMAT`, naming strategy
- **M4.5 — Polymorphisme** : `@JsonbTypeInfo` + `@JsonbSubtype`, nouveauté JSON-B 3.0. Aujourd'hui non supporté.
- **POJO setters** : la lecture POJO actuelle ne gère que les champs publics. Étendre aux setters conventionnels (`setX`/`getX`) pour les bean classiques.

### Moyen terme — M5 (codegen statique) — partiellement livré

**Livré :**
- ✅ SPI `JsonbBinding<T>` exposée (`champollion-jsonb.spi`) + `PrimedJsonParser` utility
- ✅ Annotation `@JsonbStatic` (`champollion-jsonb.spi`, `RetentionPolicy.CLASS`)
- ✅ **APT `JsonbStaticProcessor` 100 % bytecode** (Class File API JDK 25, `Filer.createClassFile`) sur le subset complet :
  - primitives (8 types), String, enums
  - arrays primitifs (`int[]`, `long[]`, `double[]`, `boolean[]`), `String[]`
  - `Optional<X>`, `List<X>`, `Map<String,V>` où X/V ∈ scalaire / enum / nested record `@JsonbStatic`
  - nested records `@JsonbStatic` comme composants directs
  - Le slow path source reste implémenté comme fallback mais n'est plus déclenché par les tests actuels.
- ✅ `ChampollionJsonb` lookup-first sur les bindings statiques, fallback runtime introspectif
- ✅ Differential testing automatisé runtime vs static
- ✅ Couverture types : primitives, String, enums, List<E>, Optional<E>, Map<String,V>, arrays primitifs, String[], nested records `@JsonbStatic` (référence directe par `new <X>$$Binding()`)

**À faire :**
- **M5.8** Activation `champollion-codegen-maven-plugin` (packaging=maven-plugin) quand `maven-plugin-plugin` ≥ ASM lisant Java 25 sera publié. Aujourd'hui jar squelette.
- **M5.13 (optionnel)** Validation `native-image` end-to-end avec GraalVM installé. La validation par inspection bytecode (M5.12) suffit déjà pour le contrat ; un test `native-image` ne ferait que confirmer pratiquement.

### Moyen terme — Tests d'envergure

- **Corpus JSONTestSuite** (nst/JSONTestSuite) intégré dans `champollion-jsonp/src/test/resources/` pour un harness RFC 8259 indépendant du TCK.
- **JMH bench** dans `champollion-bench` : comparatif throughput/latence/allocs vs Parsson, Yasson, Jackson.
- **Exemples** dans `champollion-examples` : démonstration runtime vs codegen statique, intégration avec `Json.*` et `JsonbBuilder`, exemples d'adaptateurs custom.

### Long terme — M6 (TCK)

- Créer `champollion-tck` **hors reactor** (POM Model 4.0.0 standalone, contrainte ShrinkWrap héritée de `cassini-tck`/`foy-tck`).
- Installer en M2 local les TCK officiels :
  - `jakarta.json:jakarta-json-tck:2.1.x`
  - `jakarta.json.bind:jakarta-json-bind-tck:3.0.x`
- Scripts `run-official-tck-jsonp-2.1.sh` et `run-official-tck-jsonb-3.0.sh` (smoke / all / `-Dtest=`).
- `TCK.md` documentant les éventuels challenges (tests désactivés avec citation spec).
- **Mode statique** : exécuter le TCK aussi avec les fixtures recompilées via APT pour valider la cohérence du codegen.
- **Contrat dur** : 100 % PASS sur les deux TCK avant merge structurel.

### Long terme — M7 (intégration écosystème Vidocq)

- Adapter `cassini-champollion` côté Cassini : `MessageBodyReader/Writer<JsonValue>` et `<Object>` via JSON-B Champollion. Remplace Parsson + Yasson dans Cassini.
- Adapter `chappe-champollion` (optionnel) : `BodyHandler` JSON pour Chappe non-JAX-RS.
- `docs/integration-cassini.md` et `docs/integration-chappe.md`.
- Bench end-to-end Cassini + Champollion vs Cassini + Yasson.

## Décisions ouvertes (re-confirmées)

- Mode statique dans **2 artifacts séparés** : `champollion-codegen-apt` (JDK pur) + `champollion-codegen-maven-plugin` (orchestrateur). ✅ acté.
- **Runtime reflectif gardé en fallback** quand aucune factory statique n'est trouvée. ✅ acté.
- `java.lang.foreign` pour le scanner UTF-8 : différé à post-M5.
- Polymorphisme `@JsonbTypeInfo` en mode statique : nécessite un sealed-tree connu à la compilation. À traiter en M5.

## Risques actifs

- **maven-plugin-plugin / Java 25** : ASM 9.x intégré à 3.15.1 ne lit pas major 69. Bloquant pour M5 tant qu'une 3.16+ compatible n'est pas publiée. Workaround actuel : `packaging=jar` pour le squelette.
- **JSONTestSuite cas tordus** : conformance RFC 8259 stricte n'est pas encore validée par un corpus externe — risque de régression sur des cas marginaux (ex : escape unicode surrogates). À couvrir avant M6.
- **POJO sans champs publics** : impossible aujourd'hui de lire/écrire les beans classiques avec getters/setters privés. Bloquant pour le TCK Yasson-style. À traiter en M4.4.
