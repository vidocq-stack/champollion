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

Les TCK Jakarta sont distribués par l'Eclipse Foundation et **ne sont pas
disponibles sur Maven Central** public. Il faut les installer manuellement
dans le M2 local.

### TCK Jakarta JSON Processing 2.1

1. Télécharger depuis https://download.eclipse.org/jakartaee/jsonp/2.1/
   Le fichier `jakarta-json-tck-2.1.0.jar` (ou la version la plus récente compatible).

2. Installer dans le M2 local :

   ```bash
   mvn install:install-file \
       -Dfile=jakarta-json-tck-2.1.0.jar \
       -DgroupId=jakarta.json \
       -DartifactId=jakarta-json-tck \
       -Dversion=2.1.0 \
       -Dpackaging=jar
   ```

3. Vérifier :

   ```bash
   ls ~/.m2/repository/jakarta/json/jakarta-json-tck/2.1.0/
   ```

### TCK Jakarta JSON Binding 3.0

1. Télécharger depuis https://download.eclipse.org/jakartaee/jsonb/3.0/

2. Installer :

   ```bash
   mvn install:install-file \
       -Dfile=jakarta-json-bind-tck-3.0.0.jar \
       -DgroupId=jakarta.json.bind \
       -DartifactId=jakarta-json-bind-tck \
       -Dversion=3.0.0 \
       -Dpackaging=jar
   ```

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

## Challenges connus

> Liste des tests désactivés avec justification de spec ou bug TCK. Modèle aligné
> sur `cassini/TCK.md`.

### JSON-P 2.1

| Test | Catégorie | Statut | Justification |
|---|---|---|---|
| _aucun_ | — | — | Aucun challenge identifié à ce jour. |

### JSON-B 3.0

| Test | Catégorie | Statut | Justification |
|---|---|---|---|
| _aucun_ | — | — | Aucun challenge identifié à ce jour (les annotations `@JsonbVisibility`, `@JsonbNumberFormat` et les naming strategies seront vérifiées au premier run TCK). |

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
