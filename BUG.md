# BUG.md — champollion

Suivi des bugs reproductibles. Convention : voir `../CLAUDE.md` (workspace root).

Statuts : `OPEN` → `INVESTIGATING` → `FIXED` (commit hash) → `CLOSED`.

---

## BUG-20260611-01 — JSON-B runtime: nested-POJO field aborts the enclosing object read inside a collection element

- **Date** : 2026-06-11
- **Statut** : OPEN
- **Module touché** : `champollion-jsonb` — `RuntimeReadRegistry.readObjectAndApply`
- **Symptôme** : `JsonbException: Expected object, got KEY_NAME` when deserializing
  `List<Order>` (via a parameterized `Type`) where `Order` has a nested-POJO
  field (`shipping: Address`) followed by more properties (`total`, `priority`).
  The same `Order` deserialized as the root value works. This is why the
  JSON-B read LARGE benchmark was historically `(n/c)` in `BENCH.md`.
- **Reproduction minimale** :
  ```java
  Type t = new TypeReference<List<Workloads.Order>>(){}.getType(); // jackson TypeReference or any ParameterizedType
  jsonb.fromJson(Workloads.largeJson(1), t); // throws "Expected object, got KEY_NAME"
  ```
- **Hypothèse de cause** : after a member is read, `readObjectAndApply` does
  `if (p.currentEvent() == Event.END_OBJECT) break;` (escape hatch for custom
  `JsonbDeserializer`s that consume up to the parent's END_OBJECT). A plain
  nested-POJO member also leaves `currentEvent() == END_OBJECT` (its own),
  so the enclosing object read breaks early; the unread keys (`total`...) then
  surface as `KEY_NAME` where the collection expects the next `START_OBJECT`.
  The guard cannot distinguish "child consumed its own END_OBJECT" from
  "custom deserializer overran into the parent's END_OBJECT" — it needs depth
  tracking (e.g. compare parser depth before/after `w.apply`) instead of the
  bare event-type check.
- **Investigations** :
  - 2026-06-11 : reproduced on `main` (pre-P12) and on the P12 lazy-tokenizer
    branch — identical failure, NOT a P12 regression (verified by stashing).
    Found while extending the JSON-B read benchmark coverage to LARGE.
