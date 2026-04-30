# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Prérequis

- **Java 25** + **Maven 4.0.0-rc-5** (`.sdkmanrc` fourni — utiliser `sdk env`)
- Les TCK officiels devront être installés dans le M2 local (artefacts non-publics) :
  - `jakarta.json:jakarta.json-tck:2.1.x` (JSON-P 2.1)
  - `jakarta.json.bind:jakarta.json.bind-tck:3.0.x` (JSON-B 3.0)

## Commandes essentielles

```bash
# Build du reactor (sans TCK)
mvn -ntp install -DskipTests

# Tests unitaires
mvn test

# Benchmarks JMH
mvn -pl champollion-bench -am package
java -jar champollion-bench/target/benchmarks.jar

# TCK JSON-P — smoke test seulement
./run-official-tck-jsonp-2.1.sh

# TCK JSON-B — smoke test seulement
./run-official-tck-jsonb-3.0.sh
```

> `champollion-tck` sera **hors reactor** (POM Model 4.0.0 standalone) pour
> contourner ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0 — même contrainte
> que `cassini-tck` et `foy-tck`. Ne pas changer ce modèle.

## Architecture

Champollion est une implémentation Jakarta JSON Processing 2.1 + Jakarta JSON Binding 3.0,
**zéro dépendance hors specs Jakarta**, virtual threads, JPMS strict, et compilation
statique des bindings via APT pour éviter la réflexion à chaud.

```
champollion-api        ← Spec Jakarta seulement (re-exposition jakarta.json + jakarta.json.bind)
champollion-jsonp      ← Implémentation JSON-P 2.1 (parser, generator, JsonValue, JsonPatch, JsonPointer, JsonMergePatch)
champollion-jsonb      ← Implémentation JSON-B 3.0 (Jsonb, JsonbBuilder, ser/deser, customization)
champollion-codegen    ← APT + Maven plugin : génère les serializers/deserializers JSON-B à la compilation
champollion-bench      ← JMH : comparatif Parsson/Yasson/Jackson, throughput/latence, allocations
champollion-examples   ← Exemples d'utilisation
champollion-tck        ← Runners TCK officiels JSON-P 2.1 et JSON-B 3.0 (HORS reactor)
```

**Flux JSON-P :** `JsonParser` (pull) ↔ `JsonGenerator` (push) sur `Reader/Writer/InputStream/OutputStream`.
Object model `JsonObject/JsonArray/JsonValue` au-dessus. Patch/Pointer/MergePatch en couche feuille.

**Flux JSON-B :** `Jsonb.toJson(obj)` → `BindingPlan` (résolu une seule fois par classe) → série de
`PropertyWriter` qui pushent dans un `JsonGenerator` (champollion-jsonp). Symétrique en lecture.

**Deux modes de binding :**
- **Mode Runtime** (par défaut) : introspection à la première rencontre de la classe via `MethodHandles`,
  cache concurrent. Pas de réflexion à chaque appel — coût amorti après warmup.
- **Mode Statique** (recommandé) : APT `champollion-codegen` génère un `BindingFactory` par type
  annoté `@JsonbStatic` (ou détecté par scan classpath via le Maven plugin). Aucune réflexion à l'exécution,
  compatible AOT (GraalVM, Leyden CDS). ServiceLoader résout automatiquement la factory générée.

## Contraintes d'architecture à ne pas violer

1. **Zéro dépendance hors specs Jakarta** dans `champollion-jsonp` et `champollion-jsonb`.
   JUnit/JMH uniquement en `scope=test`/`scope=provided`.
2. **`champollion-jsonb` dépend de `champollion-jsonp`** mais jamais l'inverse — la couche binding
   sait composer sur la couche processing, pas l'inverse.
3. **JPMS strict** : tous les modules ont un `module-info.java`, packages `internal.*` non exportés,
   SPI exposée uniquement via `provides ... with`.
4. **Pas de `synchronized`, pas de `ThreadLocal`** — virtual-thread-friendly. Utiliser `ScopedValue`
   pour la propagation contextuelle (ex. `JsonbContext.CURRENT` pendant un `toJson`).
5. **Pas de réflexion `setAccessible(true)`** sauf en mode runtime explicitement documenté ;
   privilégier `MethodHandles.privateLookupIn` + `module.addOpens` côté consommateur.
6. **TCK JSON-P et JSON-B PASS à 100 %** est un contrat avant tout merge structurel sur `jsonp`/`jsonb`.

## Conventions

- **Java modules explicites** : tous les modules ont un `module-info.java`.
- **Packages** :
  - `io.vidocq.champollion.spi.*` = SPI public stable (extensions tierces)
  - `io.vidocq.champollion.internal.*` = code interne (peut casser entre versions)
- **Maven groupId** : `io.vidocq.champollion`.
- **Records** pour tous les DTO immuables ; **sealed interfaces** pour les hiérarchies fermées
  (`JsonValue`, `JsonEvent`, `BindingNode`).
- **Pattern matching** exhaustif sur switch — pas de chaîne `if/else if`.
- **`java.lang.foreign`** envisagé pour le scanner JSON le plus chaud (parser SIMD-friendly).

## Roadmap en cours

Voir `ROADMAP.md` pour le plan détaillé phase par phase (M0..M7).

## TDD — Test-Driven Development (obligatoire)

Champollion est développé en **TDD strict**, dans cet ordre :

1. **Red** — écrire le test qui décrit le comportement attendu (citation spec ou RFC en commentaire JavaDoc).
   Le test doit échouer pour la bonne raison (compilation OK, assertion KO).
2. **Green** — écrire le minimum de code pour faire passer le test. Pas d'optimisation, pas d'abstraction
   qui anticipe un test futur.
3. **Refactor** — nettoyer en gardant les tests verts. Lancer la suite complète du module avant tout commit.

Règles concrètes :

- **Un test par classe publique**, nommé `<Classe>Test`, dans le même package (`src/test/java`).
- **Pas de Mockito** — doubles écrits à la main ; le découplage du code s'y prête.
- **Tests par fixture spec** : pour chaque section RFC 8259 / Jakarta JSON-P 2.1 / Jakarta JSON-B 3.0
  référencée, un test nommé `<methode>_rfc8259_section6_4()` ou similaire. Permet la traçabilité spec ↔ test.
- **JSONTestSuite** (`nst/JSONTestSuite`) intégré dès le M1 dans `champollion-jsonp/src/test/resources/`
  pour un harness de conformité RFC 8259 indépendant du TCK.
- **Coverage mesurée** mais pas érigée en gate ; la qualité du test prime sur le pourcentage.
- **Differential testing** entre runtime reflectif (M4) et codegen statique (M5) : pour chaque type
  testé, on vérifie que `runtime.toJson(o).equals(static.toJson(o))` et symétriquement à la lecture.

## TCK — Technology Compatibility Kits

Deux TCK officiels, exécutés dans un module hors reactor (`champollion-tck`, POM Model 4.0.0)
pour contourner ShrinkWrap Maven Resolver 3.3 :

| TCK | Artifact | Cibles |
|---|---|---|
| Jakarta JSON Processing 2.1 | `jakarta.json:jakarta-json-tck:2.1.x` | 100 % PASS (contrat) |
| Jakarta JSON Binding 3.0 | `jakarta.json.bind:jakarta-json-bind-tck:3.0.x` | 100 % PASS (contrat) |

Les scripts `run-official-tck-jsonp-2.1.sh` et `run-official-tck-jsonb-3.0.sh` :

- supportent `smoke` (par défaut), `all`, et `-Dtest=NomDuTest` ciblé ;
- installent le reactor en local (`mvn install -DskipTests`) avant invocation ;
- produisent un rapport `target/tck-report.txt` avec le score PASS/FAIL/SKIP.

**Discipline de release :**

- **Aucun merge structurel** sur `champollion-jsonp`/`champollion-jsonb` sans TCK PASS.
- Les éventuels challenges (tests désactivés pour interprétation spec ou bug TCK) sont documentés
  dans `TCK.md` avec citation spec, hash du test, et plan de réactivation. Suit le modèle `cassini/TCK.md`.
- **Differential mode** : le TCK doit passer aussi bien en mode runtime qu'en mode codegen statique.
  Le script `run-official-tck-jsonb-3.0.sh --static` recompile les fixtures TCK avec l'APT pour valider
  la cohérence du codegen.

## Principes IA — collaboration sur ce dépôt

- **Plan mode par défaut** sur tout changement structurel (nouveau module, nouveau format wire,
  modification de SPI publique).
- **Élégance équilibrée** : préférer un design simple qui passe le TCK à un design parfait qui
  ne le passe pas. Documenter les arbitrages dans des ADR (`docs/adr/`).
- **Pas de paresse sur les specs** : citer la section RFC 8259 / Jakarta JSON-P 2.1 / Jakarta
  JSON-B 3.0 dans les commentaires de code quand l'implémentation y répond directement.
- **Zéro dépendance** : si une lib externe semble nécessaire, c'est qu'on s'est trompé de découpe.
