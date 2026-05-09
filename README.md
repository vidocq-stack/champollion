<p align="center">
  <img src="champollion-logo.png" alt="Champollion" width="300">
</p>

<h1 align="center">Champollion</h1>

<p align="center">
  <strong>Implémentation Jakarta JSON-P 2.1 + JSON-B 3.0 — zéro dépendance, codegen statique APT, JPMS natif</strong><br>
  <a href="https://jakarta.ee/specifications/jsonp/2.1/">Jakarta JSON-P 2.1</a> | <a href="https://jakarta.ee/specifications/jsonb/3.0/">Jakarta JSON-B 3.0</a> | Virtual Threads | JDK 25
</p>

<p align="center">
  <img src="https://img.shields.io/badge/JDK-25-orange" alt="JDK">
  <img src="https://img.shields.io/badge/Maven-4.0--rc--5-purple" alt="Maven">
  <img src="https://img.shields.io/badge/Jakarta_JSON--P-2.1-blue" alt="Jakarta JSON-P">
  <img src="https://img.shields.io/badge/Jakarta_JSON--B-3.0-blue" alt="Jakarta JSON-B">
  <img src="https://img.shields.io/badge/license-Apache_2.0-green" alt="License">
</p>

---

Champollion est l'implémentation **Jakarta JSON Processing 2.1** et **Jakarta JSON Binding 3.0**
de l'écosystème [Vidocq](https://forge.vidocq.dev/vidocq) : zéro dépendance hors specs Jakarta,
JDK 25 pur, JPMS strict, virtual threads, **compilation statique des bindings via APT** pour
éviter la réflexion à chaud.

Le nom rend hommage à **Jean-François Champollion** (1790–1832), déchiffreur des hiéroglyphes
égyptiens — Champollion lit les structures arbitraires et les traduit en objets Java typés.

## Modules

| Module | Description |
| --- | --- |
| `champollion-api` | Re-expose `jakarta.json` + `jakarta.json.bind`. Point d'extension propre à venir. |
| `champollion-jsonp` | Implémentation complète JSON-P 2.1 — parser/generator streaming, object model, builders, Reader/Writer, JsonPointer (RFC 6901), JsonPatch (RFC 6902), JsonMergePatch. |
| `champollion-jsonb` | Implémentation JSON-B 3.0 — `toJson`/`fromJson` runtime + lookup-first via SPI `JsonbBinding<T>`. Couvre primitives, `java.time`, UUID, enum, records, POJOs, containers. |
| `champollion-codegen-apt` | Annotation Processor JDK pur. Génère un `JsonbBinding<T>` par record annoté `@JsonbStatic` + ServiceLoader file. Différentiel automatisé static vs runtime. |
| `champollion-codegen-maven-plugin` | Mojo `generate` qui scanne le classpath compile et délègue à l'APT pour les classes non-annotables (POJOs tiers). `maven-plugin-plugin 4.0.0-beta-2`, Java 25. |
| `champollion-tck` | **Hors reactor** (POM Model 4.0.0). Profils `-Pjsonp-tck` (JUnit 5) et `-Pjsonb-tck` (TestNG). Skip propre (exit 78) si TCK officiel absent. |
| `champollion-bench` | POM JMH prêt — comparatifs vs Parsson / Yasson / Jackson à venir. |
| `champollion-examples` | Exemples d'usage — à écrire. |

## Philosophie (héritée de Vidocq)

- **JPMS strict**, pas de classpath.
- **Class-File API (JEP 484) + APT** pour générer les `JsonbBinding<T>` à la compilation. Aucune réflexion runtime quand le binding statique existe ; runtime introspectif en fallback uniquement pour les types non recompilables.
- **Zéro dépendance externe** hors `jakarta.json-api`, `jakarta.json.bind-api`. Pas de Parsson, Yasson, ni Jackson.
- **Virtual Threads** — pas de `synchronized`, pas de `ThreadLocal`. Caches `ConcurrentHashMap` / `ClassValue`. Propagation via `ScopedValue`.
- **TDD strict** — Red → Green → Refactor, citation RFC dans les `@DisplayName`.
- **TCK 100 % PASS** comme contrat dur sur JSON-P et JSON-B, en mode runtime ET en mode codegen statique.

## Statut

🟢 Reactor opérationnel — `mvn install -DskipTests` ✅ sur 8 modules, **290/290** tests unitaires verts.

| Phase | État |
| --- | --- |
| M0 — Bootstrap multi-module, JPMS, ServiceLoader | ✅ |
| M1 — JSON-P (tokenizer, parser, generator, provider) | ✅ |
| M2 — Object model + builders + Reader/Writer | ✅ |
| M3 — JsonPointer + JsonPatch + MergePatch | ✅ |
| M4 — JSON-B runtime introspectif | ✅ |
| M5 — Codegen APT + Maven plugin | ✅ MVP |
| M6 — TCK 100 % PASS | 🟡 baseline en cours |
| M7 — Benchmarks JMH vs Parsson / Yasson / Jackson | ⏳ planifié |

Détails par sous-module et avancement par phase : voir [`STATUS.md`](./STATUS.md) et [`ROADMAP.md`](./ROADMAP.md).
État TCK exhaustif : [`TCK.md`](./TCK.md).
Bugs reproductibles : [`BUG.md`](./BUG.md). Mesures perf : [`BENCH.md`](./BENCH.md).

## Démarrage rapide

```bash
sdk env                           # JDK 25 + Maven 4.0.0-rc-5 (cf. .sdkmanrc)
mvn -ntp install -DskipTests      # build du reactor (8 modules)
mvn test                          # tests unitaires
```

TCK officiels (artefacts non publics, à installer dans le M2 local) :

```bash
./install-tck.sh                  # télécharge les ZIP officiels Eclipse
./run-official-tck-jsonp-2.1.sh   # smoke test JSON-P
./run-official-tck-jsonb-3.0.sh   # smoke test JSON-B
```

## Documentation

Le site Antora de l'écosystème Vidocq agrège la documentation de Champollion (FR + EN) :
[doc.vidocq.dev/champollion-fr](https://doc.vidocq.dev/champollion-fr/) ·
[doc.vidocq.dev/champollion](https://doc.vidocq.dev/champollion/).

## Licence

Apache License 2.0 — voir [`LICENSE`](./LICENSE).
