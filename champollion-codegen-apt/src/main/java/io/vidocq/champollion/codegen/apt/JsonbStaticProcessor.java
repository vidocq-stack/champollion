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
package io.vidocq.champollion.codegen.apt;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import io.vidocq.champollion.jsonb.spi.JsonbStatic;
import jakarta.json.bind.annotation.JsonbNillable;
import jakarta.json.bind.annotation.JsonbProperty;
import jakarta.json.bind.annotation.JsonbTransient;

import javax.lang.model.element.PackageElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code @JsonbStatic} annotation processor: generates, for each annotated record,
 * a source file {@code <FQN>$$Binding.java} that implements
 * {@code io.vidocq.champollion.jsonb.spi.JsonbBinding<TargetType>} and accumulates
 * the binding list in
 * {@code META-INF/services/io.vidocq.champollion.jsonb.spi.JsonbBinding}.
 *
 * <p>M5.2: limited to records whose components are primitive types or
 * {@link String}. Containers (List/Map/Optional/Array) will be added in M5.3.</p>
 */
@SupportedAnnotationTypes("io.vidocq.champollion.jsonb.spi.JsonbStatic")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class JsonbStaticProcessor extends AbstractProcessor {

    private static final String SERVICE_FILE =
            "META-INF/services/io.vidocq.champollion.jsonb.spi.JsonbBinding";

    private final Set<String> generatedBindings = new LinkedHashSet<>();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
        if (annotations.isEmpty()) {
            // Round final : on flush le ServiceLoader.
            if (env.processingOver() && !generatedBindings.isEmpty()) {
                writeServicesFile();
            }
            return false;
        }
        for (TypeElement annotation : annotations) {
            for (Element annotated : env.getElementsAnnotatedWith(annotation)) {
                if (annotated.getKind() != ElementKind.RECORD) {
                    error(annotated, "@JsonbStatic only supported on records (M5.2). Got: " + annotated.getKind());
                    continue;
                }
                processRecord((TypeElement) annotated);
            }
        }
        if (env.processingOver() && !generatedBindings.isEmpty()) {
            writeServicesFile();
        }
        return true;
    }

    private void processRecord(TypeElement record) {
        String simpleName = record.getSimpleName().toString();
        PackageElement pkg = (PackageElement) record.getEnclosingElement();
        // Nested types: we must reconstruct the binary name with '$' but the source
        // file has a name derived from the simple name. For M5.2, only top-level types are accepted.
        if (!(pkg instanceof PackageElement)) {
            error(record, "@JsonbStatic only supported on top-level records in M5.2");
            return;
        }
        String pkgName = pkg.getQualifiedName().toString();
        String bindingSimple = simpleName + "$$Binding";
        String bindingFqn = pkgName.isEmpty() ? bindingSimple : pkgName + "." + bindingSimple;
        String targetFqn = record.getQualifiedName().toString();

        List<? extends RecordComponentElement> comps = record.getRecordComponents();

        // Validation: components supported in M5.2.
        for (var c : comps) {
            if (!isSupportedComponentType(c.asType())) {
                error(record,
                        "@JsonbStatic component type not supported in M5.2: " + c.asType()
                                + " (only primitives + String supported — containers in M5.3)");
                return;
            }
        }

        // Single emission mode: Java source compiled by javac (workspace APT-first
        // rule, codegen audit CG-03). The former Class-File fast path for
        // primitives+String records was removed — readable, debuggable output wins
        // over saving one javac round trip.
        try (Writer w = processingEnv.getFiler().createSourceFile(bindingFqn, record).openWriter();
             PrintWriter pw = new PrintWriter(w)) {
            emit(pw, pkgName, simpleName, bindingSimple, targetFqn, comps);
        } catch (IOException ex) {
            error(record, "Failed to generate binding: " + ex.getMessage());
            return;
        }
        generatedBindings.add(bindingFqn);
    }

    /**
     * M5.5: supported components = primitives, String, List&lt;X&gt;, Optional&lt;X&gt;,
     * primitive arrays, String[], Map&lt;String,X&gt;, and records annotated
     * {@code @JsonbStatic} (referenced via {@code new <X>$$Binding()}).
     */
    private static boolean isSupportedComponentType(TypeMirror tm) {
        return switch (tm.getKind()) {
            case INT, LONG, DOUBLE, FLOAT, SHORT, BYTE, BOOLEAN -> true;
            case DECLARED -> {
                DeclaredType dt = (DeclaredType) tm;
                String fqn = dt.asElement().toString();
                if ("java.lang.String".equals(fqn)) yield true;
                if (isEnum(dt)) yield true;
                if (isStaticRecord(dt)) yield true;
                if ("java.util.List".equals(fqn) || "java.util.Optional".equals(fqn)) {
                    var args = dt.getTypeArguments();
                    if (args.size() != 1) yield false;
                    TypeMirror inner = args.get(0);
                    yield isLeafType(inner) || isInnerComplex(inner);
                }
                if ("java.util.Map".equals(fqn)) {
                    var args = dt.getTypeArguments();
                    if (args.size() != 2) yield false;
                    TypeMirror keyT = args.get(0);
                    if (keyT.getKind() != javax.lang.model.type.TypeKind.DECLARED
                            || !"java.lang.String".equals(((DeclaredType) keyT).asElement().toString())) {
                        yield false;
                    }
                    TypeMirror valT = args.get(1);
                    yield isLeafType(valT) || isInnerComplex(valT);
                }
                yield false;
            }
            case ARRAY -> {
                TypeMirror comp = ((ArrayType) tm).getComponentType();
                yield switch (comp.getKind()) {
                    case INT, LONG, DOUBLE, BOOLEAN -> true;
                    case DECLARED -> "java.lang.String".equals(comp.toString());
                    default -> false;
                };
            }
            default -> false;
        };
    }

    /** "Leaf" types allowed inside containers: boxed primitives, String. */
    private static boolean isLeafType(TypeMirror tm) {
        if (tm.getKind() == javax.lang.model.type.TypeKind.DECLARED) {
            String fqn = ((DeclaredType) tm).asElement().toString();
            return switch (fqn) {
                case "java.lang.String", "java.lang.Integer", "java.lang.Long",
                     "java.lang.Double", "java.lang.Float", "java.lang.Short",
                     "java.lang.Byte", "java.lang.Boolean" -> true;
                default -> false;
            };
        }
        return false;
    }

    /** True if the declared type references a record annotated {@code @JsonbStatic}. */
    private static boolean isStaticRecord(DeclaredType dt) {
        var elem = dt.asElement();
        return elem.getKind() == javax.lang.model.element.ElementKind.RECORD
                && elem.getAnnotation(JsonbStatic.class) != null;
    }

    /** True if the declared type is an enum (any enum — no annotation needed). */
    private static boolean isEnum(DeclaredType dt) {
        return dt.asElement().getKind() == javax.lang.model.element.ElementKind.ENUM;
    }

    /**
     * True if {@code tm} is an acceptable complex type as an internal container
     * parameter or Map value: enum or {@code @JsonbStatic} record.
     */
    private static boolean isInnerComplex(TypeMirror tm) {
        if (tm.getKind() != javax.lang.model.type.TypeKind.DECLARED) return false;
        DeclaredType dt = (DeclaredType) tm;
        return isEnum(dt) || isStaticRecord(dt);
    }

    /** Binary name of the generated binding for a static record: {@code <FQN>$$Binding}. */
    private static String bindingFqnOf(DeclaredType dt) {
        return ((TypeElement) dt.asElement()).getQualifiedName().toString() + "$$Binding";
    }

    // ============================================================
    // JSON-B customization annotations (component or its accessor) —
    // ported verbatim from the former BindingBytecodeEmitter (CG-03).
    // ============================================================

    /** JSON name of a component: @JsonbProperty value if present, otherwise simpleName. */
    private static String jsonbName(RecordComponentElement c) {
        var prop = c.getAnnotation(JsonbProperty.class);
        if (prop != null && !prop.value().isEmpty()) return prop.value();
        var accessor = c.getAccessor();
        if (accessor != null) {
            var pa = accessor.getAnnotation(JsonbProperty.class);
            if (pa != null && !pa.value().isEmpty()) return pa.value();
        }
        return c.getSimpleName().toString();
    }

    /** True if the component or its accessor carry {@code @JsonbTransient}. */
    private static boolean isJsonbTransient(RecordComponentElement c) {
        if (c.getAnnotation(JsonbTransient.class) != null) return true;
        var accessor = c.getAccessor();
        return accessor != null && accessor.getAnnotation(JsonbTransient.class) != null;
    }

    /** True if the component or its accessor carry {@code @JsonbNillable}. */
    private static boolean isJsonbNillable(RecordComponentElement c) {
        if (c.getAnnotation(JsonbNillable.class) != null) return true;
        var accessor = c.getAccessor();
        return accessor != null && accessor.getAnnotation(JsonbNillable.class) != null;
    }

    /**
     * Pre-quotes a member name for {@code RawJsonKeyWriter.writeKeyRawWithColon}
     * (RFC 8259 section 7 escaping done once at build time).
     * Example: {@code name} becomes {@code "name":}.
     */
    private static String preQuoteJson(String name) {
        var sb = new StringBuilder(name.length() + 3).append('"');
        appendEscaped(sb, name);
        return sb.append('"').append(':').toString();
    }

    /** Java string literal (escaped) for emission into generated source. */
    private static String lit(String value) {
        var sb = new StringBuilder(value.length() + 2).append('"');
        appendEscaped(sb, value);
        return sb.append('"').toString();
    }

    /** Shared escaping — valid for both RFC 8259 keys and Java string literals. */
    private static void appendEscaped(StringBuilder sb, String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
    }

    private void emit(PrintWriter pw, String pkg, String recordName, String bindingName,
                      String targetFqn, List<? extends RecordComponentElement> comps) {
        if (!pkg.isEmpty()) {
            pw.println("package " + pkg + ";");
            pw.println();
        }
        pw.println("import io.vidocq.champollion.jsonb.spi.JsonbBinding;");
        pw.println("import jakarta.json.stream.JsonGenerator;");
        pw.println("import jakarta.json.stream.JsonParser;");
        pw.println();
        pw.println("/** Generated by champollion-codegen-apt — do not edit. */");
        pw.println("public final class " + bindingName + " implements JsonbBinding<" + targetFqn + "> {");
        pw.println();
        pw.println("    @Override public Class<" + targetFqn + "> type() { return " + targetFqn + ".class; }");
        pw.println();

        // ===== write =====
        pw.println("    @Override");
        pw.println("    public void write(JsonGenerator g, " + targetFqn + " value) {");
        pw.println("        if (value == null) { g.writeNull(); return; }");
        pw.println("        g.writeStartObject();");
        for (var c : comps) {
            if (isJsonbTransient(c)) continue;   // @JsonbTransient: no write
            emitWriteComponent(pw, c, jsonbName(c), isJsonbNillable(c));
        }
        pw.println("        g.writeEnd();");
        pw.println("    }");
        pw.println();

        // ===== read =====
        pw.println("    @Override");
        pw.println("    public " + targetFqn + " read(JsonParser p) {");
        pw.println("        JsonParser.Event e = p.next();");
        pw.println("        if (e == JsonParser.Event.VALUE_NULL) return null;");
        pw.println("        if (e != JsonParser.Event.START_OBJECT) {");
        pw.println("            throw new IllegalStateException(\"Expected START_OBJECT, got \" + e);");
        pw.println("        }");
        for (var c : comps) {
            emitReadDeclaration(pw, c);
        }
        pw.println("        while ((e = p.next()) != JsonParser.Event.END_OBJECT) {");
        pw.println("            if (e != JsonParser.Event.KEY_NAME) {");
        pw.println("                throw new IllegalStateException(\"Expected KEY_NAME, got \" + e);");
        pw.println("            }");
        pw.println("            String __key = p.getString();");
        pw.println("            JsonParser.Event __ev = p.next();");
        pw.println("            switch (__key) {");
        for (var c : comps) {
            if (isJsonbTransient(c)) continue;   // @JsonbTransient: no read mapping
            String name = c.getSimpleName().toString();
            pw.println("                case \"" + jsonbName(c) + "\" -> {");
            emitReadComponent(pw, c, "_" + name);
            pw.println("                }");
        }
        pw.println("                default -> {");
        pw.println("                    if (__ev == JsonParser.Event.START_OBJECT) p.skipObject();");
        pw.println("                    else if (__ev == JsonParser.Event.START_ARRAY) p.skipArray();");
        pw.println("                }");
        pw.println("            }");
        pw.println("        }");
        pw.print("        return new " + targetFqn + "(");
        for (int i = 0; i < comps.size(); i++) {
            if (i > 0) pw.print(", ");
            pw.print("_" + comps.get(i).getSimpleName());
        }
        pw.println(");");
        pw.println("    }");
        pw.println();
        // Raw-key fast path (P4): pre-quoted RFC 8259 keys when the generator is
        // champollion's (same optimization the former bytecode emitter applied).
        pw.println("    private static void __writeKey(JsonGenerator g, String preQuotedWithColon, String name) {");
        pw.println("        if (g instanceof io.vidocq.champollion.spi.RawJsonKeyWriter __rw) __rw.writeKeyRawWithColon(preQuotedWithColon);");
        pw.println("        else g.writeKey(name);");
        pw.println("    }");
        pw.println("}");
    }

    // ============================================================
    // Code generation helpers (write side)
    // ============================================================

    private void emitWriteComponent(PrintWriter pw, RecordComponentElement c,
                                    String jsonName, boolean nillable) {
        String name = c.getSimpleName().toString();
        String accessor = "value." + name + "()";
        // Key emission goes through the generated __writeKey helper: raw pre-quoted
        // fast path when the generator implements RawJsonKeyWriter, jakarta fallback
        // otherwise (parity with the former bytecode emitter, P4 optimization).
        String key = "__writeKey(g, " + lit(preQuoteJson(jsonName) ) + ", " + lit(jsonName) + ");";
        TypeMirror tm = c.asType();
        switch (tm.getKind()) {
            case INT, SHORT, BYTE -> pw.println("        " + key + " g.write((int) " + accessor + ");");
            case LONG -> pw.println("        " + key + " g.write(" + accessor + ");");
            case DOUBLE, FLOAT -> pw.println("        " + key + " g.write((double) " + accessor + ");");
            case BOOLEAN -> pw.println("        " + key + " g.write(" + accessor + ");");
            case DECLARED -> {
                DeclaredType dt = (DeclaredType) tm;
                String fqn = dt.asElement().toString();
                if ("java.lang.String".equals(fqn)) {
                    pw.println("        if (" + accessor + " != null) { " + key + " g.write(" + accessor + "); }");
                    if (nillable) {
                        // @JsonbNillable: null values are serialized as JSON null.
                        pw.println("        else { " + key + " g.writeNull(); }");
                    }
                } else if (isEnum(dt)) {
                    pw.println("        if (" + accessor + " != null) { " + key + " g.write(" + accessor + ".name()); }");
                } else if (isStaticRecord(dt)) {
                    // Nested @JsonbStatic record: direct delegation to the generated binding of the subtype.
                    String binding = bindingFqnOf(dt);
                    pw.println("        if (" + accessor + " != null) {");
                    pw.println("            " + key);
                    pw.println("            new " + binding + "().write(g, " + accessor + ");");
                    pw.println("        }");
                } else if ("java.util.List".equals(fqn)) {
                    TypeMirror elem = dt.getTypeArguments().get(0);
                    pw.println("        if (" + accessor + " != null) {");
                    pw.println("            " + key);
                    pw.println("            g.writeStartArray();");
                    String elemFqn = ((TypeElement) ((DeclaredType) elem).asElement()).getQualifiedName().toString();
                    pw.println("            for (" + elemFqn + " __e : " + accessor + ") {");
                    pw.println("                if (__e == null) g.writeNull(); else " + writeInline("__e", elem) + ";");
                    pw.println("            }");
                    pw.println("            g.writeEnd();");
                    pw.println("        }");
                } else if ("java.util.Optional".equals(fqn)) {
                    TypeMirror inner = dt.getTypeArguments().get(0);
                    pw.println("        if (" + accessor + " != null && " + accessor + ".isPresent()) {");
                    String innerFqn = ((TypeElement) ((DeclaredType) inner).asElement()).getQualifiedName().toString();
                    pw.println("            " + innerFqn + " __v = " + accessor + ".get();");
                    pw.println("            " + key);
                    pw.println("            " + writeInline("__v", inner) + ";");
                    pw.println("        }");
                } else if ("java.util.Map".equals(fqn)) {
                    TypeMirror valT = dt.getTypeArguments().get(1);
                    String valFqn = ((TypeElement) ((DeclaredType) valT).asElement()).getQualifiedName().toString();
                    pw.println("        if (" + accessor + " != null) {");
                    pw.println("            " + key);
                    pw.println("            g.writeStartObject();");
                    pw.println("            for (java.util.Map.Entry<String, " + valFqn + "> __en : " + accessor + ".entrySet()) {");
                    pw.println("                g.writeKey(__en.getKey());");
                    pw.println("                if (__en.getValue() == null) g.writeNull(); else " + writeInline("__en.getValue()", valT) + ";");
                    pw.println("            }");
                    pw.println("            g.writeEnd();");
                    pw.println("        }");
                }
            }
            case ARRAY -> {
                TypeMirror comp = ((ArrayType) tm).getComponentType();
                pw.println("        if (" + accessor + " != null) {");
                pw.println("            " + key);
                pw.println("            g.writeStartArray();");
                switch (comp.getKind()) {
                    case INT -> pw.println("            for (int __e : " + accessor + ") g.write(__e);");
                    case LONG -> pw.println("            for (long __e : " + accessor + ") g.write(__e);");
                    case DOUBLE -> pw.println("            for (double __e : " + accessor + ") g.write(__e);");
                    case BOOLEAN -> pw.println("            for (boolean __e : " + accessor + ") g.write(__e);");
                    case DECLARED -> {
                        // String[]
                        pw.println("            for (String __e : " + accessor + ") {");
                        pw.println("                if (__e == null) g.writeNull(); else g.write(__e);");
                        pw.println("            }");
                    }
                    default -> {}
                }
                pw.println("            g.writeEnd();");
                pw.println("        }");
            }
            default -> {}
        }
    }

    /**
     * Returns a Java expression (without the final {@code ;}) that emits
     * {@code expr} into the current {@code g} generator. Covers String, boxed
     * leaves, enums, and {@code @JsonbStatic} records.
     */
    private static String writeInline(String expr, TypeMirror tm) {
        if (tm.getKind() != javax.lang.model.type.TypeKind.DECLARED) {
            return "g.write(" + expr + ")";
        }
        DeclaredType dt = (DeclaredType) tm;
        if (isStaticRecord(dt)) {
            return "new " + bindingFqnOf(dt) + "().write(g, " + expr + ")";
        }
        if (isEnum(dt)) {
            return "g.write(" + expr + ".name())";
        }
        String fqn = dt.asElement().toString();
        return switch (fqn) {
            case "java.lang.String" -> "g.write(" + expr + ")";
            case "java.lang.Integer", "java.lang.Short", "java.lang.Byte" -> "g.write(" + expr + ".intValue())";
            case "java.lang.Long" -> "g.write(" + expr + ".longValue())";
            case "java.lang.Double", "java.lang.Float" -> "g.write(" + expr + ".doubleValue())";
            case "java.lang.Boolean" -> "g.write(" + expr + ".booleanValue())";
            default -> "g.write(String.valueOf(" + expr + "))";
        };
    }

    // ============================================================
    // Code generation helpers (read side)
    // ============================================================

    private void emitReadDeclaration(PrintWriter pw, RecordComponentElement c) {
        String name = c.getSimpleName().toString();
        TypeMirror tm = c.asType();
        switch (tm.getKind()) {
            case INT -> pw.println("        int _" + name + " = 0;");
            case SHORT -> pw.println("        short _" + name + " = 0;");
            case BYTE -> pw.println("        byte _" + name + " = 0;");
            case LONG -> pw.println("        long _" + name + " = 0L;");
            case DOUBLE -> pw.println("        double _" + name + " = 0.0;");
            case FLOAT -> pw.println("        float _" + name + " = 0.0f;");
            case BOOLEAN -> pw.println("        boolean _" + name + " = false;");
            case DECLARED -> {
                DeclaredType dt = (DeclaredType) tm;
                String fqn = dt.asElement().toString();
                if ("java.util.List".equals(fqn)) {
                    String elemFqn = ((TypeElement) ((DeclaredType) dt.getTypeArguments().get(0)).asElement())
                            .getQualifiedName().toString();
                    pw.println("        java.util.List<" + elemFqn + "> _" + name + " = null;");
                } else if ("java.util.Optional".equals(fqn)) {
                    String inner = ((TypeElement) ((DeclaredType) dt.getTypeArguments().get(0)).asElement())
                            .getQualifiedName().toString();
                    pw.println("        java.util.Optional<" + inner + "> _" + name + " = java.util.Optional.empty();");
                } else if ("java.util.Map".equals(fqn)) {
                    String valFqn = ((TypeElement) ((DeclaredType) dt.getTypeArguments().get(1)).asElement())
                            .getQualifiedName().toString();
                    pw.println("        java.util.Map<String, " + valFqn + "> _" + name + " = null;");
                } else if (isEnum(dt) || isStaticRecord(dt)) {
                    String f = ((TypeElement) dt.asElement()).getQualifiedName().toString();
                    pw.println("        " + f + " _" + name + " = null;");
                } else {
                    pw.println("        String _" + name + " = null;");
                }
            }
            case ARRAY -> {
                TypeMirror comp = ((ArrayType) tm).getComponentType();
                String typeStr = switch (comp.getKind()) {
                    case INT -> "int[]";
                    case LONG -> "long[]";
                    case DOUBLE -> "double[]";
                    case BOOLEAN -> "boolean[]";
                    case DECLARED -> "String[]";
                    default -> "Object[]";
                };
                pw.println("        " + typeStr + " _" + name + " = null;");
            }
            default -> {}
        }
    }

    private void emitReadComponent(PrintWriter pw, RecordComponentElement c, String target) {
        TypeMirror tm = c.asType();
        switch (tm.getKind()) {
            case INT -> pw.println("                    if (__ev != JsonParser.Event.VALUE_NULL) " + target + " = p.getInt();");
            case SHORT -> pw.println("                    if (__ev != JsonParser.Event.VALUE_NULL) " + target + " = (short) p.getInt();");
            case BYTE -> pw.println("                    if (__ev != JsonParser.Event.VALUE_NULL) " + target + " = (byte) p.getInt();");
            case LONG -> pw.println("                    if (__ev != JsonParser.Event.VALUE_NULL) " + target + " = p.getLong();");
            case DOUBLE -> pw.println("                    if (__ev != JsonParser.Event.VALUE_NULL) " + target + " = p.getBigDecimal().doubleValue();");
            case FLOAT -> pw.println("                    if (__ev != JsonParser.Event.VALUE_NULL) " + target + " = p.getBigDecimal().floatValue();");
            case BOOLEAN -> pw.println("                    " + target + " = (__ev == JsonParser.Event.VALUE_TRUE);");
            case DECLARED -> {
                DeclaredType dt = (DeclaredType) tm;
                String fqn = dt.asElement().toString();
                if ("java.lang.String".equals(fqn)) {
                    pw.println("                    if (__ev != JsonParser.Event.VALUE_NULL) " + target + " = p.getString();");
                } else if (isEnum(dt)) {
                    String f = ((TypeElement) dt.asElement()).getQualifiedName().toString();
                    pw.println("                    if (__ev != JsonParser.Event.VALUE_NULL) " + target + " = " + f + ".valueOf(p.getString());");
                } else if (isStaticRecord(dt)) {
                    // Delegation to <X>$$Binding. The binding may be bytecode (read
                    // returns Object) or parameterized source (read returns T). Cast
                    // explicitly to cover both.
                    String binding = bindingFqnOf(dt);
                    String f = ((TypeElement) dt.asElement()).getQualifiedName().toString();
                    pw.println("                    if (__ev == JsonParser.Event.VALUE_NULL) " + target + " = null;");
                    pw.println("                    else {");
                    pw.println("                        JsonParser __primed = new io.vidocq.champollion.jsonb.spi.PrimedJsonParser(__ev, p);");
                    pw.println("                        " + target + " = (" + f + ") new " + binding + "().read(__primed);");
                    pw.println("                    }");
                } else if ("java.util.List".equals(fqn)) {
                    TypeMirror elem = dt.getTypeArguments().get(0);
                    String elemFqn = ((TypeElement) ((DeclaredType) elem).asElement()).getQualifiedName().toString();
                    pw.println("                    if (__ev != JsonParser.Event.START_ARRAY) throw new IllegalStateException(\"Expected START_ARRAY\");");
                    pw.println("                    java.util.ArrayList<" + elemFqn + "> __list = new java.util.ArrayList<>();");
                    pw.println("                    JsonParser.Event __aev;");
                    pw.println("                    while ((__aev = p.next()) != JsonParser.Event.END_ARRAY) {");
                    pw.println("                        if (__aev == JsonParser.Event.VALUE_NULL) __list.add(null);");
                    pw.println("                        else __list.add(" + readInline("__aev", "p", elem) + ");");
                    pw.println("                    }");
                    pw.println("                    " + target + " = __list;");
                } else if ("java.util.Optional".equals(fqn)) {
                    TypeMirror inner = dt.getTypeArguments().get(0);
                    pw.println("                    if (__ev == JsonParser.Event.VALUE_NULL) " + target + " = java.util.Optional.empty();");
                    pw.println("                    else " + target + " = java.util.Optional.of(" + readInline("__ev", "p", inner) + ");");
                } else if ("java.util.Map".equals(fqn)) {
                    TypeMirror valT = dt.getTypeArguments().get(1);
                    String valFqn = ((TypeElement) ((DeclaredType) valT).asElement()).getQualifiedName().toString();
                    pw.println("                    if (__ev != JsonParser.Event.START_OBJECT) throw new IllegalStateException(\"Expected START_OBJECT\");");
                    pw.println("                    java.util.LinkedHashMap<String, " + valFqn + "> __map = new java.util.LinkedHashMap<>();");
                    pw.println("                    JsonParser.Event __mev;");
                    pw.println("                    while ((__mev = p.next()) != JsonParser.Event.END_OBJECT) {");
                    pw.println("                        if (__mev != JsonParser.Event.KEY_NAME) throw new IllegalStateException(\"Expected KEY_NAME\");");
                    pw.println("                        String __k = p.getString();");
                    pw.println("                        JsonParser.Event __vev = p.next();");
                    pw.println("                        if (__vev == JsonParser.Event.VALUE_NULL) __map.put(__k, null);");
                    pw.println("                        else __map.put(__k, " + readInline("__vev", "p", valT) + ");");
                    pw.println("                    }");
                    pw.println("                    " + target + " = __map;");
                }
            }
            case ARRAY -> {
                TypeMirror comp = ((ArrayType) tm).getComponentType();
                pw.println("                    if (__ev != JsonParser.Event.START_ARRAY) throw new IllegalStateException(\"Expected START_ARRAY\");");
                switch (comp.getKind()) {
                    case INT -> {
                        pw.println("                    java.util.ArrayList<Integer> __ints = new java.util.ArrayList<>();");
                        pw.println("                    JsonParser.Event __aev;");
                        pw.println("                    while ((__aev = p.next()) != JsonParser.Event.END_ARRAY) __ints.add(p.getInt());");
                        pw.println("                    int[] __arr = new int[__ints.size()];");
                        pw.println("                    for (int __i = 0; __i < __arr.length; __i++) __arr[__i] = __ints.get(__i);");
                        pw.println("                    " + target + " = __arr;");
                    }
                    case LONG -> {
                        pw.println("                    java.util.ArrayList<Long> __longs = new java.util.ArrayList<>();");
                        pw.println("                    JsonParser.Event __aev;");
                        pw.println("                    while ((__aev = p.next()) != JsonParser.Event.END_ARRAY) __longs.add(p.getLong());");
                        pw.println("                    long[] __arr = new long[__longs.size()];");
                        pw.println("                    for (int __i = 0; __i < __arr.length; __i++) __arr[__i] = __longs.get(__i);");
                        pw.println("                    " + target + " = __arr;");
                    }
                    case DOUBLE -> {
                        pw.println("                    java.util.ArrayList<Double> __doubles = new java.util.ArrayList<>();");
                        pw.println("                    JsonParser.Event __aev;");
                        pw.println("                    while ((__aev = p.next()) != JsonParser.Event.END_ARRAY) __doubles.add(p.getBigDecimal().doubleValue());");
                        pw.println("                    double[] __arr = new double[__doubles.size()];");
                        pw.println("                    for (int __i = 0; __i < __arr.length; __i++) __arr[__i] = __doubles.get(__i);");
                        pw.println("                    " + target + " = __arr;");
                    }
                    case BOOLEAN -> {
                        pw.println("                    java.util.ArrayList<Boolean> __bools = new java.util.ArrayList<>();");
                        pw.println("                    JsonParser.Event __aev;");
                        pw.println("                    while ((__aev = p.next()) != JsonParser.Event.END_ARRAY) __bools.add(__aev == JsonParser.Event.VALUE_TRUE);");
                        pw.println("                    boolean[] __arr = new boolean[__bools.size()];");
                        pw.println("                    for (int __i = 0; __i < __arr.length; __i++) __arr[__i] = __bools.get(__i);");
                        pw.println("                    " + target + " = __arr;");
                    }
                    case DECLARED -> {
                        // String[]
                        pw.println("                    java.util.ArrayList<String> __ss = new java.util.ArrayList<>();");
                        pw.println("                    JsonParser.Event __aev;");
                        pw.println("                    while ((__aev = p.next()) != JsonParser.Event.END_ARRAY) {");
                        pw.println("                        __ss.add(__aev == JsonParser.Event.VALUE_NULL ? null : p.getString());");
                        pw.println("                    }");
                        pw.println("                    " + target + " = __ss.toArray(new String[0]);");
                    }
                    default -> {}
                }
            }
            default -> {}
        }
    }

    /** Reads the current value as a leaf type. {@code ev} = event already consumed for this value. */
    private static String readLeaf(String ev, String p, String fqn) {
        return switch (fqn) {
            case "java.lang.String" -> p + ".getString()";
            case "java.lang.Integer" -> p + ".getInt()";
            case "java.lang.Long" -> p + ".getLong()";
            case "java.lang.Double", "java.lang.Float" -> p + ".getBigDecimal().doubleValue()";
            case "java.lang.Short" -> "(short) " + p + ".getInt()";
            case "java.lang.Byte" -> "(byte) " + p + ".getInt()";
            case "java.lang.Boolean" -> "(" + ev + " == JsonParser.Event.VALUE_TRUE)";
            default -> p + ".getString()";
        };
    }

    /**
     * Returns a Java expression that reads a value from parser {@code p},
     * assuming the leading event {@code ev} has already been consumed. For
     * static records, returns an expression that creates the binding and invokes
     * it via a {@code PrimedJsonParser}.
     */
    private static String readInline(String ev, String p, TypeMirror tm) {
        if (tm.getKind() != javax.lang.model.type.TypeKind.DECLARED) {
            return readLeaf(ev, p, "?");
        }
        DeclaredType dt = (DeclaredType) tm;
        if (isStaticRecord(dt)) {
            String b = bindingFqnOf(dt);
            String targetFqn = ((TypeElement) dt.asElement()).getQualifiedName().toString();
            // Explicit cast: the binding may be raw bytecode (Object read) or
            // parameterized source (T read). The cast covers both cases.
            return "(" + targetFqn + ") new " + b + "().read(new io.vidocq.champollion.jsonb.spi.PrimedJsonParser(" + ev + ", " + p + "))";
        }
        if (isEnum(dt)) {
            String f = ((TypeElement) dt.asElement()).getQualifiedName().toString();
            return f + ".valueOf(" + p + ".getString())";
        }
        return readLeaf(ev, p, dt.asElement().toString());
    }

    private void writeServicesFile() {
        Filer filer = processingEnv.getFiler();
        try {
            // Read existing services (to append across multi-rounds / incremental builds)
            Set<String> all = new LinkedHashSet<>();
            try {
                FileObject existing = filer.getResource(StandardLocation.CLASS_OUTPUT, "", SERVICE_FILE);
                try (var reader = new BufferedReader(
                        new InputStreamReader(existing.openInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.strip();
                        if (!line.isEmpty() && !line.startsWith("#")) all.add(line);
                    }
                }
            } catch (IOException ignored) {
                // Fichier absent au premier round : OK.
            }
            all.addAll(generatedBindings);
            FileObject f = filer.createResource(StandardLocation.CLASS_OUTPUT, "", SERVICE_FILE);
            try (var w = f.openWriter()) {
                for (String s : all) {
                    w.write(s);
                    w.write('\n');
                }
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to write " + SERVICE_FILE + ": " + e.getMessage());
        }
    }

    private void error(Element where, String message) {
        Messager m = processingEnv.getMessager();
        m.printMessage(Diagnostic.Kind.ERROR, message, where);
    }
}
