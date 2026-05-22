# ADR 0001 — JSPB et TEXT_FORMAT hors scope

## Statut

Accepté — 2026-05-22.

## Contexte

Le `conformance_test_runner` Google envoie 4 wire formats à tester :
PROTOBUF, JSON, JSPB, TEXT_FORMAT. Pour atteindre 100% PASS, Champollion doit
fournir une réponse correcte sur les 4 — ou les déclarer comme expected
failures dans `--failure_list`.

- **JSPB** : encodage JavaScript Protocol Buffer interne à Google. Utilisé
  par Closure Library / GWT côté frontend Google. Aucun consommateur Java
  raisonnable. Non documenté dans la spec publique proto3.
- **TEXT_FORMAT** : format texte ad-hoc (`my_field: 42 nested { ... }`).
  Spec officiellement « debugging only » (cf. <https://protobuf.dev/reference/protobuf/textformat-spec/>).
  ~5 kLOC d'implémentation pour zéro valeur métier en pratique — les
  consommateurs Java ont déjà `Message.toString()` natif.

## Décision

**Champollion ne supporte ni JSPB ni TEXT_FORMAT.**

Les tests conformance qui les requièrent (`*.JspbInput.*`, `*.JspbOutput.*`,
`*.TextInput.*`, `*.TextOutput.*`) sont déclarés dans
`champollion-protobuf-tck/conformance-failure-list.txt` et passés au runner
Google via `--failure_list`. Ils sont comptés comme **expected failures** —
n'impactent pas le score 100% PASS du périmètre supporté.

Le `ConformanceRunner.handle` continue de répondre `skipped` pour ces tests
(absent de `KNOWN_TYPES` ou wire format JSPB/TEXT) — le runner Google les
classe selon la failure-list.

## Conséquences

**Positives** :
- Économie de ~30 jours-homme d'implémentation (TEXT_FORMAT lecteur + écrivain
  + 4 sous-grammaires).
- Aucune dette technique sur des formats que personne n'utilisera en pratique
  côté Java (la spec public Google ne mentionne JSPB nulle part).
- Cohérent avec `protobuf-java` officiel qui n'a pas non plus de lecteur
  TEXT_FORMAT strict.

**Négatives** :
- Le score conformance affiché par le runner contient une ligne
  `87 expected failures` à expliquer à un reviewer.
- Si une consommation gRPC future requiert un format texte (peu probable —
  gRPC est binaire), il faudra implémenter.

## Alternatives écartées

| Option | Pourquoi écarté |
|---|---|
| Implémenter TEXT_FORMAT lecteur seul | Spec non figée, ~5 kLOC, zero usage |
| Implémenter JSPB | Aucun consommateur public, format propriétaire Closure |
| Forker le runner Google pour supprimer ces tests | Coût de maintenance > coût du failure-list |
