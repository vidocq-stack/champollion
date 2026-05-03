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

| Métrique | Valeur |
|---|---|
| Tests exécutés | 197 |
| **PASS** | **65** (33 %) |
| FAIL | 112 |
| ERROR | 20 |

Causes principales des ERRORs (toutes pointent des stubs `UnsupportedOperationException` qu'on s'était auto-marqués comme TODO) :
- `Json.createDiff()` → M3.4 reporté
- `parser.getObject()` / `parser.getValue()` → M2 marker
- `createObjectBuilder(Map<String,?>)` → M2.3 marker
- `JsonPointer /~n` rejeté à tort

### JSON-B 3.0

| Métrique | Valeur |
|---|---|
| Tests exécutés | 295 |
| **PASS** | **75** (25 %) |
| FAIL | 182 |
| ERROR | 33 |
| SKIP | 5 |

Score initial à investiguer : naming strategies, configuration, mapping types tiers
non encore couverts.

### Plan d'attaque pour 100 % PASS

1. **M2.x stubs** : implémenter les méthodes `UnsupportedOperationException`
   identifiées (`getObject`, `getValue`, `createObjectBuilder(Map)`,
   `createDiff`).
2. **JsonPointer escapes** : revoir le rejet de `~n` (probablement ce que le TCK
   attend que ce soit accepté ou mieux signalé).
3. **JSON-B advanced** : `@JsonbVisibility`, `@JsonbNumberFormat`, naming
   strategies (camelCase, snake_case, etc.), property order strategy.
4. **Differential investigation** : pour chaque test FAIL, trouver la divergence
   spec/Champollion et la documenter dans la table _Challenges_ ci-dessous.

## Challenges connus

> Liste des tests désactivés avec justification de spec ou bug TCK.

### JSON-P 2.1

| Test | Catégorie | Statut | Justification |
|---|---|---|---|
| _à investiguer_ | — | — | Premier run baseline ; analyse FAIL/ERROR à venir. |

### JSON-B 3.0

| Test | Catégorie | Statut | Justification |
|---|---|---|---|
| _à investiguer_ | — | — | Premier run baseline ; analyse FAIL/ERROR à venir. |

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
