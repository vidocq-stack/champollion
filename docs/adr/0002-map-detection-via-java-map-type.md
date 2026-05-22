# ADR 0002 — `Map<K,V>` détecté via type Java + `FieldType.MAP` + `mapKey`/`mapValue`

## Statut

Accepté — 2026-05-22.

## Contexte

Proto3 spec §maps encode `map<K,V> name = N;` comme un `repeated Entry { K key = 1; V value = 2; }`
length-delimited. Champollion doit représenter ces champs en Java sur les
records annotés (sans utiliser une classe `Entry` exposée au consommateur).

Ambiguïté Java → proto : un Java `Map<Integer, Integer>` peut être `map<int32, int32>`,
`map<uint32, uint32>`, `map<sint32, sint32>`, etc. Il faut un mécanisme pour
lever l'ambiguïté, comme `@ProtobufField(type=INT32 vs UINT32)` le fait pour
les scalaires.

## Décision

Trois ajouts coordonnés à l'annotation existante `@ProtobufField` :

1. **`FieldType.MAP`** — nouvelle valeur enum, `wireType = LEN`, non-packable.
2. **`mapKey()` / `mapValue()`** — `FieldType` default `STRING`, ignorés sauf
   si `type == MAP`. Lèvent l'ambiguïté côté key et value comme `type()` le
   fait pour les scalaires.
3. **Auto-détection du type Java** — `RuntimeBinding.buildPlan` introspect le
   `RecordComponent` : si `type == MAP` et le record component n'est pas
   `java.util.Map`, → `IllegalArgumentException`. Le `V` est extrait via
   `ParameterizedType.getActualTypeArguments()[1]` pour les cas `MESSAGE`
   (lookup du `BindingPlan` du sous-message).

Usage :

```java
@ProtobufField(number = 56, type = FieldType.MAP,
               mapKey = FieldType.INT32, mapValue = FieldType.INT32)
Map<Integer, Integer> map_int32_int32
```

## Conséquences

**Positives** :
- Cohérent avec le pattern `List<X>` déjà détecté (`resolveListElementType`).
- Pas de classe `MapEntry` exposée → API runtime propre.
- Annotation unique `@ProtobufField` reste l'unique métadonnée → simple à
  apprendre.
- `LinkedHashMap` côté default → roundtrip wire déterministe.

**Négatives** :
- 3 nouveaux paramètres annotation à mémoriser (`type=MAP, mapKey, mapValue`).
- Le static codegen APT ne supporte pas encore `FieldType.MAP` — TestAllTypesProto3
  a dû retirer `@ProtobufStatic` pour tomber sur le runtime reflectif. À
  industrialiser en M6.

## Alternatives écartées

| Option | Pourquoi écarté |
|---|---|
| Annotation séparée `@ProtobufMap(keyType, valueType)` | 3e annotation à mémoriser, gain minimal |
| Class `MapEntry<K,V>` exposée + `List<MapEntry<K,V>>` | Casse l'idiome `Map<K,V>` Java, viraille API |
| Inférer `mapKey`/`mapValue` du type Java (Integer → INT32) | Casse l'ambiguïté UINT32/SINT32/INT32 sur même Integer |
