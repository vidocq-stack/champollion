# champollion-tck

Runner for the official Jakarta JSON-P 2.1 and Jakarta JSON-B 3.0 TCKs.

## Out of reactor

This module is intentionally **outside the reactor** Champollion (standalone Maven POM
Model 4.0.0) to work around an incompatibility between ShrinkWrap Maven Resolver 3.3 and
Model 4.1.0 POMs. Invoke it from the project root:

```bash
./run-official-tck-jsonp-2.1.sh    # JSON-P 2.1
./run-official-tck-jsonb-3.0.sh    # JSON-B 3.0
```

## Prerequisites

See [TCK.md](../TCK.md) for installing the official TCKs (non-public artifacts,
distributed by the Eclipse Foundation).

## Maven Profiles

| Profile | TCK |
|---|---|
| `-Pjsonp-tck` | Jakarta JSON Processing 2.1 (JUnit 5) |
| `-Pjsonb-tck` | Jakarta JSON Binding 3.0 (TestNG + Arquillian) |
