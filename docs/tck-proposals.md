# JSON-B TCK gap analysis for the Champollion bugs of 2026-11-01

Status: **draft, nothing posted**. Scope: the JSON-B runtime bugs logged in `BUG.md` on 2026-11-01
(BUG-20261101-01, -06, -07, -08) plus a suspected gap, "candidate-11" (`@JsonbPropertyOrder` ignored on
records by `RuntimeBindingRegistry.resolveRecord`). For each one: what the specification says, whether the
official TCK checks it, and, when it does not, a draft TCK test and a draft issue for
[jakartaee/jsonb-api](https://github.com/jakartaee/jsonb-api).

## Sources checked

| Source | Version | Notes |
|---|---|---|
| Specification | JSON-B 3.0, `jakarta-jsonb-spec-3.0.html` (jakarta.ee) | The spec never mentions Java records. |
| Specification draft | `spec/src/main/asciidoc/jsonb.adoc` on `main` (3.1.0-SNAPSHOT) | Adds "Java Class and Record" (§3.7), records in §4.2, §4.3, §4.8, §4.9. |
| API javadoc | `jakarta.json.bind-api` tags `3.0.0` and `3.0.1`, and `main` | Records first appear in the `main` javadoc ("Since 3.1"). |
| TCK binary | `jakarta.json.bind-tck-3.0.0.jar` (local M2) | 462 classes, compiled for Java 11 (class version 55), so **no records at all**. No class uses `java.util.concurrent` or `Thread`. No model class holds a field of its own type. |
| TCK sources | `tck/` on `main` of jakartaee/jsonb-api (353 files) | Record tests added in 2026 (PRs #381, #411, #433, #455, #461, #464, #486). |

Section numbers below are those of the published 3.0 spec unless marked "3.1 draft".

## Summary

| Bug | Spec-defined in 3.0? | 3.0 TCK | 3.1 TCK (`main`) | Verdict |
|---|---|---|---|---|
| -01 type holding itself (record/class, `Optional`/`List`/`Map`) | Yes for classes (§3.7, §3.4.3, §3.11); records only in 3.1 draft | Not covered | Not covered | **Propose test + issue** |
| -06 formats/nillable on record components; public field read without its format | Field part yes (§3.7.1, §4.8, §4.9); record part only in 3.1 draft | Not covered | Partly covered | **Propose test + issue** (remaining parts) |
| -07 `@JsonbNumberFormat` not thread-safe | Yes (`Jsonb` javadoc) | Not covered | Not covered | **Propose test + issue** |
| -08 JSON `null` for a primitive `@JsonbCreator` parameter | Partly: error type yes, expected outcome ambiguous | Not covered | Not covered | **Propose test + clarification issue** |
| candidate-11 `@JsonbPropertyOrder` on a record | No (records absent from 3.0) | Not covered | **Covered**: `RecordPropertyOrderTest.testCustomPropertyOrderOnRecord` | No proposal; fix Champollion before certifying 3.1 |
| -05 (APT) package/superclass annotations | Package level yes (§4.3.1, §4.8, §4.9); superclass inheritance no | Package level covered (`testNillablePackage`, `testDateFormatPackage`, `testNumberFormatPackage`) | same | Processor bug, no proposal |
| -09 (APT) static `@JsonbCreator` factory | Yes (§4.5) | Covered (`InstantiationCustomizationTest.testFactoryMethod`, `testConstructorPlusFactory`) | same | Processor bug, no proposal |
| -10 (APT) record implementing a `@JsonbTypeInfo` interface | Yes for classes (§3.8); records only in 3.1 draft | Covered for classes (`AnnotationTypeInfoTest`) | Covered for records (`RecordsPolymorphicMappingTest`) | Processor bug, no proposal |

---

## BUG-20261101-01: a type holding itself overflows the stack

Champollion threw `StackOverflowError` while building the mapping of a type that names itself through a
member: `record Entry(..., Optional<Entry> next)`, `List<Node>`, `Map<String, Tree>`, and a plain class holding
`List<Person>`. This is about recursive **types** with finite (acyclic) instances, not cyclic object graphs
(cyclic references are an open feature request, jsonb-api#72).

### Spec reference

- §3.7 "Java Class": the mapping rules apply to any class; nothing excludes a class whose property type is itself.
- §3.4.3: non-empty `Optional` instances are serialized "by retrieving their contained instance"; deserializing
  "return empty optional value for properties containing a null value".
- §3.11 "Collections": "Implementations MUST support the binding of the following collection interfaces".
- 3.1 draft §3.7: a nested record "follows the same default and customized mapping rules as a top-level record".

So for classes the expected behaviour (a finite tree round-trips) is spec-defined in 3.0; for records it is
defined only by the 3.1 draft.

### TCK coverage

- 3.0 TCK: **not covered**. A scan of every model class's fields (javap) finds no field whose type, or type
  argument, is its own class (the only hits are enum constants).
- 3.1 TCK on `main`: **not covered**. `RecordGenericAndNestedMappingTest` and `RecordGenericAndNestedCustomizationTest`
  nest a record in another record or in `List`/`Map`, never a record in itself.

### Verdict

Not covered. Easy to miss: an implementation that resolves member mappings eagerly passes the whole TCK and
crashes on the first linked list or tree.

### Draft TCK test (class version, valid for 3.0 and 3.1)

```java
package ee.jakarta.tck.json.bind.defaultmapping.classes;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

/**
 * @test
 * @sources SelfReferencingTypesMappingTest.java
 * @executeClass com.sun.ts.tests.jsonb.defaultmapping.classes.SelfReferencingTypesMappingTest
 **/
public class SelfReferencingTypesMappingTest {

    public static class TreeNode {
        public String name;
        public List<TreeNode> children;
    }

    public static class Chain {
        public String value;
        public Optional<Chain> next = Optional.empty();
    }

    public static class Directory {
        public String name;
        public Map<String, Directory> entries;
    }

    private final Jsonb jsonb = JsonbBuilder.create();

    /*
     * @testName: testListOfSelfType
     *
     * @assertion_ids: JSONB:SPEC:JSB-3.7-1; JSONB:SPEC:JSB-3.11-1
     *
     * @test_Strategy: Assert that a class holding a List of its own type is
     * serialized and deserialized as a finite tree.
     */
    @Test
    public void testListOfSelfType() {
        TreeNode leaf = new TreeNode();
        leaf.name = "leaf";
        TreeNode root = new TreeNode();
        root.name = "root";
        root.children = List.of(leaf);

        String json = jsonb.toJson(root);
        assertThat("Failed to serialize a class holding a List of its own type.",
                   json, matchesPattern("\\{\\s*\"children\"\\s*:\\s*\\[\\s*\\{\\s*\"name\"\\s*:\\s*\"leaf\"\\s*}\\s*]\\s*,"
                                                + "\\s*\"name\"\\s*:\\s*\"root\"\\s*}"));

        TreeNode read = jsonb.fromJson(json, TreeNode.class);
        assertThat("Failed to deserialize a class holding a List of its own type.", read.name, is("root"));
        assertThat("Failed to deserialize a class holding a List of its own type.", read.children.get(0).name, is("leaf"));
        assertThat("Failed to deserialize a class holding a List of its own type.", read.children.get(0).children, nullValue());
    }

    /*
     * @testName: testOptionalOfSelfType
     *
     * @assertion_ids: JSONB:SPEC:JSB-3.7-1; JSONB:SPEC:JSB-3.4.3-1
     *
     * @test_Strategy: Assert that a class holding an Optional of its own type
     * round-trips.
     */
    @Test
    public void testOptionalOfSelfType() {
        Chain second = new Chain();
        second.value = "b";
        Chain first = new Chain();
        first.value = "a";
        first.next = Optional.of(second);

        Chain read = jsonb.fromJson(jsonb.toJson(first), Chain.class);
        assertThat("Failed to round-trip a class holding an Optional of its own type.", read.value, is("a"));
        assertThat("Failed to round-trip a class holding an Optional of its own type.", read.next.get().value, is("b"));
        assertThat("Failed to round-trip a class holding an Optional of its own type.", read.next.get().next.isPresent(), is(false));
    }

    /*
     * @testName: testMapOfSelfType
     *
     * @assertion_ids: JSONB:SPEC:JSB-3.7-1; JSONB:SPEC:JSB-3.11-1
     *
     * @test_Strategy: Assert that a class holding a Map of its own type, used
     * through a generic root type, round-trips.
     */
    @Test
    public void testMapOfSelfType() {
        Directory child = new Directory();
        child.name = "bin";
        Directory root = new Directory();
        root.name = "/";
        root.entries = Map.of("bin", child);

        Type listType = new TestTypeToken<List<Directory>>() { }.getType();
        List<Directory> read = jsonb.fromJson(jsonb.toJson(List.of(root)), listType);
        assertThat("Failed to round-trip a class holding a Map of its own type.", read.get(0).name, is("/"));
        assertThat("Failed to round-trip a class holding a Map of its own type.", read.get(0).entries.get("bin").name, is("bin"));
    }
}
```

`TestTypeToken` stands for whatever type-token helper the TCK uses (the `main` record tests use a `TypeToken`
class); `Optional.empty()` read back from an absent property follows §3.4.3 and §3.14.1.

Record variant for the 3.1 TCK (append to `RecordGenericAndNestedMappingTest`):

```java
    public record Entry(String key, Optional<Entry> next) {}
    public record Node(String name, List<Node> children) {}

    /*
     * @testName: testSelfReferencingRecords
     *
     * @assertion_ids: JSONB:SPEC:JSB-3.7-1
     *
     * @test_Strategy: Assert that a record holding an Optional or a List of its
     * own type round-trips.
     */
    @Test
    public void testSelfReferencingRecords() {
        Entry entry = new Entry("a", Optional.of(new Entry("b", Optional.empty())));
        Entry readEntry = jsonb.fromJson(jsonb.toJson(entry), Entry.class);
        assertThat("A record holding an Optional of itself must round-trip.", readEntry, is(entry));

        Node node = new Node("root", List.of(new Node("leaf", List.of())));
        Node readNode = jsonb.fromJson(jsonb.toJson(node), Node.class);
        assertThat("A record holding a List of itself must round-trip.", readNode, is(node));
    }
```

### Draft issue

**Title:** TCK: no test binds a type that refers to itself (tree, linked list)

The TCK never maps a class or record whose property type, or a type argument of it, is the class itself
(`class TreeNode { List<TreeNode> children; }`, `record Entry(String key, Optional<Entry> next)`). Such types are
plain classes under §3.7 and use only the `Optional`/collection mappings of §3.4.3 and §3.11, so they must work.
An implementation that builds member mappings eagerly overflows the stack on them and still passes the whole
TCK (found in Champollion, a JSON-B 3.0 implementation). This is about finite instances only, not cyclic object
graphs (#72). Proposal: add `SelfReferencingTypesMappingTest` (List, Optional, Map of the self type, at the root
and under a generic root type) and a record variant in `RecordGenericAndNestedMappingTest`. Draft tests attached.

---

## BUG-20261101-06: format and null annotations on record components and public fields

Champollion's record components took `@JsonbDateFormat` from their accessor only (not from the record type or
its package), ignored `@JsonbNumberFormat` entirely, ignored the locale when reading, and ignored a record- or
package-level `@JsonbNillable`. A public field with no setter was written with its format but read without it.

### Spec reference

- §4.8 / §4.9 targets: "field", "getter/setter", "type", "parameter", "package".
- §4.8: "annotation applied to type target will override the same annotation applied to package target."
- §4.3.1: "Type level annotation overrides behavior set at the package level."
- §3.7.1: "If no matching setter method exists and the field is public, then direct field assignment is used."
- `JsonbNumberFormat` javadoc (3.0): "Annotation provides way how to set custom number format to field or JavaBean property."
- 3.1 draft §4.8 / §4.9 add the targets "record component", "record component accessor", "virtual attribute",
  and say an annotation on a component accessor "is used only for serialization".
- 3.1 draft §4.3: `@JsonbNillable` may annotate "type, package, field, record component".

The public-field part is spec-defined in 3.0. The record part is defined only by the 3.1 draft.

### TCK coverage

- 3.0 TCK: `DateFormatCustomizationTest` and `NumberFormatCustomizationTest` cover field, accessor, type and
  package levels on **classes with private fields and getters/setters** (javap: no public field in any of their
  models). No record. **Public field read with its format: not covered.**
- 3.1 TCK on `main`:
  - Covered: `@JsonbNumberFormat` on public fields without setters, read back
    (`NumberFormatCustomizationTest.testNumberFormatExplicitLocaleIndependentOfDefaultLocale`, PR #486).
  - Covered: formats on a redeclared component accessor (`RecordComponentAccessorCustomizationTest.testJsonbDateFormatOnRecordComponentAccessor`,
    `testJsonbNumberFormatOnRecordComponentAccessor`), formats and `@JsonbNillable` on virtual attributes, type-level
    `@JsonbNillable` affecting a **virtual attribute** (`RecordVirtualAttributeTest`).
  - **Not covered:** type-level `@JsonbDateFormat`/`@JsonbNumberFormat` on a record; package-level formats or
    `@JsonbNillable` reaching record components; `@JsonbNumberFormat`/`@JsonbDateFormat` on the component itself
    (applies to both directions, unlike the accessor); type-level `@JsonbNillable` on a null **component**;
    `@JsonbDateFormat` on a public field without setter, read back.

### Verdict

Partly covered (3.1 only). Propose the missing cases.

### Draft TCK test (records, for 3.1)

```java
package ee.jakarta.tck.json.bind.customizedmapping.records;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.annotation.JsonbDateFormat;
import jakarta.json.bind.annotation.JsonbNillable;
import jakarta.json.bind.annotation.JsonbNumberFormat;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;

/**
 * @test
 * @sources RecordFormatCustomizationTest.java
 * @executeClass com.sun.ts.tests.jsonb.customizedmapping.records.RecordFormatCustomizationTest
 **/
public class RecordFormatCustomizationTest {

    @JsonbDateFormat(value = "dd.MM.yyyy", locale = "en-US")
    public record TypeDated(LocalDate on) {}

    @JsonbNumberFormat(value = "#0.00", locale = "en-US")
    public record TypePriced(BigDecimal amount) {}

    public record ComponentPriced(@JsonbNumberFormat(value = "#,##0.00", locale = "de-DE") BigDecimal amount) {}

    public record ComponentDated(@JsonbDateFormat(value = "dd/MM/yyyy", locale = "en-US") LocalDate on) {}

    @JsonbNillable
    public record NillableType(String note) {}

    private final Jsonb jsonb = JsonbBuilder.create();

    /*
     * @testName: testDateFormatOnRecordType
     *
     * @assertion_ids: JSONB:SPEC:JSB-4.8-1
     *
     * @test_Strategy: Assert that a type-level JsonbDateFormat on a record applies
     * to its components when serializing and deserializing.
     */
    @Test
    public void testDateFormatOnRecordType() {
        String json = jsonb.toJson(new TypeDated(LocalDate.of(1862, 4, 3)));
        assertThat("Type-level JsonbDateFormat must apply to record components.",
                   json, matchesPattern("\\{\\s*\"on\"\\s*:\\s*\"03\\.04\\.1862\"\\s*}"));
        assertThat("Type-level JsonbDateFormat must apply when reading record components.",
                   jsonb.fromJson("{\"on\":\"03.04.1862\"}", TypeDated.class).on(), is(LocalDate.of(1862, 4, 3)));
    }

    /*
     * @testName: testNumberFormatOnRecordType
     *
     * @assertion_ids: JSONB:SPEC:JSB-4.9-1
     *
     * @test_Strategy: Assert that a type-level JsonbNumberFormat on a record applies
     * to its components.
     */
    @Test
    public void testNumberFormatOnRecordType() {
        String json = jsonb.toJson(new TypePriced(new BigDecimal("1.5")));
        assertThat("Type-level JsonbNumberFormat must apply to record components.",
                   json, matchesPattern("\\{\\s*\"amount\"\\s*:\\s*\"1\\.50\"\\s*}"));
        assertThat("Type-level JsonbNumberFormat must apply when reading record components.",
                   jsonb.fromJson("{\"amount\":\"1.50\"}", TypePriced.class).amount().compareTo(new BigDecimal("1.5")), is(0));
    }

    /*
     * @testName: testFormatsOnRecordComponent
     *
     * @assertion_ids: JSONB:SPEC:JSB-4.8-1; JSONB:SPEC:JSB-4.9-1
     *
     * @test_Strategy: Assert that JsonbNumberFormat and JsonbDateFormat placed on a
     * record component apply in both directions, with the annotation's locale.
     */
    @Test
    public void testFormatsOnRecordComponent() {
        String priced = jsonb.toJson(new ComponentPriced(new BigDecimal("1234.5")));
        assertThat("Component-level JsonbNumberFormat must apply when serializing.",
                   priced, matchesPattern("\\{\\s*\"amount\"\\s*:\\s*\"1\\.234,50\"\\s*}"));
        assertThat("Component-level JsonbNumberFormat must apply, with its locale, when deserializing.",
                   jsonb.fromJson("{\"amount\":\"1.234,50\"}", ComponentPriced.class).amount()
                           .compareTo(new BigDecimal("1234.5")), is(0));

        String dated = jsonb.toJson(new ComponentDated(LocalDate.of(2026, 11, 1)));
        assertThat("Component-level JsonbDateFormat must apply when serializing.",
                   dated, matchesPattern("\\{\\s*\"on\"\\s*:\\s*\"01/11/2026\"\\s*}"));
        assertThat("Component-level JsonbDateFormat must apply when deserializing.",
                   jsonb.fromJson("{\"on\":\"01/11/2026\"}", ComponentDated.class).on(), is(LocalDate.of(2026, 11, 1)));
    }

    /*
     * @testName: testNillableRecordTypeAppliesToComponents
     *
     * @assertion_ids: JSONB:SPEC:JSB-4.3.1-1
     *
     * @test_Strategy: Assert that a type-level JsonbNillable on a record writes a
     * null component as an explicit JSON null.
     */
    @Test
    public void testNillableRecordTypeAppliesToComponents() {
        assertThat("Type-level JsonbNillable must apply to record components.",
                   jsonb.toJson(new NillableType(null)), matchesPattern("\\{\\s*\"note\"\\s*:\\s*null\\s*}"));
    }
}
```

Package-level cases need a `package-info.java` and their own model package
(`customizedmapping/records/model/formatted/package-info.java` carrying `@JsonbDateFormat`, `@JsonbNumberFormat`,
`@JsonbNillable`, with one record in it), mirroring `testDateFormatPackage`, `testNumberFormatPackage` and
`testNillablePackage`.

### Draft TCK test (public field without setter, valid for 3.0)

Append to `DateFormatCustomizationTest` (3.1 already covers the number-format counterpart):

```java
    public static class PublicFieldDateContainer {
        @JsonbDateFormat(value = "dd.MM.yyyy", locale = "en-US")
        public LocalDate on;
    }

    /*
     * @testName: testDateFormatOnPublicFieldWithoutAccessors
     *
     * @assertion_ids: JSONB:SPEC:JSB-4.8-1; JSONB:SPEC:JSB-3.7.1-1
     *
     * @test_Strategy: Assert that JsonbDateFormat on a public field with no getter
     * or setter is applied both when the field is read and when it is assigned.
     */
    @Test
    public void testDateFormatOnPublicFieldWithoutAccessors() {
        PublicFieldDateContainer container = new PublicFieldDateContainer();
        container.on = LocalDate.of(1862, 4, 3);
        String json = jsonb.toJson(container);
        assertThat("Failed to apply JsonbDateFormat on a public field when serializing.",
                   json, matchesPattern("\\{\\s*\"on\"\\s*:\\s*\"03\\.04\\.1862\"\\s*}"));
        assertThat("Failed to apply JsonbDateFormat on a public field when deserializing.",
                   jsonb.fromJson(json, PublicFieldDateContainer.class).on, is(LocalDate.of(1862, 4, 3)));
    }
```

### Draft issue

**Title:** TCK: format and nillable annotations on record components and record types are not tested

The 3.1 draft lists "record component" among the targets of `@JsonbDateFormat`, `@JsonbNumberFormat` and
`@JsonbNillable` (§4.3, §4.8, §4.9), and type/package levels apply to the properties of the type. The TCK on
`main` tests these annotations on component accessors (serialization only) and on virtual attributes, but not:
a type-level `@JsonbDateFormat`/`@JsonbNumberFormat` on a record; a format on the component itself, in both
directions and with its locale; a type- or package-level `@JsonbNillable` reaching a null component; the
package level for records in general. A Champollion bug (component formats only looked up on the accessor,
number format ignored, nillable type ignored) passed every existing test. Also missing in 3.0 and 3.1:
`@JsonbDateFormat` on a public field without setter, read back (§3.7.1 + §4.8). Draft tests attached.

---

## BUG-20261101-07: a `@JsonbNumberFormat` shared across threads

Champollion built one `java.text.DecimalFormat` per annotated property and used it from every thread;
`DecimalFormat` is not thread-safe, so concurrent round trips produced wrong numbers.

### Spec reference

- `Jsonb` javadoc (3.0.0): "All the methods in this class are safe for use by multiple concurrent threads."
- §4.9: number patterns follow `java.text.DecimalFormat` (javadoc: "The pattern format is specified in DecimalFormat"),
  a class the JDK documents as not synchronized, which makes this an easy trap.

Spec-defined (API contract).

### TCK coverage

- 3.0 TCK: **not covered**; no class in the jar references `java.util.concurrent` or `java.lang.Thread`.
- 3.1 TCK on `main`: **not covered**; only the signature-test driver mentions threads.

### Verdict

Not covered. A concurrency test is probabilistic: it can catch the bug, never prove its absence. It is still
worth having because the failure mode (silently wrong data) is serious and common (`DecimalFormat`,
`SimpleDateFormat`).

### Draft TCK test

```java
package ee.jakarta.tck.json.bind.api.jsonb;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.annotation.JsonbDateFormat;
import jakarta.json.bind.annotation.JsonbNumberFormat;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * @test
 * @sources JsonbConcurrencyTest.java
 * @executeClass com.sun.ts.tests.jsonb.api.jsonb.JsonbConcurrencyTest
 **/
public class JsonbConcurrencyTest {

    private static final int THREADS = 16;
    private static final int ITERATIONS = 2_000;

    public static class Formatted {
        @JsonbNumberFormat(value = "#,##0.00", locale = "en-US")
        public double amount;

        @JsonbDateFormat(value = "dd.MM.yyyy", locale = "en-US")
        public java.time.LocalDate day;
    }

    /*
     * @testName: testCustomFormatsAreSafeForConcurrentUse
     *
     * @assertion_ids: JSONB:JAVADOC:Jsonb-threadsafety; JSONB:SPEC:JSB-4.9-1
     *
     * @test_Strategy: Use one Jsonb instance from several threads at once to write
     * and read a type whose properties carry JsonbNumberFormat and JsonbDateFormat,
     * each thread with its own values, and assert that every round trip returns the
     * values written.
     */
    @Test
    public void testCustomFormatsAreSafeForConcurrentUse() throws Exception {
        Jsonb jsonb = JsonbBuilder.create();
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                int thread = t;
                tasks.add(() -> {
                    int wrong = 0;
                    for (int i = 0; i < ITERATIONS; i++) {
                        Formatted value = new Formatted();
                        value.amount = thread * 1_000_000 + i + 0.25;
                        value.day = java.time.LocalDate.of(2000 + thread, 1 + i % 12, 1 + i % 28);
                        Formatted read = jsonb.fromJson(jsonb.toJson(value), Formatted.class);
                        if (read.amount != value.amount || !read.day.equals(value.day)) {
                            wrong++;
                        }
                    }
                    return wrong;
                });
            }
            int wrong = 0;
            for (Future<Integer> result : executor.invokeAll(tasks)) {
                wrong += result.get();
            }
            assertThat("Concurrent use of one Jsonb instance produced wrong values.", wrong, is(0));
        } finally {
            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.MINUTES);
            jsonb.close();
        }
    }
}
```

### Draft issue

**Title:** TCK: the thread safety promised by the `Jsonb` javadoc is not tested

The `Jsonb` javadoc states that all its methods "are safe for use by multiple concurrent threads", but no TCK
test uses a `Jsonb` instance from more than one thread. The obvious trap is the formats: `@JsonbNumberFormat`
and `@JsonbDateFormat` are naturally implemented with `java.text.DecimalFormat` or `SimpleDateFormat`, which are
not thread-safe; caching one instance per property silently produces wrong numbers under load. Champollion had
exactly this bug and passed the whole TCK. Proposal: a `JsonbConcurrencyTest` that round-trips a formatted type
from several threads sharing one `Jsonb` and asserts every value. The test is probabilistic, but cheap (about
32,000 round trips) and catches the shared-`DecimalFormat` case reliably in practice. Draft test attached.

---

## BUG-20261101-08: JSON `null` for a primitive `@JsonbCreator` parameter

`fromJson("{\"number\":null}", Account.class)` with `@JsonbCreator Account(@JsonbProperty("number") long number)`
leaked a raw `IllegalArgumentException` from reflection. Champollion now throws `JsonbException`.

### Spec reference

- `Jsonb` javadoc (3.0.0): a deserialization failure is reported "by throwing JsonbException".
- `Jsonb.fromJson` javadoc: "@throws JsonbException If an unexpected error occurs during deserialization."
- §3.2: implementations "SHOULD also report an error" when a JSON value cannot be represented by the Java type.
- §4.5.1: "Primitive types cannot accept null values"; Table 3 gives `0L` etc. for **absent** optional parameters.

What is defined: whatever fails must fail as `JsonbException`, never a raw `IllegalArgumentException`. What is
**not** defined: whether an explicit JSON `null` for a primitive creator parameter is an error (§3.2) or takes the
Table 3 default like an absent property (§4.5.1). Both readings are defensible; Champollion chose the error.

### TCK coverage

- 3.0 TCK: **not covered**. `OptionalCreatorParametersTest.testPrimitiveTypesDefaultValues` reads `{ }` (absent
  properties) into `PrimitiveTypeContainer`; no test sends an explicit `null`.
- 3.1 TCK on `main`: **not covered** (no `fromJson` with a `null` value in any creator or instantiation test).

### Verdict

Not covered, and the expected result is ambiguous. Propose a test that accepts both defensible outcomes and rejects
anything else, plus a request to settle the ambiguity in the spec.

### Draft TCK test

```java
// appended to ee.jakarta.tck.json.bind.customizedmapping.instantiation.OptionalCreatorParametersTest

    /*
     * @testName: testExplicitNullForPrimitiveCreatorParameter
     *
     * @assertion_ids: JSONB:SPEC:JSB-4.5.1-1; JSONB:SPEC:JSB-3.2-1
     *
     * @test_Strategy: Deserialize an explicit JSON null into every primitive
     * JsonbCreator parameter. The result must be either the Table 3 default value
     * or a JsonbException; no other exception may escape.
     */
    @Test
    public void testExplicitNullForPrimitiveCreatorParameter() {
        String json = "{ \"byteType\":null, \"shortType\":null, \"intType\":null, \"longType\":null,"
                + " \"floatType\":null, \"doubleType\":null, \"charType\":null, \"booleanType\":null }";
        PrimitiveTypeContainer unmarshalledObject;
        try {
            unmarshalledObject = jsonb.fromJson(json, PrimitiveTypeContainer.class);
        } catch (JsonbException expected) {
            return; // reported as a deserialization error, as Jsonb's javadoc requires
        } catch (RuntimeException unexpected) {
            throw new AssertionError("A JSON null for a primitive creator parameter must give the default value "
                                             + "or a JsonbException, got " + unexpected, unexpected);
        }
        assertThat("A JSON null must give the default byte value", unmarshalledObject.getByteType(), is((byte) 0));
        assertThat("A JSON null must give the default short value", unmarshalledObject.getShortType(), is((short) 0));
        assertThat("A JSON null must give the default int value", unmarshalledObject.getIntType(), is(0));
        assertThat("A JSON null must give the default long value", unmarshalledObject.getLongType(), is(0L));
        assertThat("A JSON null must give the default float value", unmarshalledObject.getFloatType(), is(0.0F));
        assertThat("A JSON null must give the default double value", unmarshalledObject.getDoubleType(), is(0.0));
        assertThat("A JSON null must give the default char value", unmarshalledObject.getCharType(), is('\u0000'));
        assertThat("A JSON null must give the default boolean value", unmarshalledObject.getBooleanType(), is(false));
    }
```

If the spec settles on one outcome, the test keeps only that branch.

### Draft issue

**Title:** Spec + TCK: explicit JSON `null` for a primitive `@JsonbCreator` parameter

§4.5.1 says an absent optional creator parameter of primitive type gets the Table 3 default ("Primitive types
cannot accept null values"). It does not say what happens when the property is present with an explicit `null`:
take the same default, or report an error under §3.2 (`JsonbException`)? Implementations differ, and at least one
(Champollion, before a fix) let the reflective `IllegalArgumentException` escape, which no reading allows: the
`Jsonb` javadoc requires deserialization failures to be reported as `JsonbException`. Proposal: (1) state the rule
in §4.5.1, (2) add a TCK test sending `null` for every primitive parameter of `PrimitiveTypeContainer`; the draft
test accepts either the default or a `JsonbException` until (1) is decided. The same question applies to
records' canonical constructors in 3.1.

---

## Candidate-11: `@JsonbPropertyOrder` on a record

Suspected Champollion gap: `RuntimeBindingRegistry.resolveRecord` never calls `applyPropertyOrder`.

### Spec reference

- 3.0: §4.2 defines `@JsonbPropertyOrder` per type ("only for one specific type"), but the 3.0 spec and javadoc
  never mention records, and the 3.0 javadoc target is any type. Not explicitly defined for records in 3.0.
- 3.1 draft §4.2: names "MUST correspond to the original property or record component names".
- `JsonbPropertyOrder` javadoc on `main`: "record: ordering applies to all serializable record components".

### TCK coverage

- 3.0 TCK: not covered (no records, Java 11 bytecode).
- 3.1 TCK on `main`: **covered** by `ee.jakarta.tck.json.bind.customizedmapping.propertyorder.RecordPropertyOrderTest`
  (PR #464, merged 2026-09-24): `testCustomPropertyOrderOnRecord`, `testPropertyOrderOriginalNamesOnRecord`
  (model `NamingStrategyOrderedRecord`, `@JsonbPropertyOrder({"longInstance","intInstance","stringInstance"})`),
  plus the strategy tests on `SimpleOrderRecord`.

### Verdict

Covered by the upcoming 3.1 TCK: no proposal. Champollion will fail `RecordPropertyOrderTest` when it targets
3.1, so this is a Champollion fix, worth a `BUG.md` entry. Small remaining gap, not worth an issue alone: no
**partial** order on a record (`testCustomPartialPropertyOrder` exists for classes only).

---

## Processor-only bugs (-05, -09, -10)

These are bugs of Champollion's annotation processor, which diverged from Champollion's own runtime. The
expected runtime behaviour:

- **-05** package/superclass annotations: package level is spec-defined (§4.3.1, §4.8, §4.9) and covered by the
  3.0 TCK (`testNillablePackage`, `testDateFormatPackage`, `testNumberFormatPackage`); inheritance from a
  **superclass** annotation is not defined by the spec.
- **-09** static `@JsonbCreator` factory: spec-defined (§4.5, "custom constructor or static factory method"),
  covered by `InstantiationCustomizationTest.testFactoryMethod` and `testConstructorPlusFactory`.
- **-10** `@JsonbTypeInfo` on an interface a record implements: spec-defined for classes (§3.8), covered by
  `AnnotationTypeInfoTest`; for records only by the 3.1 draft, covered by `RecordsPolymorphicMappingTest`.

## Open points

- The `JSONB:SPEC:...` assertion ids in the drafts follow the existing TCK pattern but are guesses; the maintainers
  assign them.
- Whether the 3.1 additions are in the `3.1.0-M1`/`M2` tags was not checked; the analysis uses `main`.
- jsonb-api#72 (cyclic references) is related to -01 but different; the issue text says so.
