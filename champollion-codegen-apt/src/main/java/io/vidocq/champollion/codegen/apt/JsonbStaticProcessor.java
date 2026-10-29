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

    // Qualified names the component types are matched against.
    private static final String JAVA_LANG_STRING = "java.lang.String";
    private static final String JAVA_LANG_INTEGER = "java.lang.Integer";
    private static final String JAVA_LANG_LONG = "java.lang.Long";
    private static final String JAVA_LANG_SHORT = "java.lang.Short";
    private static final String JAVA_LANG_BYTE = "java.lang.Byte";
    private static final String JAVA_LANG_FLOAT = "java.lang.Float";
    private static final String JAVA_LANG_DOUBLE = "java.lang.Double";
    private static final String JAVA_LANG_BOOLEAN = "java.lang.Boolean";
    private static final String JAVA_UTIL_LIST = "java.util.List";
    private static final String JAVA_UTIL_OPTIONAL = "java.util.Optional";
    private static final String JAVA_UTIL_MAP = "java.util.Map";

    // Fragments of the generated source shared by the emit* helpers; the
    // numeric suffix is the indentation, in spaces.
    private static final String INDENT_8 = "        ";
    private static final String INDENT_12 = "            ";
    private static final String INDENT_20 = "                    ";
    private static final String CLOSE_4 = "    }";
    private static final String CLOSE_8 = "        }";
    private static final String CLOSE_12 = "            }";
    private static final String CLOSE_20 = "                    }";
    private static final String IF_8 = "        if (";
    private static final String IF_VALUE_NOT_NULL_20 = "                    if (__ev != JsonParser.Event.VALUE_NULL) ";
    private static final String DECLARE_AEV_20 = "                    JsonParser.Event __aev;";
    private static final String WRITE_END_12 = "            g.writeEnd();";
    private static final String NOT_NULL_THEN = " != null) {";
    private static final String G_WRITE = "g.write(";
    private static final String SPACE_G_WRITE = " g.write(";
    private static final String WRITE_ELEMENT = ") g.write(__e);";
    private static final String ASSIGN_ZERO = " = 0;";
    private static final String ASSIGN_NULL = " = null;";
    private static final String ASSIGN_ARR = " = __arr;";
    private static final String GET_INT = ".getInt()";

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

    private void processRecord(TypeElement recordType) {
        String simpleName = recordType.getSimpleName().toString();
        // Nested types: we must reconstruct the binary name with '$' but the source
        // file has a name derived from the simple name. For M5.2, only top-level types are accepted.
        if (!(recordType.getEnclosingElement() instanceof PackageElement pkg)) {
            error(recordType, "@JsonbStatic only supported on top-level records in M5.2");
            return;
        }
        String pkgName = pkg.getQualifiedName().toString();
        String bindingSimple = simpleName + "$$Binding";
        String bindingFqn = pkgName.isEmpty() ? bindingSimple : pkgName + "." + bindingSimple;
        String targetFqn = recordType.getQualifiedName().toString();

        List<? extends RecordComponentElement> comps = recordType.getRecordComponents();

        // Validation: components supported in M5.2.
        // JSON-B 3.0 §3.7: two properties cannot share a JSON name; the read switch could not tell them apart.
        java.util.Map<String, String> componentByJsonName = new java.util.HashMap<>();
        for (var c : comps) {
            if (isJsonbTransient(c)) continue;
            String previous = componentByJsonName.putIfAbsent(jsonbName(c), c.getSimpleName().toString());
            if (previous != null) {
                error(recordType, "@JsonbStatic components " + previous + " and " + c.getSimpleName()
                        + " have the same JSON name \"" + jsonbName(c) + "\"");
                return;
            }
        }
        for (var c : comps) {
            if (!isSupportedComponentType(c.asType())) {
                error(recordType,
                        "@JsonbStatic component type not supported in M5.2: " + c.asType()
                                + " (only primitives + String supported — containers in M5.3)");
                return;
            }
        }

        // Single emission mode: Java source compiled by javac (workspace APT-first
        // rule, codegen audit CG-03). The former Class-File fast path for
        // primitives+String records was removed — readable, debuggable output wins
        // over saving one javac round trip.
        try (Writer w = processingEnv.getFiler().createSourceFile(bindingFqn, recordType).openWriter();
             PrintWriter pw = new PrintWriter(w)) {
            emit(pw, pkgName, bindingSimple, targetFqn, comps);
        } catch (IOException ex) {
            error(recordType, "Failed to generate binding: " + ex.getMessage());
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
                if (JAVA_LANG_STRING.equals(fqn)) yield true;
                if (isEnum(dt)) yield true;
                if (isStaticRecord(dt)) yield true;
                if (JAVA_UTIL_LIST.equals(fqn) || JAVA_UTIL_OPTIONAL.equals(fqn)) {
                    var args = dt.getTypeArguments();
                    if (args.size() != 1) yield false;
                    TypeMirror inner = args.get(0);
                    yield isLeafType(inner) || isInnerComplex(inner);
                }
                if (JAVA_UTIL_MAP.equals(fqn)) {
                    var args = dt.getTypeArguments();
                    if (args.size() != 2) yield false;
                    TypeMirror keyT = args.get(0);
                    if (keyT.getKind() != javax.lang.model.type.TypeKind.DECLARED
                            || !JAVA_LANG_STRING.equals(((DeclaredType) keyT).asElement().toString())) {
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
                    case DECLARED -> JAVA_LANG_STRING.equals(comp.toString());
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
                case JAVA_LANG_STRING, JAVA_LANG_INTEGER, JAVA_LANG_LONG,
                     JAVA_LANG_DOUBLE, JAVA_LANG_FLOAT, JAVA_LANG_SHORT,
                     JAVA_LANG_BYTE, JAVA_LANG_BOOLEAN -> true;
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

    private void emit(PrintWriter pw, String pkg, String bindingName,
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
        pw.println(CLOSE_4);
        pw.println();

        // ===== read =====
        pw.println("    @Override");
        pw.println("    public " + targetFqn + " read(JsonParser p) {");
        pw.println("        JsonParser.Event e = p.next();");
        pw.println("        if (e == JsonParser.Event.VALUE_NULL) return null;");
        pw.println("        if (e != JsonParser.Event.START_OBJECT) {");
        pw.println("            throw new IllegalStateException(\"Expected START_OBJECT, got \" + e);");
        pw.println(CLOSE_8);
        for (var c : comps) {
            emitReadDeclaration(pw, c);
        }
        pw.println("        while ((e = p.next()) != JsonParser.Event.END_OBJECT) {");
        pw.println("            if (e != JsonParser.Event.KEY_NAME) {");
        pw.println("                throw new IllegalStateException(\"Expected KEY_NAME, got \" + e);");
        pw.println(CLOSE_12);
        pw.println("            String __key = p.getString();");
        pw.println("            JsonParser.Event __ev = p.next();");
        pw.println("            switch (__key) {");
        for (var c : comps) {
            if (isJsonbTransient(c)) continue;   // @JsonbTransient: no read mapping
            String name = c.getSimpleName().toString();
            pw.println("                case " + lit(jsonbName(c)) + " -> {");
            emitReadComponent(pw, c, "_" + name);
            pw.println("                }");
        }
        pw.println("                default -> {");
        pw.println("                    if (__ev == JsonParser.Event.START_OBJECT) p.skipObject();");
        pw.println("                    else if (__ev == JsonParser.Event.START_ARRAY) p.skipArray();");
        pw.println("                }");
        pw.println(CLOSE_12);
        pw.println(CLOSE_8);
        pw.print("        return new " + targetFqn + "(");
        for (int i = 0; i < comps.size(); i++) {
            if (i > 0) pw.print(", ");
            pw.print("_" + comps.get(i).getSimpleName());
        }
        pw.println(");");
        pw.println(CLOSE_4);
        pw.println();
        // Raw-key fast path (P4): pre-quoted RFC 8259 keys when the generator is
        // champollion's (same optimization the former bytecode emitter applied).
        pw.println("    private static void __writeKey(JsonGenerator g, String preQuotedWithColon, String name) {");
        pw.println("        if (g instanceof io.vidocq.champollion.spi.RawJsonKeyWriter __rw) __rw.writeKeyRawWithColon(preQuotedWithColon);");
        pw.println("        else g.writeKey(name);");
        pw.println(CLOSE_4);
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
            case INT, SHORT, BYTE -> pw.println(INDENT_8 + key + " g.write((int) " + accessor + ");");
            case LONG -> pw.println(INDENT_8 + key + SPACE_G_WRITE + accessor + ");");
            case DOUBLE, FLOAT -> pw.println(INDENT_8 + key + " g.write((double) " + accessor + ");");
            case BOOLEAN -> pw.println(INDENT_8 + key + SPACE_G_WRITE + accessor + ");");
            case DECLARED -> emitWriteDeclared(pw, (DeclaredType) tm, accessor, key, nillable);
            case ARRAY -> emitWriteArray(pw, (ArrayType) tm, accessor, key);
            default -> {
                // Unreachable: isSupportedComponentType rejects any other component kind.
            }
        }
    }

    private void emitWriteDeclared(PrintWriter pw, DeclaredType dt, String accessor, String key, boolean nillable) {
        String fqn = dt.asElement().toString();
        if (JAVA_LANG_STRING.equals(fqn)) {
            pw.println(IF_8 + accessor + " != null) { " + key + SPACE_G_WRITE + accessor + "); }");
            if (nillable) {
                // @JsonbNillable: null values are serialized as JSON null.
                pw.println("        else { " + key + " g.writeNull(); }");
            }
        } else if (isEnum(dt)) {
            pw.println(IF_8 + accessor + " != null) { " + key + SPACE_G_WRITE + accessor + ".name()); }");
        } else if (isStaticRecord(dt)) {
            // Nested @JsonbStatic record: direct delegation to the generated binding of the subtype.
            String binding = bindingFqnOf(dt);
            pw.println(IF_8 + accessor + NOT_NULL_THEN);
            pw.println(INDENT_12 + key);
            pw.println("            new " + binding + "().write(g, " + accessor + ");");
            pw.println(CLOSE_8);
        } else if (JAVA_UTIL_LIST.equals(fqn)) {
            TypeMirror elem = dt.getTypeArguments().get(0);
            pw.println(IF_8 + accessor + NOT_NULL_THEN);
            pw.println(INDENT_12 + key);
            pw.println("            g.writeStartArray();");
            String elemFqn = ((TypeElement) ((DeclaredType) elem).asElement()).getQualifiedName().toString();
            pw.println("            for (" + elemFqn + " __e : " + accessor + ") {");
            pw.println("                if (__e == null) g.writeNull(); else " + writeInline("__e", elem) + ";");
            pw.println(CLOSE_12);
            pw.println(WRITE_END_12);
            pw.println(CLOSE_8);
        } else if (JAVA_UTIL_OPTIONAL.equals(fqn)) {
            TypeMirror inner = dt.getTypeArguments().get(0);
            pw.println(IF_8 + accessor + " != null && " + accessor + ".isPresent()) {");
            String innerFqn = ((TypeElement) ((DeclaredType) inner).asElement()).getQualifiedName().toString();
            pw.println(INDENT_12 + innerFqn + " __v = " + accessor + ".get();");
            pw.println(INDENT_12 + key);
            pw.println(INDENT_12 + writeInline("__v", inner) + ";");
            pw.println(CLOSE_8);
        } else if (JAVA_UTIL_MAP.equals(fqn)) {
            TypeMirror valT = dt.getTypeArguments().get(1);
            String valFqn = ((TypeElement) ((DeclaredType) valT).asElement()).getQualifiedName().toString();
            pw.println(IF_8 + accessor + NOT_NULL_THEN);
            pw.println(INDENT_12 + key);
            pw.println("            g.writeStartObject();");
            pw.println("            for (java.util.Map.Entry<String, " + valFqn + "> __en : " + accessor + ".entrySet()) {");
            pw.println("                g.writeKey(__en.getKey());");
            pw.println("                if (__en.getValue() == null) g.writeNull(); else " + writeInline("__en.getValue()", valT) + ";");
            pw.println(CLOSE_12);
            pw.println(WRITE_END_12);
            pw.println(CLOSE_8);
        }
    }

    private static void emitWriteArray(PrintWriter pw, ArrayType arrayType, String accessor, String key) {
        TypeMirror comp = arrayType.getComponentType();
        pw.println(IF_8 + accessor + NOT_NULL_THEN);
        pw.println(INDENT_12 + key);
        pw.println("            g.writeStartArray();");
        switch (comp.getKind()) {
            case INT -> pw.println("            for (int __e : " + accessor + WRITE_ELEMENT);
            case LONG -> pw.println("            for (long __e : " + accessor + WRITE_ELEMENT);
            case DOUBLE -> pw.println("            for (double __e : " + accessor + WRITE_ELEMENT);
            case BOOLEAN -> pw.println("            for (boolean __e : " + accessor + WRITE_ELEMENT);
            case DECLARED -> {
                // String[]
                pw.println("            for (String __e : " + accessor + ") {");
                pw.println("                if (__e == null) g.writeNull(); else g.write(__e);");
                pw.println(CLOSE_12);
            }
            default -> {
                // Unreachable: isSupportedComponentType rejects any other array component.
            }
        }
        pw.println(WRITE_END_12);
        pw.println(CLOSE_8);
    }

    /**
     * Returns a Java expression (without the final {@code ;}) that emits
     * {@code expr} into the current {@code g} generator. Covers String, boxed
     * leaves, enums, and {@code @JsonbStatic} records.
     */
    private static String writeInline(String expr, TypeMirror tm) {
        if (tm.getKind() != javax.lang.model.type.TypeKind.DECLARED) {
            return G_WRITE + expr + ")";
        }
        DeclaredType dt = (DeclaredType) tm;
        if (isStaticRecord(dt)) {
            return "new " + bindingFqnOf(dt) + "().write(g, " + expr + ")";
        }
        if (isEnum(dt)) {
            return G_WRITE + expr + ".name())";
        }
        String fqn = dt.asElement().toString();
        return switch (fqn) {
            case JAVA_LANG_STRING -> G_WRITE + expr + ")";
            case JAVA_LANG_INTEGER, JAVA_LANG_SHORT, JAVA_LANG_BYTE -> G_WRITE + expr + ".intValue())";
            case JAVA_LANG_LONG -> G_WRITE + expr + ".longValue())";
            case JAVA_LANG_DOUBLE, JAVA_LANG_FLOAT -> G_WRITE + expr + ".doubleValue())";
            case JAVA_LANG_BOOLEAN -> G_WRITE + expr + ".booleanValue())";
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
            case INT -> pw.println("        int _" + name + ASSIGN_ZERO);
            case SHORT -> pw.println("        short _" + name + ASSIGN_ZERO);
            case BYTE -> pw.println("        byte _" + name + ASSIGN_ZERO);
            case LONG -> pw.println("        long _" + name + " = 0L;");
            case DOUBLE -> pw.println("        double _" + name + " = 0.0;");
            case FLOAT -> pw.println("        float _" + name + " = 0.0f;");
            case BOOLEAN -> pw.println("        boolean _" + name + " = false;");
            case DECLARED -> {
                DeclaredType dt = (DeclaredType) tm;
                String fqn = dt.asElement().toString();
                if (JAVA_UTIL_LIST.equals(fqn)) {
                    String elemFqn = ((TypeElement) ((DeclaredType) dt.getTypeArguments().get(0)).asElement())
                            .getQualifiedName().toString();
                    pw.println("        java.util.List<" + elemFqn + "> _" + name + ASSIGN_NULL);
                } else if (JAVA_UTIL_OPTIONAL.equals(fqn)) {
                    String inner = ((TypeElement) ((DeclaredType) dt.getTypeArguments().get(0)).asElement())
                            .getQualifiedName().toString();
                    pw.println("        java.util.Optional<" + inner + "> _" + name + " = java.util.Optional.empty();");
                } else if (JAVA_UTIL_MAP.equals(fqn)) {
                    String valFqn = ((TypeElement) ((DeclaredType) dt.getTypeArguments().get(1)).asElement())
                            .getQualifiedName().toString();
                    pw.println("        java.util.Map<String, " + valFqn + "> _" + name + ASSIGN_NULL);
                } else if (isEnum(dt) || isStaticRecord(dt)) {
                    String f = ((TypeElement) dt.asElement()).getQualifiedName().toString();
                    pw.println(INDENT_8 + f + " _" + name + ASSIGN_NULL);
                } else {
                    pw.println("        String _" + name + ASSIGN_NULL);
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
                pw.println(INDENT_8 + typeStr + " _" + name + ASSIGN_NULL);
            }
            default -> {
                // Unreachable: isSupportedComponentType rejects any other component kind.
            }
        }
    }

    private void emitReadComponent(PrintWriter pw, RecordComponentElement c, String target) {
        TypeMirror tm = c.asType();
        switch (tm.getKind()) {
            case INT -> pw.println(IF_VALUE_NOT_NULL_20 + target + " = p.getInt();");
            case SHORT -> pw.println(IF_VALUE_NOT_NULL_20 + target + " = (short) p.getInt();");
            case BYTE -> pw.println(IF_VALUE_NOT_NULL_20 + target + " = (byte) p.getInt();");
            case LONG -> pw.println(IF_VALUE_NOT_NULL_20 + target + " = p.getLong();");
            case DOUBLE -> pw.println(IF_VALUE_NOT_NULL_20 + target + " = p.getBigDecimal().doubleValue();");
            case FLOAT -> pw.println(IF_VALUE_NOT_NULL_20 + target + " = p.getBigDecimal().floatValue();");
            case BOOLEAN -> pw.println(INDENT_20 + target + " = (__ev == JsonParser.Event.VALUE_TRUE);");
            case DECLARED -> {
                DeclaredType dt = (DeclaredType) tm;
                String fqn = dt.asElement().toString();
                if (JAVA_LANG_STRING.equals(fqn)) {
                    pw.println(IF_VALUE_NOT_NULL_20 + target + " = p.getString();");
                } else if (isEnum(dt)) {
                    String f = ((TypeElement) dt.asElement()).getQualifiedName().toString();
                    pw.println(IF_VALUE_NOT_NULL_20 + target + " = " + f + ".valueOf(p.getString());");
                } else if (isStaticRecord(dt)) {
                    // Delegation to <X>$$Binding. The binding may be bytecode (read
                    // returns Object) or parameterized source (read returns T). Cast
                    // explicitly to cover both.
                    String binding = bindingFqnOf(dt);
                    String f = ((TypeElement) dt.asElement()).getQualifiedName().toString();
                    pw.println("                    if (__ev == JsonParser.Event.VALUE_NULL) " + target + ASSIGN_NULL);
                    pw.println("                    else {");
                    pw.println("                        JsonParser __primed = new io.vidocq.champollion.jsonb.spi.PrimedJsonParser(__ev, p);");
                    pw.println("                        " + target + " = (" + f + ") new " + binding + "().read(__primed);");
                    pw.println(CLOSE_20);
                } else if (JAVA_UTIL_LIST.equals(fqn)) {
                    TypeMirror elem = dt.getTypeArguments().get(0);
                    String elemFqn = ((TypeElement) ((DeclaredType) elem).asElement()).getQualifiedName().toString();
                    pw.println("                    if (__ev != JsonParser.Event.START_ARRAY) throw new IllegalStateException(\"Expected START_ARRAY\");");
                    pw.println("                    java.util.ArrayList<" + elemFqn + "> __list = new java.util.ArrayList<>();");
                    pw.println(DECLARE_AEV_20);
                    pw.println("                    while ((__aev = p.next()) != JsonParser.Event.END_ARRAY) {");
                    pw.println("                        if (__aev == JsonParser.Event.VALUE_NULL) __list.add(null);");
                    pw.println("                        else __list.add(" + readInline("__aev", "p", elem) + ");");
                    pw.println(CLOSE_20);
                    pw.println(INDENT_20 + target + " = __list;");
                } else if (JAVA_UTIL_OPTIONAL.equals(fqn)) {
                    TypeMirror inner = dt.getTypeArguments().get(0);
                    pw.println("                    if (__ev == JsonParser.Event.VALUE_NULL) " + target + " = java.util.Optional.empty();");
                    pw.println("                    else " + target + " = java.util.Optional.of(" + readInline("__ev", "p", inner) + ");");
                } else if (JAVA_UTIL_MAP.equals(fqn)) {
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
                    pw.println(CLOSE_20);
                    pw.println(INDENT_20 + target + " = __map;");
                }
            }
            case ARRAY -> {
                TypeMirror comp = ((ArrayType) tm).getComponentType();
                pw.println("                    if (__ev != JsonParser.Event.START_ARRAY) throw new IllegalStateException(\"Expected START_ARRAY\");");
                switch (comp.getKind()) {
                    case INT -> {
                        pw.println("                    java.util.ArrayList<Integer> __ints = new java.util.ArrayList<>();");
                        pw.println(DECLARE_AEV_20);
                        pw.println("                    while ((__aev = p.next()) != JsonParser.Event.END_ARRAY) __ints.add(p.getInt());");
                        pw.println("                    int[] __arr = new int[__ints.size()];");
                        pw.println("                    for (int __i = 0; __i < __arr.length; __i++) __arr[__i] = __ints.get(__i);");
                        pw.println(INDENT_20 + target + ASSIGN_ARR);
                    }
                    case LONG -> {
                        pw.println("                    java.util.ArrayList<Long> __longs = new java.util.ArrayList<>();");
                        pw.println(DECLARE_AEV_20);
                        pw.println("                    while ((__aev = p.next()) != JsonParser.Event.END_ARRAY) __longs.add(p.getLong());");
                        pw.println("                    long[] __arr = new long[__longs.size()];");
                        pw.println("                    for (int __i = 0; __i < __arr.length; __i++) __arr[__i] = __longs.get(__i);");
                        pw.println(INDENT_20 + target + ASSIGN_ARR);
                    }
                    case DOUBLE -> {
                        pw.println("                    java.util.ArrayList<Double> __doubles = new java.util.ArrayList<>();");
                        pw.println(DECLARE_AEV_20);
                        pw.println("                    while ((__aev = p.next()) != JsonParser.Event.END_ARRAY) __doubles.add(p.getBigDecimal().doubleValue());");
                        pw.println("                    double[] __arr = new double[__doubles.size()];");
                        pw.println("                    for (int __i = 0; __i < __arr.length; __i++) __arr[__i] = __doubles.get(__i);");
                        pw.println(INDENT_20 + target + ASSIGN_ARR);
                    }
                    case BOOLEAN -> {
                        pw.println("                    java.util.ArrayList<Boolean> __bools = new java.util.ArrayList<>();");
                        pw.println(DECLARE_AEV_20);
                        pw.println("                    while ((__aev = p.next()) != JsonParser.Event.END_ARRAY) __bools.add(__aev == JsonParser.Event.VALUE_TRUE);");
                        pw.println("                    boolean[] __arr = new boolean[__bools.size()];");
                        pw.println("                    for (int __i = 0; __i < __arr.length; __i++) __arr[__i] = __bools.get(__i);");
                        pw.println(INDENT_20 + target + ASSIGN_ARR);
                    }
                    case DECLARED -> {
                        // String[]
                        pw.println("                    java.util.ArrayList<String> __ss = new java.util.ArrayList<>();");
                        pw.println(DECLARE_AEV_20);
                        pw.println("                    while ((__aev = p.next()) != JsonParser.Event.END_ARRAY) {");
                        pw.println("                        __ss.add(__aev == JsonParser.Event.VALUE_NULL ? null : p.getString());");
                        pw.println(CLOSE_20);
                        pw.println(INDENT_20 + target + " = __ss.toArray(new String[0]);");
                    }
                    default -> {
                        // Unreachable: isSupportedComponentType rejects any other array component.
                    }
                }
            }
            default -> {
                // Unreachable: isSupportedComponentType rejects any other component kind.
            }
        }
    }

    /** Reads the current value as a leaf type. {@code ev} = event already consumed for this value. */
    private static String readLeaf(String ev, String p, String fqn) {
        return switch (fqn) {
            case JAVA_LANG_STRING -> p + ".getString()";
            case JAVA_LANG_INTEGER -> p + GET_INT;
            case JAVA_LANG_LONG -> p + ".getLong()";
            case JAVA_LANG_DOUBLE, JAVA_LANG_FLOAT -> p + ".getBigDecimal().doubleValue()";
            case JAVA_LANG_SHORT -> "(short) " + p + GET_INT;
            case JAVA_LANG_BYTE -> "(byte) " + p + GET_INT;
            case JAVA_LANG_BOOLEAN -> "(" + ev + " == JsonParser.Event.VALUE_TRUE)";
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

    /** Services already written by a previous round or an incremental build, to append to. */
    private static Set<String> readExistingServices(Filer filer) {
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
        } catch (IOException _) {
            // No services file yet on the first round: nothing to append to.
        }
        return all;
    }

    /** Whether the record a binding was generated for still exists and is still annotated {@code @JsonbStatic}. */
    private boolean stillStatic(String bindingFqn) {
        if (generatedBindings.contains(bindingFqn)) return true;
        String suffix = "$$Binding";
        if (!bindingFqn.endsWith(suffix)) return true;
        TypeElement target = processingEnv.getElementUtils()
                .getTypeElement(bindingFqn.substring(0, bindingFqn.length() - suffix.length()));
        return target != null && target.getAnnotation(JsonbStatic.class) != null;
    }

    private void writeServicesFile() {
        Filer filer = processingEnv.getFiler();
        try {
            // Read existing services (to append across multi-rounds / incremental builds)
            Set<String> all = readExistingServices(filer);
            // An incremental build keeps the entries it did not regenerate, but not one whose record is gone or no
            // longer @JsonbStatic: its binding class would be stale, or missing at run time.
            all.removeIf(binding -> !stillStatic(binding));
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
