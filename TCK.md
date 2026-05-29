# Champollion TCK

Cette documentation décrit l'exécution des **TCK officiels** Jakarta JSON-P 2.1 et
Jakarta JSON-B 3.0 contre Champollion, et liste les éventuels challenges
(tests désactivés avec justification).

## Méta

- **Module** : `champollion-tck` (volontairement **hors reactor**, POM Model 4.0.0
  pour contourner ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0)
- **Scripts** : `run-official-tck-jsonp-2.1.sh` et `run-official-tck-jsonb-3.0.sh`
  à la racine du projet
- **Contrat** : 100 % PASS sur les deux TCK avant tout merge structurel sur
  `champollion-jsonp` ou `champollion-jsonb`

---

## Installation des TCK officiels

Les TCK Jakarta sont distribués par l'Eclipse Foundation sous forme de ZIP
contenant les jars + POMs (non publiés sur Maven Central public).

### Méthode automatique (recommandée)

Le script `install-tck.sh` à la racine télécharge les ZIP officiels depuis
`download.eclipse.org`, extrait les artefacts et les installe via
`mvn install:install-file` avec leurs vrais POMs.

```bash
./install-tck.sh          # JSON-P + JSON-B
./install-tck.sh jsonp    # JSON-P 2.1 uniquement
./install-tck.sh jsonb    # JSON-B 3.0 uniquement
```

Idempotent : ne re-télécharge pas si les jars sont déjà présents dans `~/.m2/`.

### Coordonnées Maven installées

| Coordonnées | Source |
|---|---|
| `jakarta.json:jakarta.json-tck-common:2.1.0` | jsonp ZIP |
| `jakarta.json:jakarta.json-tck-tests:2.1.0` | jsonp ZIP |
| `jakarta.json:jakarta.json-tck-tests-pluggability:2.1.0` | jsonp ZIP |
| `jakarta.json.bind:jakarta.json.bind-tck:3.0.0` | jsonb ZIP |

### Méthode manuelle

Si tu préfères :

1. Télécharger https://download.eclipse.org/jakartaee/jsonp/2.1/jakarta-jsonp-tck-2.1.0.zip
2. Décompresser, aller dans `jsonp-tck/artifacts/`, lancer pour chaque jar :
   ```bash
   mvn install:install-file -Dfile=<jar> -DpomFile=<pom>
   ```
3. Idem pour `jakarta-jsonb-tck-3.0.0.zip`.

---

## Lancement

### TCK JSON-P 2.1

```bash
# smoke test
./run-official-tck-jsonp-2.1.sh

# suite complète
./run-official-tck-jsonp-2.1.sh all

# test ciblé
./run-official-tck-jsonp-2.1.sh -Dtest=NomDuTest
```

Sortie : `champollion-tck/target/tck-report-jsonp.txt`

### TCK JSON-B 3.0

```bash
# smoke test
./run-official-tck-jsonb-3.0.sh

# suite complète, mode runtime introspectif
./run-official-tck-jsonb-3.0.sh all

# suite complète, mode codegen statique (M5.13 — pas encore livré)
./run-official-tck-jsonb-3.0.sh all --static

# test ciblé
./run-official-tck-jsonb-3.0.sh -Dtest=NomDuTest
```

Sortie : `champollion-tck/target/tck-report-jsonb.txt`

### Comportement quand le TCK n'est pas installé

Les scripts vérifient la présence du jar TCK dans le M2 local et **sortent avec
exit code 78** (`EX_CONFIG`) si absent. C'est interprété comme un **skip** par les
runners CI (pas un échec). Voir `.forgejo/workflows/ci.yml` :

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

## Mode static (--static, JSON-B uniquement)

L'option `--static` rejoue la suite TCK avec les fixtures recompilées via
`champollion-codegen-apt` pour valider que le **codegen statique** (M5) produit
le même comportement que le runtime introspectif.

**Statut M6** : implémentation reportée. Nécessite :

1. Extraction du JAR TCK
2. Recompilation des classes de fixtures (records ou POJOs) avec le `JsonbStaticProcessor`
   en injectant l'annotation `@JsonbStatic` sur les types ciblés
3. Repackaging et invocation TCK avec le classpath enrichi

Le differential testing actuel (`DifferentialBindingTest` dans `champollion-codegen-apt`)
fournit déjà une garantie forte que les bindings statiques produisent un comportement
identique au runtime — l'option `--static` ne ferait que confirmer pratiquement sur
le corpus TCK.

---

## Premier run TCK — baseline 2026-05-03

### JSON-P 2.1

| Métrique | 2026-05-03 baseline | Après M2.x + M3.4 | Après M6.x |
|---|---|---|---|
| Tests exécutés | 197 | 179 (api seulement) | 179 |
| **PASS** | **65** (33 %) | **168** (94 %) | **178** (99,4 %) ✅ |
| FAIL | 112 | 7 | 0 |
| ERROR | 20 | 4 | 1 (sigtest env) |

**100 % des tests applicables PASS** — l'unique ERROR restant est `JSONPSigTest.signatureTest`,
un challenge environnemental (signature file Eclipse non distribué dans le ZIP TCK 2.1.0).

**Fixes M6.x** :
- `ChampollionJsonObject.getString/getInt/getBoolean/isNull` lèvent NPE si la clé n'existe pas (spec 2.1.4)
- `ChampollionJsonObjectBuilder.remove(null)` & `addAll(null)` lèvent NPE
- `JsonProvider.createValue(Number)` accepte `Integer/Long/Double/Float/Short/Byte/BigDecimal/BigInteger/AtomicLong/...`
- `JsonBuilderFactory.createObjectBuilder(JsonObject|Map)` & `createArrayBuilder(JsonArray|Collection)` overrides
- `autoDetectingReader` lève `JsonException` si encoding indéterminable (1 octet 0x00 → `jsonObjectUnknownEncoding.json`)
- `ChampollionJsonPointer` : la levée d'exception sur `~n` mal formé est différée à la résolution
  (le TCK `testResolvePathWithUnencodedTilde` attrape l'exception dans `getValue()`, pas dans `createPointer()`)

> **Saut majeur** : `JsonProviderTest.systemProperty()` du TCK polluait
> `System.getProperty("jakarta.json.provider")` sans cleanup, ce qui
> faisait shadow `JsonProvider.provider()` par un mock dans tous les
> tests suivants. Fix : `forkCount=1, reuseForks=false` dans surefire
> + séparation pluggability dans son propre profil.

**Fixes ajoutés** (commit `44a83d9`) :
- `JsonConfig.KEY_STRATEGY` (FIRST/LAST/NONE) côté reader
- `readArray/readObject` lèvent `JsonException` si type incompatible
- `close()` invalide le reader (Spec §3.6)
- Tokenizer utilise `JsonParsingException` (sous-classe `JsonException`)
- `Builder.build()` reset le builder (Spec 2.1 §4.7/§4.8)
- `getConfigInUse()` filtre les properties supportées

Causes principales des ERRORs (toutes pointent des stubs `UnsupportedOperationException` qu'on s'était auto-marqués comme TODO) :
- `Json.createDiff()` → M3.4 reporté
- `parser.getObject()` / `parser.getValue()` → M2 marker
- `createObjectBuilder(Map<String,?>)` → M2.3 marker
- `JsonPointer /~n` rejeté à tort

### JSON-B 3.0

| Métrique | 2026-05-03 baseline | Après M7.x | Après M7.8–M7.15 | Après M7.16 (CDI §5) | **Après M7.17 (creator/property split)** |
|---|---|---|---|---|---|
| Tests exécutés | 295 | 295 | 295 | 295 | 295 |
| **PASS** | **78** (26,4 %) | 248 (84,1 %) | 287 (97,3 %) | 288 (97,6 %) | **289 (97,97 %)** ✅ |
| FAIL | 179 | 35 | 0 | 0 | 0 |
| ERROR | 33 | 7 | 3 (env) | 2 | **1** (env) |
| SKIP | 5 | 5 | 5 | 5 | 5 |

**100 % des tests fonctionnellement applicables PASS** — le seul ERROR restant
(`JSONBSigTest.signatureTest`) est purement environnemental (signature binaire,
fichier `.sig` non distribué dans le ZIP TCK 3.0.0).

**100 % des tests fonctionnellement applicables PASS** — les 3 ERROR restants sont
purement environnementaux (CDI runtime absent + signature binaire).

**Modules à 100 %** :
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

**Fixes structurels majeurs** :
- Bridges/synthetic methods filtrés en POJO introspection (M7.2)
- Generic interface (`TypeContainer<T>`) → `dynamicWriter` runtime resolution (M7.2)
- Abstract classes (`Number`, `TimeZone`, etc.) → résolution dynamique (M7.2)
- `GenericArrayType` (ex. `Optional<String>[]`) géré explicitement (M7.5)
- `byte[]` strategy: BYTE/BASE_64/BASE_64_URL via `JsonbConfig.BINARY_DATA_STRATEGY` (M7.1)
- Date/Time builtins : Duration, Period, LocalTime, OffsetTime, ZoneId, ZoneOffset, MonthDay, YearMonth, Year, Date, Calendar, TimeZone, SimpleTimeZone (M7.3)
- Collections raw type → impl spécifique (`Queue→LinkedList`, `Deque→ArrayDeque`, `SortedSet→TreeSet`, etc.) (M7.4)
- Property visibility hierarchy : `private getter` masque `public field` (M7.5)
- `final` fields skip côté lecture (M7.5)
- Naming strategies (LOWER_CASE_WITH_DASHES/UNDERSCORES, UPPER_CAMEL_CASE, UPPER_CAMEL_CASE_WITH_SPACES, IDENTITY, CASE_INSENSITIVE) (M7.6)
- PropertyOrderStrategy (LEXICOGRAPHICAL/REVERSE/ANY) + `@JsonbPropertyOrder` + classe parent → enfant (M7.6)
- PropertyVisibilityStrategy via `JsonbConfig` / `@JsonbVisibility` sur classe / package (M7.6)
- @JsonbTransient + autre annotation Jsonb → JsonbException (M7.6)
- Détection de duplicate property names → JsonbException (M7.6)
- @JsonbDateFormat / @JsonbNumberFormat à plusieurs niveaux (member, type, package, config) avec locale (M7.7)
- @JsonbNillable propagation type/package + @JsonbProperty(nillable=true) (M7.7)
- JSON-P types (JsonObject/JsonArray/JsonValue/JsonString/JsonNumber) traités natifs (M7.7)
- @JsonbTypeInfo dispatch sur POJO non-record (M7.7)
- `JsonbConfig.FAIL_ON_UNKNOWN_PROPERTIES`, `JsonbConfig.LOCALE` (M7.7)

**Fixes M7.8 → M7.15 (39 tests gagnés, 248 → 287)** :
- M7.8 — Validations `@JsonbCreator` (multiplicité, return type factory, ctor + factory),
  setters/fields appliqués post-creator (`testCustomConstructorPlusFields`),
  defaults `Optional* / OptionalInt / OptionalLong / OptionalDouble`,
  flag `JsonbConfig.CREATOR_PARAMETERS_REQUIRED`.
- M7.10 — Cascade discriminator multi-niveau `@JsonbTypeInfo` :
  `findTypeInfo` tolère chaîne d'héritage linéaire (Labrador → Dog → Animal → LivingThing),
  `typeInfoChain` collecte parent → enfant, `polymorphicWriter`/`polymorphicReader`
  écrivent/lisent toute la cascade dans l'ordre.
- M7.11 — Validations `@JsonbTypeInfo` §4.8 (multi-inheritance, alias non-subtype, name collision).
- M7.12 — IJSON strict mode complet : `withStrictIJSON`, top-level non-objet/array
  → JsonbException, BinaryDataStrategy.BASE_64 forcé, format date `yyyy-MM-dd'T'HH:mm:ss'Z'xxx`,
  Calendar/Date/LocalDate/Instant convertis via ZonedDateTime UTC, Duration/Period
  préservés au format ISO 8601.
- M7.13 — Adapters globaux (`JsonbConfig.withAdapters`) + `@JsonbTypeAdapter` field-level,
  préservation des génériques de l'Adapted type (`AnimalListAdapter` ↔ `List<AnimalJson>`).
- M7.14 — Custom Serializers/Deserializers : `@JsonbTypeSerializer`/`@JsonbTypeDeserializer`,
  `JsonbConfig.SERIALIZERS`/`DESERIALIZERS`, `ChampollionSerializationContext`
  et `ChampollionDeserializationContext`, `ReplayJsonParser` pour rejouer le current event.
- M7.15 — `@JsonbCreator` dans hiérarchie polymorphique (DateConstructor),
  `@JsonbDateFormat`/`@JsonbTypeAdapter`/`@JsonbTypeDeserializer` sur les params du creator.
- M7.9 — `@JsonbNumberFormat` : `parseLocale("##default")` → `Locale.ROOT`
  (format canonique JSON-B), normalisation NBSP (U+00A0 vs NNBSP U+202F sur CLDR
  Java 13+ français). Symétrie writer + reader.
- M7.13/14 (final) — `readObjectAndApply` tolère END_OBJECT consommé par un
  custom deser (sur-consommation de la valeur enfant), `PrimedParser.currentEvent()` override.

**Fixes M7.16 → M7.17 (CDI §5 + custom Deserializer split — 287 → 289)** :

- **M7.16** — Support spec JSON-B §5 (résolution CDI des Adapter / Serializer
  / Deserializer). Nouveau helper `CdiResolver` (`champollion-jsonb/internal`)
  qui appelle `CDI.current().select(class).get()` via `MethodHandle` réflexifs
  cachés au chargement de classe (zéro dépendance runtime hard sur
  `jakarta.enterprise.cdi-api`). Pré-check `isLikelyManagedBean(class)` :
  on ne tente CDI que si la classe porte une annotation `@*Scoped`,
  `@Singleton`, ou `@Inject` sur l'un de ses membres ; sinon fallback
  immédiat sur `newInstance()`. Vérifications `Instance.isUnsatisfied()` /
  `isAmbiguous()` avant `get()` pour la robustesse. Câblé sur les 4 sites
  concernés par §5 (RuntimeBindingRegistry l. 196 + 459, RuntimeReadRegistry
  l. 216 + 1705). Côté `champollion-tck`, dépendance test `vauban-core`
  (CDI 4.1 maison Vidocq — politique « zéro dépendance externe Vidocq
  stack », pas de Weld) : Vauban fournit `SeContainerInitializer` +
  `CDIProvider` + bean discovery + `@Inject` field injection — tout le
  scaffolding nécessaire pour démarrer le `SeContainer` que les tests TCK
  CDI consomment. Résout `AdaptersCustomizationCDITest` (ERROR → PASS).

- **M7.17** — Distinction du contexte d'appel pour les `@JsonbTypeDeserializer`.
  La spec §10.3 est ambigüe selon le call-site : le TCK contient deux tests
  aux contrats incompatibles si le parser arrive dans le même état :

  | Test | Custom Deserializer | Position parser attendue à l'entrée de `deserialize()` |
  |---|---|---|
  | `SerializersCustomizationCDITest` | `AnimalListDeserializerInjected` (sur regular property) | `START_ARRAY` (premier token de la valeur). Le deserializer fait `while (parser.next() == START_OBJECT)` pour itérer les éléments. |
  | `InstantiationCustomizationTest.testJsonbDeserializerOnCreatorParameter` | `SimpleStringDeserializer` (sur creator parameter) | `KEY_NAME` (avant la valeur). Le deserializer fait `parser.next()` puis vérifie `VALUE_STRING`. |

  Solution : `customDeserializerReader(member, underlying, type, advanceParser)`
  paramètre booléen explicite. Pour les **regular property** (setter, field,
  record component, getter), `advanceParser = true` — Champollion fait un
  `parser.next()` avant de déléguer, positionnant le parser sur le premier
  token de la valeur (e.g. `START_ARRAY`, `START_OBJECT`, `VALUE_*`).
  Pour les **creator parameters** (chemin `resolveCreator`),
  `advanceParser = false` — le parser reste sur `KEY_NAME`, le deserializer
  fait lui-même son `next()`. Cette distinction réconcilie les deux contrats
  TCK et n'est pas un workaround : elle traduit fidèlement la sémantique
  de l'API JSON-B selon que la cible est un setter de propriété ou un
  paramètre de constructeur. Résout `SerializersCustomizationCDITest`
  (ERROR → PASS) en préservant les 10 tests d'`InstantiationCustomizationTest`.

  Commits : `bf8c3b2` (CdiResolver §5), `5fc08b5` (`isLikelyManagedBean`),
  `599f939` (revert d'un fix antérieur trop large), `17c6881` (split
  creator-param vs regular property), `2d59a6f` (doc).

**État final 2026-05-04 — 289/295 PASS (97,97 %)** :

- `AdaptersCustomizationCDITest` — **PASS** ✅ (M7.16)
  Vauban livre `SeContainerInitializer` + bean discovery + `@Inject` fields ;
  Champollion `CdiResolver` (helper réflexif sans dep hard `cdi-api`) résout
  les Adapter/Ser/Deser via `CDI.current().select(class).get()` quand la
  classe est un managed bean réel (pré-check `isLikelyManagedBean` :
  `@*Scoped` / `@Singleton` / `@Inject`).
- `SerializersCustomizationCDITest` — **PASS** ✅ (M7.17)
  Distinction du contexte d'appel pour les custom Deserializer :
  - regular property (setter/field/record/getter) : Champollion fait
    `parser.next()` avant `deserialize()` (parser positionné sur le
    premier token de la valeur, e.g. `START_ARRAY`) ;
  - creator parameter : parser reste à `KEY_NAME`, le deserializer fait
    lui-même son `next()`.
  Ce split résout l'apparente contradiction entre le
  `AnimalListDeserializerInjected` (attend `START_ARRAY` post-next) et le
  `SimpleStringDeserializer` (attend `KEY_NAME` pré-next).
- `JSONBSigTest.signatureTest` — challenge environnemental restant
  (signature binaire, fichier `jakarta.json.bind.sig` non distribué dans
  le ZIP TCK 3.0.0). Hors scope.

## Challenges connus

> Liste des tests désactivés ou divergents avec justification spec / TCK.

### JSON-P 2.1

| Test / classe | Catégorie | Statut | Justification |
|---|---|---|---|
| `JSONPSigTest.signatureTest` | environnement | **challenge accepté** | Test sigtest qui requiert un signature file Eclipse, non distribué dans le ZIP TCK 2.1.0. Cf. `cassini-tck` qui documente le même cas pour JAX-RS. C'est le seul test applicable du profil `jsonp-tck` qui ne PASS pas, et il est unanimement skippé par tous les implémenteurs. |
| `PointerTests.testResolvePathWithUnencodedTilde` (`/m~n`) | RFC vs TCK | **résolu** | Le test marque ce cas `(optional)` et tolère un échec à la résolution. Champollion lève désormais `JsonException` dans `getValue()` (au lieu de `createPointer()`), ce qui rentre dans le try/catch du test. |
| `jsonprovidertests.ClientTests.*` (18 tests pluggability) | profil séparé | **isolé** | Suite pluggability isolée dans `-Pjsonp-tck-pluggability` : nécessite un environnement où `META-INF/services/jakarta.json.spi.JsonProvider` charge `MyJsonProvider` (le mock TCK) à la place de Champollion. Géré par configuration de classpath au lancement, pas un bug. |

### JSON-B 3.0

| Test | Catégorie | Statut | Justification |
|---|---|---|---|
| `JSONBSigTest.signatureTest` | environnement | **challenge accepté** | Fichier signature binaire `jakarta.json.bind.sig` non distribué dans le ZIP TCK 3.0.0 — unanimement skippé par tous les implémenteurs. |
| 5 tests SKIP | TCK upstream | **ignorés** | Tests annotés `@Test(enabled=false)` côté TCK officiel — hors de notre contrôle. |

---

## Discipline de release

- **Aucun merge structurel** sur `champollion-jsonp` ou `champollion-jsonb` sans
  TCK PASS à 100 %.
- Toute modification de `champollion-codegen-apt` doit préserver le score
  differential testing avant merge.
- Les éventuels challenges (tests désactivés) sont documentés ici avec citation
  spec, hash du test, et plan de réactivation.

---

## CI Forgejo

Le workflow `.forgejo/workflows/ci.yml` exécute en parallèle les jobs `tck-jsonp`
et `tck-jsonb` après le job `build`. Garde shell `if [ -x ./run-official-tck-*.sh ]`
qui rend les jobs no-op si les scripts ne sont pas (encore) présents — désormais
ils le sont, mais l'exit 78 du script en absence du TCK reste un skip propre.

Pour activer les jobs en production, installer les TCK officiels sur le runner CI
(ajout à `actions/setup-java` ou pre-step Maven).

---

# Google Protobuf Conformance (M5)

Suit le **conformance_test_runner** Google
(<https://github.com/protocolbuffers/protobuf/blob/main/conformance/README.md>),
non distribué en binaire — à builder via Bazel.

## Méta

- **Module** : `champollion-protobuf-tck` (HORS reactor, POM Model 4.0.0).
- **Script** : `./run-official-conformance-protobuf.sh [smoke|all|--editions]`.
- **Wrapper Java** : `io.vidocq.champollion.protobuf.conformance.ConformanceRunner`.
- **Contrat** : 100% PASS sur le subset déclaré ci-dessous avant tout merge
  structurel sur `champollion-protobuf`.

## Régénération des types pour conformance

`champollion-protobuf-tck/src/main/proto/test_messages_proto3.proto` contient un
subset de `google/protobuf/test_messages_proto3.proto`. Régénérer après modif :

```bash
cd champollion
mvn -ntp -pl champollion-protobuf,champollion-protobuf-codegen install -DskipTests
champollion-protobuf-tck/generate-tck-sources.sh
```

Le proto fullName reste canonique (`protobuf_test_messages.proto3.TestAllTypesProto3`)
attendu par le runner Google ; le package Java forcé à
`io.vidocq.champollion.protobuf.tck.proto3`. APT `@ProtobufStatic` active
automatiquement pour générer un parser sans réflexion.

## Score actuel — `CONFORMANCE SUITE PASSED` 🎯

Dernier run (2026-05-24, branche `main`) :

```
CONFORMANCE SUITE PASSED: 2699 successes, 0 skipped,
                          0 expected failures, 0 unexpected failures.
```

- **2699 tests PASS** sur le périmètre `--maximum_edition PROTO3` (proto2 + proto3).
- **0 expected failures** — `conformance-failure-list.txt` désormais vide après les fixes
  M6.9 (UnknownFieldSet), M7.1 (unknown enum JSON), M7.2 (Proto2 packed int32), M7.3 (Any-in-Any, commit `e31f65b`).
- **0 unexpected failures** → exit code 0.

## Capacités couvertes

| Capacité | Statut | Notes |
|---|---|---|
| Pipe stdin/stdout protocol | ✅ M1.6 | length-prefix LITTLE-endian (cf. `docs/adr/0004-…`) |
| Dispatch par `message_type` | ✅ M5.2 | `KNOWN_TYPES` registry — TestAllTypesProto2 + Proto3 + 16 WKT |
| Scalaires + repeated (15 + 15 + 14 packed + 14 unpacked) | ✅ M5.5 | int32/int64/uint32/.../float/double/bool/string/bytes |
| Wire PROTOBUF in/out | ✅ M5.2 | runtime reflectif (présence-aware) |
| Wire JSON canonical in/out | ✅ M5.2 | `ProtobufJson.fromJson` / `toJson` |
| Wire JSPB / TEXT_FORMAT | ❌ | hors scope ADR-0001 |
| `utf8_validation = VERIFY` | ✅ M4.3.1–3 | Read + write strict |
| `field_presence = EXPLICIT` | ✅ M4.3.4–5 | `@ProtobufField(explicitPresence)` |
| Nested messages (récursifs) | ✅ M5.5 | NestedMessageT/P2 top-level (collision package APT) |
| `map<K,V>` (16 combinaisons) | ✅ M5.9 | `FieldType.MAP` + `mapKey/mapValue` (cf. `docs/adr/0002-…`) |
| `oneof` JSON tracker + wire last-wins | ✅ M5.6 + M5.10 | `oneofGroup` annotation (cf. `docs/adr/0003-…`) |
| WKT Wrappers / Timestamp / Duration / FieldMask / Any (top-level) | ✅ M4.3.5 + M5.5 | range validation + JSON canonical |
| Field name to JSON name (18 variations) | ✅ M5.10 | `_field_name3`, `FIELD_NAME11`, etc. |
| Int range strict + BigDecimal exponent | ✅ M5.10 | `0.5`, `1e5`, `TooLarge`, `TooSmall` |
| BadTag wire 6/7 + overlong + field# > 2^29-1 | ✅ M5.6.2 + M5.10 | 5e octet limité à 4 bits (overflow int32) |
| `MessageEncoding.DELIMITED` (Edition 2023) | ❌ | Backlog M6 (cf. `docs/adr/0005-…`) |
| WKT Struct/Value/ListValue/NullValue sealed | ❌ | Backlog M6 — expected failure |
| Any contenu complexe (nested WKT) | ❌ | Backlog M6 — expected failure |
| EnumFieldWithAlias (allow_alias=true) | ❌ | Java enum ne supporte pas — M6 via `@ProtoEnumValue` |
| NEG enum (proto value = -1) | ❌ | Backlog M6 — annotation requise |
| UnknownFieldSet preservation | ❌ | Backlog M6 — composant record dédié |
| RepeatedScalarMessageMerge sémantique | ❌ | Backlog M6 |
| MessageSetEncoding proto2 | ❌ | Legacy internal Google, peut rester en failure-list |

## Procédure de release

Pas de PR `champollion-protobuf` mergée tant que :

1. Reactor 11/11 `BUILD SUCCESS`.
2. `champollion-protobuf` JUnit 100% verts (actuellement **236 tests**).
3. `champollion-protobuf-tck` JUnit 100% verts (actuellement **8 tests**).
4. `./run-official-conformance-protobuf.sh smoke` retourne `CONFORMANCE SUITE PASSED`
   avec `0 unexpected failures`. Tout nouveau FAIL inattendu doit être :
   - soit corrigé,
   - soit ajouté à `conformance-failure-list.txt` avec citation ADR justifiant
     (review explicite via `git diff` à la PR).

## FAIL connus / expected failures

**Aucun** — `conformance-failure-list.txt` est désormais vide (commit `e31f65b`).
Toutes les catégories précédemment attendues ont été résolues dans les phases M6–M7.3 :
WKT Struct/Value/ListValue/NullValue, Any-in-Any, aliasing d'enum, NEG enum,
UnknownFieldSet, RepeatedScalarMessageMerge, MessageSetEncoding proto2 (M6.9),
Uint64QuotedExponent et EnumFieldUnknownValue.Validator (M7.1).
