# ADR 0004 — Protocole `conformance_test_runner` Google + `--failure_list`

## Statut

Accepté — 2026-05-22.

## Contexte

Le `conformance_test_runner` Google (compilé via CMake depuis
`protocolbuffers/protobuf` v35.0, cf. M5.4) communique avec l'implémentation
testée par un pipe stdin/stdout. Le protocole exact n'est pas trivialement
documenté et a été reverse-engineering au cours de M5.4.

## Décision — Protocole exact

1. **Length-prefix LITTLE-ENDIAN, pas big-endian** comme la documentation
   pourrait laisser penser. Le runner écrit `[4 octets length LE][protobuf
   ConformanceRequest bytes]` sur stdin du child. Champollion fait l'inverse
   en sortie.

2. **`fork_pipe_runner`** fork le wrapper à chaque test (pas long-lived).
   La JVM démarre en ~70 ms avec AppCDS — assez rapide pour 2600 tests
   séquentiels.

3. **Tag séparateur `--` non supporté.** Le runner moderne accepte uniquement
   `runner [options] <program>` (program en dernier). On utilise un wrapper
   bash `target/run-runner.sh` qui `exec java -cp …`.

4. **Classpath complet via `mvn dependency:build-classpath`.** Le `java -jar
   tck-jar` ne charge pas les deps externes (NoClassDefFoundError sur
   `Message`, `jakarta.json-api`, etc.). Le script génère
   `target/conformance-classpath.txt` et l'utilise pour `java -cp`.

5. **`--failure_list FILE`** — fichier texte avec un nom de test par ligne,
   `#` pour commentaires. Tests listés = expected failures, comptés
   séparément dans la sortie `CONFORMANCE SUITE PASSED: N successes, …
   M expected failures, 0 unexpected failures`.

## Conséquences

**Positives** :
- Wrapper Java stateless, reproductible. Une seule commande
  `./run-official-conformance-protobuf.sh smoke` → exit 0 = 100% PASS.
- La failure-list est versionnée — review explicite des "expected failures"
  à chaque PR (`git diff conformance-failure-list.txt`).
- Le mode dégradé (sans `CONFORMANCE_TEST_RUNNER` env var) reste opérationnel
  — utile pour les contributeurs qui n'ont pas le runner builded localement.

**Négatives** :
- Le format `--failure_list` n'est pas documenté côté Google ; sa stabilité
  inter-version n'est pas garantie. Mitigation : re-run mensuel + alerte si
  divergence.

## Alternatives écartées

| Option | Pourquoi écarté |
|---|---|
| Big-endian length-prefix | Démontré faux par le timeout 30s observé en M5.4 |
| Long-lived JVM process | `fork_pipe_runner` ne le supporte pas ; il fork à chaque test |
| Fat jar avec toutes les deps | Lourd (~30 MB), inutile vu le `java -cp` |
| `failure_list` inline dans le wrapper | Perdrait la review explicite via git diff |
