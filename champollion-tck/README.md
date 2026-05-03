# champollion-tck

Runner des TCK officiels Jakarta JSON-P 2.1 et Jakarta JSON-B 3.0.

## Hors reactor

Ce module est volontairement **hors du reactor** Champollion (POM Maven Model 4.0.0
standalone) pour contourner une incompatibilité de ShrinkWrap Maven Resolver 3.3
avec les POMs Model 4.1.0. Il s'invoque depuis la racine du projet :

```bash
./run-official-tck-jsonp-2.1.sh    # JSON-P 2.1
./run-official-tck-jsonb-3.0.sh    # JSON-B 3.0
```

## Prérequis

Voir [TCK.md](../TCK.md) pour l'installation des TCK officiels (artifacts
non-publics, distribués par l'Eclipse Foundation).

## Profils Maven

| Profil | TCK |
|---|---|
| `-Pjsonp-tck` | Jakarta JSON Processing 2.1 (JUnit 5) |
| `-Pjsonb-tck` | Jakarta JSON Binding 3.0 (TestNG + Arquillian) |
