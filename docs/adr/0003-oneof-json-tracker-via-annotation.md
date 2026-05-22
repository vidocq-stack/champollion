# ADR 0003 — `oneofGroup` sur `@ProtobufField` + tracker JSON HashMap stateless

## Statut

Accepté — 2026-05-22.

## Contexte

Spec proto3 JSON §oneof : un payload JSON ne peut pas contenir plusieurs
property keys qui appartiennent au même groupe `oneof { ... }`. Le runner
Google teste cela via `Required.Proto3.JsonInput.OneofFieldDuplicate.*` et
attend `parse_error`.

Côté wire (binary) : pas de rejet — la spec autorise plusieurs valeurs d'un
même oneof, **last-wins** (la dernière valeur lue gagne, les autres slots du
même oneof sont clear).

Champollion doit donc :
- Côté JSON : rejet explicit du doublon.
- Côté wire : merge last-wins, clear des autres slots du même groupe.

## Décision

1. **`@ProtobufField.oneofGroup() String default ""`** — annotation enrichie.
   Tous les champs avec la même valeur non-vide forment un oneof groupe.
   ```java
   @ProtobufField(number = 111, type = UINT32, explicitPresence = true,
                  oneofGroup = "oneof_field") Integer oneof_uint32
   ```

2. **Côté JSON (`ProtobufJsonRuntime.readMessage`)** — `HashMap<String,String>
   seenOneofs` local au `readMessage`. Pour chaque property key non-null lu,
   `put(oneofGroup, key)` ; si l'oneof était déjà set, throw `IOException`
   avec mention du conflit. Les valeurs JSON `null` sont exclues du tracker
   (cohérent avec "null = absent" §JSON canonical).

3. **Côté wire (`RuntimeBinding.readMessage`)** — avant d'écrire un slot
   pour un field `oneofGroup` non-vide, clear tous les autres slots du même
   groupe (`slots[other.componentIndex] = null; hasValue[other.componentIndex] = false`).
   Implémente la sémantique "last-wins" de la spec.

4. **Pas de `ThreadLocal`, pas de `synchronized`** — le tracker est local
   au stack frame de `readMessage`, virtual-thread-safe par construction.

## Conséquences

**Positives** :
- Rejet JSON correct (couvre +5 tests Champollion + N tests conformance).
- Wire last-wins correct (couvre `ValidDataOneof.X.MultipleValuesForDifferentField`).
- Coût runtime nul (HashMap allocation ~zéro vu le nombre de fields oneof par
  message).
- Pas de modification des classes générées par APT — purement métadonnée
  annotation.

**Négatives** :
- 5e paramètre `@ProtobufField` à connaître (déjà 4 : number, type, packed,
  explicitPresence). Acceptable car oneof est rare.
- Le pattern matching exhaustif sur le oneof n'est pas représenté en Java —
  le consommateur doit checker chaque slot null. À industrialiser via sealed
  interface dans une évolution future M6+.

## Alternatives écartées

| Option | Pourquoi écarté |
|---|---|
| Sealed interface `Oneof_X permits ...` au niveau record | Trop intrusif, complique l'API record |
| Modeling `Optional<Choice>` avec sub-types | Java records ne supportent pas l'héritage |
| Tracker en `ScopedValue` thread-confined | Inutile — un parser appelle `readMessage` synchroniquement, pas de partage de tracker |
