/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.champollion.tests.architecture;

import java.util.Map;

import io.vidocq.archrules.Jdk;
import io.vidocq.archrules.Layer;
import io.vidocq.archrules.Packages;
import io.vidocq.archrules.PlatformThreads;
import io.vidocq.archrules.VidocqArchRules;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Champollion's own rules, which no scanner knows, as ArchUnit rules (Vidocq/vidocq#289). They come from the shared
 * {@code vidocq-archrules} library of {@code vidocq-parent}, set to Champollion's packages; each names the document
 * it comes from. A rule holds for whatever classes it is given: {@link ArchitectureRulesTest} gives it the shipped
 * classes, {@link ArchitectureRulesSelfTest} fixtures that break it.
 *
 * <p>The root package of each shipped module: {@code io.vidocq.champollion.spi} (champollion-api),
 * {@code io.vidocq.champollion.jsonp} (champollion-jsonp), {@code io.vidocq.champollion.jsonb}
 * (champollion-jsonb), {@code io.vidocq.champollion.protobuf} without {@code .codegen} (champollion-protobuf),
 * {@code io.vidocq.champollion.protobuf.codegen} (champollion-protobuf-codegen) and
 * {@code io.vidocq.champollion.codegen.apt} (champollion-codegen-apt). The two Maven plugins run on the build
 * machine and are not covered.
 */
final class ArchitectureRules {

    private static final String CLAUDE_CONSTRAINTS = "CLAUDE.md, Architecture Constraints";

    private static final String[] PROTOBUF_RUNTIME = {
        "io.vidocq.champollion.protobuf",
        "io.vidocq.champollion.protobuf.internal..",
        "io.vidocq.champollion.protobuf.wkt.."
    };

    /** champollion-api: the Jakarta JSON-P and JSON-B APIs it re-exposes, and the annotation API (static). */
    static final ArchRule API_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.champollion.spi.."},
            Packages.of(
                    Jdk.RUNTIME,
                    "jakarta.json..",
                    "jakarta.json.bind..",
                    "jakarta.annotation..",
                    "io.vidocq.champollion.spi.."),
            CLAUDE_CONSTRAINTS + ", zero dependency beyond Jakarta specs; module-info.java of champollion-api");

    /** champollion-jsonp: the JSON-P API and champollion-api; never champollion-jsonb. */
    static final ArchRule JSONP_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.champollion.jsonp.."},
            Packages.of(
                    Jdk.RUNTIME,
                    "jakarta.json..",
                    "io.vidocq.champollion.spi..",
                    "io.vidocq.champollion.jsonp.."),
            CLAUDE_CONSTRAINTS + ", zero dependency beyond Jakarta specs; module-info.java of champollion-jsonp");

    /**
     * champollion-jsonb: the JSON-P and JSON-B APIs, champollion-api and the parser internals champollion-jsonp
     * exports to it alone (parser pool, module-info.java of champollion-jsonp).
     */
    static final ArchRule JSONB_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.champollion.jsonb.."},
            Packages.of(
                    Jdk.RUNTIME,
                    "jakarta.json..",
                    "jakarta.json.bind..",
                    "io.vidocq.champollion.spi..",
                    "io.vidocq.champollion.jsonp.internal..",
                    "io.vidocq.champollion.jsonb.."),
            CLAUDE_CONSTRAINTS + ", zero dependency beyond Jakarta specs; module-info.java of champollion-jsonb");

    /** champollion-protobuf: the JSON-P API only, composed on champollion-jsonp through the provider. */
    static final ArchRule PROTOBUF_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            PROTOBUF_RUNTIME,
            Packages.of(
                    Jdk.RUNTIME,
                    "jakarta.json..",
                    "io.vidocq.champollion.protobuf",
                    "io.vidocq.champollion.protobuf.wkt..",
                    "io.vidocq.champollion.protobuf.internal.."),
            CLAUDE_CONSTRAINTS + ", zero dependency beyond Jakarta specs; module-info.java of champollion-protobuf");

    /** champollion-protobuf-codegen: an annotation processor, java.compiler and champollion-protobuf only. */
    static final ArchRule PROTOBUF_CODEGEN_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.champollion.protobuf.codegen.."},
            Packages.of(
                    Jdk.COMPILER,
                    "io.vidocq.champollion.protobuf",
                    "io.vidocq.champollion.protobuf.internal..",
                    "io.vidocq.champollion.protobuf.wkt..",
                    "io.vidocq.champollion.protobuf.codegen.."),
            "module-info.java of champollion-protobuf-codegen; workspace AGENTS.md, build-time tools");

    /**
     * champollion-codegen-apt: an annotation processor, java.compiler and the JSON-B API/SPI only, plus the
     * Compiler Tree API of {@code jdk.compiler} ({@code com.sun.source}) that its module descriptor reads
     * statically and that {@code ResponseEntities} uses to read method bodies at build time.
     */
    static final ArchRule APT_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.champollion.codegen.apt.."},
            Packages.of(
                    Jdk.COMPILER,
                    "com.sun.source..",
                    "jakarta.json.bind..",
                    "io.vidocq.champollion.jsonb.spi..",
                    "io.vidocq.champollion.codegen.apt.."),
            "module-info.java of champollion-codegen-apt; workspace AGENTS.md, build-time tools");

    /**
     * CLAUDE.md, constraint 2: jsonb builds on jsonp, never the reverse. The module descriptors: jsonp exports its
     * internals to jsonb alone, protobuf is independent of jsonb, each code generator reads one runtime module.
     */
    static final ArchRule LAYERS = VidocqArchRules.layers(
            CLAUDE_CONSTRAINTS + ", constraint 2; module-info.java files",
            Layer.named("API")
                    .in("io.vidocq.champollion.spi..")
                    .accessesNoLayer(),
            Layer.named("JSON-P")
                    .in("io.vidocq.champollion.jsonp..")
                    .accessibleOnlyBy("JSON-B"),
            Layer.named("JSON-B")
                    .in("io.vidocq.champollion.jsonb..")
                    .accessibleOnlyBy("JSON-B APT"),
            Layer.named("Protobuf")
                    .in(PROTOBUF_RUNTIME)
                    .accessibleOnlyBy("Protobuf codegen"),
            Layer.named("Protobuf codegen")
                    .in("io.vidocq.champollion.protobuf.codegen..")
                    .accessibleByNone(),
            Layer.named("JSON-B APT")
                    .in("io.vidocq.champollion.codegen.apt..")
                    .accessibleByNone());

    /**
     * Workspace AGENTS.md, dependency graph: the foundational bricks (chappe, vauban, champollion) have no
     * dependency between them.
     */
    static final ArchRule FOUNDATIONAL_BRICKS = VidocqArchRules.neverDependOn(
            new String[] {"io.vidocq.champollion.."},
            new String[] {"io.vidocq.chappe..", "io.vidocq.vauban.."},
            "workspace AGENTS.md, dependency graph");

    private static final String JSONB_TYPE_TOKEN = "java.lang.reflect.Type is the type token of the JSON-B API"
            + " (Jsonb.toJson(Object, Type), Jsonb.fromJson(String, Type)): it only reads generic type arguments,"
            + " never a member of a class";

    private static final String JSONB_RUNTIME_MODE = "the JSON-B runtime mode: introspection on first encounter of"
            + " a class that has no generated binding, cached, MethodHandles first (internals.adoc, Runtime mode;"
            + " README, runtime introspective fallback for non-recompilable types; CLAUDE.md, Two binding modes)";

    private static final String PROTOBUF_RUNTIME_MODE = "the protobuf runtime mode: a record annotated"
            + " @ProtobufMessage is introspected on first encounter, cached, until champollion-protobuf-codegen"
            + " generates its binding (Javadoc of RuntimeBinding and Descriptors; CLAUDE.md, Two binding modes)";

    /**
     * The documented reflective fallbacks, each with its reason. Nested classes share the entry of their
     * top-level class. They are the runtime modes that generated bindings supersede, and the JSON-B type token;
     * the shipped classes outside this list use no reflection.
     */
    static final Map<String, String> REFLECTIVE_FALLBACKS = Map.ofEntries(
            Map.entry("io.vidocq.champollion.jsonb.internal.RuntimeBindingRegistry", JSONB_RUNTIME_MODE),
            Map.entry("io.vidocq.champollion.jsonb.internal.RuntimeReadRegistry", JSONB_RUNTIME_MODE),
            Map.entry(
                    "io.vidocq.champollion.jsonb.internal.CdiResolver",
                    "the optional CDI lookup JSON-B 3.0 section 5 requires: the CDI API is not a dependency, so its"
                            + " classes are loaded by name from the bean's class loader, without initialisation,"
                            + " and a bean's @Inject members are read (CdiResolver Javadoc)"),
            Map.entry(
                    "io.vidocq.champollion.jsonb.internal.AccessFailures",
                    "the failure path of the JSON-B runtime mode: it recognises InaccessibleObjectException to tell"
                            + " the user which opens line to add (BUG-20261031-01); it reads no member"),
            Map.entry("io.vidocq.champollion.jsonb.internal.GenericTypes", JSONB_TYPE_TOKEN),
            Map.entry("io.vidocq.champollion.jsonb.internal.ChampollionJsonb", JSONB_TYPE_TOKEN),
            Map.entry("io.vidocq.champollion.jsonb.internal.ChampollionDeserializationContext", JSONB_TYPE_TOKEN),
            Map.entry("io.vidocq.champollion.protobuf.Descriptors", PROTOBUF_RUNTIME_MODE),
            Map.entry("io.vidocq.champollion.protobuf.internal.ProtobufJsonRuntime", PROTOBUF_RUNTIME_MODE),
            Map.entry("io.vidocq.champollion.protobuf.internal.RuntimeBinding", PROTOBUF_RUNTIME_MODE),
            Map.entry(
                    "io.vidocq.champollion.protobuf.internal.EnumValueMap",
                    "reads the @ProtoEnumValue annotation of an enum constant, in the protobuf runtime mode"),
            Map.entry(
                    "io.vidocq.champollion.protobuf.wkt.Any",
                    "Any packs and unpacks a message of a type known only at run time: it reads the"
                            + " @ProtobufMessage annotation of its class"),
            Map.entry(
                    "io.vidocq.champollion.protobuf.wkt.TypeRegistry",
                    "the registry of Any's types reads the @ProtobufMessage annotation of the registered class"));

    /** Workspace AGENTS.md, "Maximum static code generation"; CLAUDE.md, constraint 5. */
    static final ArchRule NO_REFLECTION = VidocqArchRules.noReflection(
            REFLECTIVE_FALLBACKS, "workspace AGENTS.md, static code generation; CLAUDE.md, constraint 5");

    /** Workspace AGENTS.md, "Virtual Threads everywhere for I/O". */
    static final ArchRule NO_PLATFORM_THREADS = VidocqArchRules.noPlatformThreads(
            PlatformThreads.NONE, "workspace AGENTS.md, Virtual Threads");

    /** CLAUDE.md, constraint 4: ScopedValue, not ThreadLocal. */
    static final ArchRule NO_THREAD_LOCAL = VidocqArchRules.noThreadLocal(
            "Champollion is virtual-thread-friendly: a ThreadLocal neither follows a task nor caches anything,"
                    + " ScopedValue carries context",
            CLAUDE_CONSTRAINTS + ", constraint 4");

    private ArchitectureRules() {
        // Rules only.
    }
}
