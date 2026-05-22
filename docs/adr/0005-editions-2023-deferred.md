# ADR 0005 — Editions 2023 reportées en M6

## Statut

Accepté — 2026-05-22.

## Contexte

Protocol Buffers Editions 2023 est la spec successeur de proto2/proto3 (cf.
<https://protobuf.dev/editions/overview/>). Le wire format reste identique
mais introduit des `features` configurables au niveau file/message/field :

- `features.field_presence ∈ {IMPLICIT, EXPLICIT, LEGACY_REQUIRED}`
- `features.enum_type ∈ {OPEN, CLOSED}`
- `features.repeated_field_encoding ∈ {PACKED, EXPANDED}`
- `features.utf8_validation ∈ {VERIFY, NONE}`
- `features.message_encoding ∈ {LENGTH_PREFIXED, DELIMITED}` ← réactive les
  proto2 groups (wire types 3/4)
- `features.json_format ∈ {ALLOW, LEGACY_BEST_EFFORT}`

Champollion a déjà :
- ✅ M4.1 : modèle `FeatureSet` dans `Descriptors` (records sealed enum).
- ✅ M4.2 : propagation `file → message → field` côté codegen.
- ✅ M4.3 : runtime consomme `Utf8Validation.VERIFY` (`readStringRequireUtf8`,
  `writeStringNoTag` strict) et `FieldPresence.EXPLICIT` (via
  `@ProtobufField(explicitPresence=true)`).

## Décision

**M5 cible proto3 only** (`--maximum_edition PROTO3` côté runner Google).
Les ~80 tests Editions 2023 que le runner pourrait envoyer sont exclus par
ce flag — ils n'apparaissent pas dans le score 100% PASS actuel.

**M6 ouvrira la conformance Editions 2023** (cible PASS sur
`--maximum_edition 2023`) :

1. **`MessageEncoding.DELIMITED`** runtime : réactiver wire types 3/4
   (START_GROUP / END_GROUP). Notre `readTag` rejette actuellement ces wire
   types (cf. M5.6.2 BadTag) — il faudra un mode lenient quand la feature
   est active.

2. **`FieldPresence.LEGACY_REQUIRED`** : un field non-présent à la lecture
   doit échouer (sémantique proto2 `required`).

3. **`EnumType.CLOSED`** : valeur enum hors range = `MalformedProtobufException`
   au lieu du null silencieux actuel. Aussi côté codegen — `lookupEnumOrNull`
   doit devenir `lookupEnumOrThrow` en mode CLOSED.

4. **Édition 2023 `.proto` syntax** dans `SchemaResolver` : on supporte déjà
   `edition = "2023"` mais pas tous les features overrides au niveau field.

5. **TestAllTypesEdition2023** : record à créer (équivalent proto3/proto2),
   enregistré dans `KNOWN_TYPES`.

## Conséquences

**Positives** :
- M5 livre 100% PASS proto3 sans dépendance sur la maturité Editions 2023
  (qui est encore "experimental" côté Google).
- Le modèle `FeatureSet` est déjà en place — M6 sera une activation runtime,
  pas un design from scratch.

**Négatives** :
- Le score 100% PASS affiché ne couvre pas Editions 2023.
- Si l'écosystème gRPC migre massivement vers Editions 2023 avant M6, on
  aura un retard à rattraper.

## Alternatives écartées

| Option | Pourquoi écarté |
|---|---|
| Implémenter Editions 2023 en M5 | Triple le scope, retarde 100% proto3 d'~1 mois |
| Ne jamais supporter Editions | Spec Google officielle, à terme remplace proto2/proto3 |
| Failure-list les tests Editions | Confus — `--maximum_edition PROTO3` est l'option propre du runner |
