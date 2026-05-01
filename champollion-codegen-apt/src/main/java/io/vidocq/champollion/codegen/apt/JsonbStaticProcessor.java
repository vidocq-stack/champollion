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
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
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
 * Processeur d'annotations {@code @JsonbStatic} : génère pour chaque record annoté
 * un fichier source {@code <FQN>$$Binding.java} qui implémente
 * {@code io.vidocq.champollion.jsonb.spi.JsonbBinding<TargetType>} et accumule la
 * liste des bindings dans
 * {@code META-INF/services/io.vidocq.champollion.jsonb.spi.JsonbBinding}.
 *
 * <p>M5.2 : limité aux records dont les composants sont des types primitifs ou
 * {@link String}. Les containers (List/Map/Optional/Array) seront ajoutés en M5.3.</p>
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
        // Nested types : on doit reconstruire le nom binaire avec '$' mais le source
        // file a un nom dérivé du simple name. Pour M5.2, on n'accepte que les top-level.
        if (!(pkg instanceof PackageElement)) {
            error(record, "@JsonbStatic only supported on top-level records in M5.2");
            return;
        }
        String pkgName = pkg.getQualifiedName().toString();
        String bindingSimple = simpleName + "$$Binding";
        String bindingFqn = pkgName.isEmpty() ? bindingSimple : pkgName + "." + bindingSimple;
        String targetFqn = record.getQualifiedName().toString();

        List<? extends RecordComponentElement> comps = record.getRecordComponents();

        // Validation : composants supportés en M5.2.
        for (var c : comps) {
            if (!isSupportedComponentType(c.asType())) {
                error(record,
                        "@JsonbStatic component type not supported in M5.2: " + c.asType()
                                + " (only primitives + String supported — containers in M5.3)");
                return;
            }
        }

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
     * M5.3 : composants supportés = primitives, String, List&lt;X&gt;, Optional&lt;X&gt;,
     * arrays primitifs et String[].
     */
    private static boolean isSupportedComponentType(TypeMirror tm) {
        return switch (tm.getKind()) {
            case INT, LONG, DOUBLE, FLOAT, SHORT, BYTE, BOOLEAN -> true;
            case DECLARED -> {
                String fqn = ((DeclaredType) tm).asElement().toString();
                if ("java.lang.String".equals(fqn)) yield true;
                if ("java.util.List".equals(fqn) || "java.util.Optional".equals(fqn)) {
                    var args = ((DeclaredType) tm).getTypeArguments();
                    if (args.size() != 1) yield false;
                    yield isLeafType(args.get(0));
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

    /** Types "feuille" autorisés à l'intérieur de containers : primitives wrappés, String. */
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
            emitWriteComponent(pw, c);
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
        pw.println("            String _key = p.getString();");
        pw.println("            JsonParser.Event _ev = p.next();");
        pw.println("            switch (_key) {");
        for (var c : comps) {
            String name = c.getSimpleName().toString();
            pw.println("                case \"" + name + "\" -> {");
            emitReadComponent(pw, c, "_" + name);
            pw.println("                }");
        }
        pw.println("                default -> {");
        pw.println("                    if (_ev == JsonParser.Event.START_OBJECT) p.skipObject();");
        pw.println("                    else if (_ev == JsonParser.Event.START_ARRAY) p.skipArray();");
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
        pw.println("}");
    }

    // ============================================================
    // Code generation helpers (write side)
    // ============================================================

    private void emitWriteComponent(PrintWriter pw, RecordComponentElement c) {
        String name = c.getSimpleName().toString();
        String accessor = "value." + name + "()";
        TypeMirror tm = c.asType();
        switch (tm.getKind()) {
            case INT, SHORT, BYTE -> pw.println("        g.write(\"" + name + "\", (int) " + accessor + ");");
            case LONG -> pw.println("        g.write(\"" + name + "\", " + accessor + ");");
            case DOUBLE, FLOAT -> pw.println("        g.write(\"" + name + "\", (double) " + accessor + ");");
            case BOOLEAN -> pw.println("        g.write(\"" + name + "\", " + accessor + ");");
            case DECLARED -> {
                String fqn = ((DeclaredType) tm).asElement().toString();
                if ("java.lang.String".equals(fqn)) {
                    pw.println("        if (" + accessor + " != null) g.write(\"" + name + "\", " + accessor + ");");
                } else if ("java.util.List".equals(fqn)) {
                    TypeMirror elem = ((DeclaredType) tm).getTypeArguments().get(0);
                    String elemFqn = ((DeclaredType) elem).asElement().toString();
                    pw.println("        if (" + accessor + " != null) {");
                    pw.println("            g.writeKey(\"" + name + "\");");
                    pw.println("            g.writeStartArray();");
                    pw.println("            for (" + elemFqn + " _e : " + accessor + ") {");
                    pw.println("                if (_e == null) g.writeNull(); else " + writePrimitiveOrString("_e", elemFqn));
                    pw.println("            }");
                    pw.println("            g.writeEnd();");
                    pw.println("        }");
                } else if ("java.util.Optional".equals(fqn)) {
                    TypeMirror inner = ((DeclaredType) tm).getTypeArguments().get(0);
                    String innerFqn = ((DeclaredType) inner).asElement().toString();
                    pw.println("        if (" + accessor + " != null && " + accessor + ".isPresent()) {");
                    pw.println("            " + innerFqn + " _v = " + accessor + ".get();");
                    pw.println("            g.writeKey(\"" + name + "\");");
                    pw.println("            " + writePrimitiveOrString("_v", innerFqn));
                    pw.println("        }");
                }
            }
            case ARRAY -> {
                TypeMirror comp = ((ArrayType) tm).getComponentType();
                pw.println("        if (" + accessor + " != null) {");
                pw.println("            g.writeKey(\"" + name + "\");");
                pw.println("            g.writeStartArray();");
                switch (comp.getKind()) {
                    case INT -> pw.println("            for (int _e : " + accessor + ") g.write(_e);");
                    case LONG -> pw.println("            for (long _e : " + accessor + ") g.write(_e);");
                    case DOUBLE -> pw.println("            for (double _e : " + accessor + ") g.write(_e);");
                    case BOOLEAN -> pw.println("            for (boolean _e : " + accessor + ") g.write(_e);");
                    case DECLARED -> {
                        // String[]
                        pw.println("            for (String _e : " + accessor + ") {");
                        pw.println("                if (_e == null) g.writeNull(); else g.write(_e);");
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

    /** Émet une statement {@code g.write(<expr>);} adaptée au type de la valeur scalaire. */
    private static String writePrimitiveOrString(String expr, String fqn) {
        return switch (fqn) {
            case "java.lang.String" -> "g.write(" + expr + ");";
            case "java.lang.Integer", "java.lang.Short", "java.lang.Byte" -> "g.write(" + expr + ".intValue());";
            case "java.lang.Long" -> "g.write(" + expr + ".longValue());";
            case "java.lang.Double", "java.lang.Float" -> "g.write(" + expr + ".doubleValue());";
            case "java.lang.Boolean" -> "g.write(" + expr + ".booleanValue());";
            default -> "g.write(String.valueOf(" + expr + "));";
        };
    }

    // ============================================================
    // Code generation helpers (read side)
    // ============================================================

    private void emitReadDeclaration(PrintWriter pw, RecordComponentElement c) {
        String name = c.getSimpleName().toString();
        TypeMirror tm = c.asType();
        switch (tm.getKind()) {
            case INT, SHORT, BYTE -> pw.println("        int _" + name + " = 0;");
            case LONG -> pw.println("        long _" + name + " = 0L;");
            case DOUBLE -> pw.println("        double _" + name + " = 0.0;");
            case FLOAT -> pw.println("        float _" + name + " = 0.0f;");
            case BOOLEAN -> pw.println("        boolean _" + name + " = false;");
            case DECLARED -> {
                String fqn = ((DeclaredType) tm).asElement().toString();
                if ("java.util.List".equals(fqn)) {
                    String elemFqn = ((DeclaredType) ((DeclaredType) tm).getTypeArguments().get(0)).asElement().toString();
                    pw.println("        java.util.List<" + elemFqn + "> _" + name + " = null;");
                } else if ("java.util.Optional".equals(fqn)) {
                    String inner = ((DeclaredType) ((DeclaredType) tm).getTypeArguments().get(0)).asElement().toString();
                    pw.println("        java.util.Optional<" + inner + "> _" + name + " = java.util.Optional.empty();");
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
            case INT -> pw.println("                    if (_ev != JsonParser.Event.VALUE_NULL) " + target + " = p.getInt();");
            case SHORT -> pw.println("                    if (_ev != JsonParser.Event.VALUE_NULL) " + target + " = (short) p.getInt();");
            case BYTE -> pw.println("                    if (_ev != JsonParser.Event.VALUE_NULL) " + target + " = (byte) p.getInt();");
            case LONG -> pw.println("                    if (_ev != JsonParser.Event.VALUE_NULL) " + target + " = p.getLong();");
            case DOUBLE -> pw.println("                    if (_ev != JsonParser.Event.VALUE_NULL) " + target + " = p.getBigDecimal().doubleValue();");
            case FLOAT -> pw.println("                    if (_ev != JsonParser.Event.VALUE_NULL) " + target + " = p.getBigDecimal().floatValue();");
            case BOOLEAN -> pw.println("                    " + target + " = (_ev == JsonParser.Event.VALUE_TRUE);");
            case DECLARED -> {
                String fqn = ((DeclaredType) tm).asElement().toString();
                if ("java.lang.String".equals(fqn)) {
                    pw.println("                    if (_ev != JsonParser.Event.VALUE_NULL) " + target + " = p.getString();");
                } else if ("java.util.List".equals(fqn)) {
                    String elemFqn = ((DeclaredType) ((DeclaredType) tm).getTypeArguments().get(0)).asElement().toString();
                    pw.println("                    if (_ev != JsonParser.Event.START_ARRAY) throw new IllegalStateException(\"Expected START_ARRAY\");");
                    pw.println("                    java.util.ArrayList<" + elemFqn + "> _list = new java.util.ArrayList<>();");
                    pw.println("                    JsonParser.Event _aev;");
                    pw.println("                    while ((_aev = p.next()) != JsonParser.Event.END_ARRAY) {");
                    pw.println("                        if (_aev == JsonParser.Event.VALUE_NULL) _list.add(null);");
                    pw.println("                        else _list.add(" + readLeaf("_aev", "p", elemFqn) + ");");
                    pw.println("                    }");
                    pw.println("                    " + target + " = _list;");
                } else if ("java.util.Optional".equals(fqn)) {
                    String innerFqn = ((DeclaredType) ((DeclaredType) tm).getTypeArguments().get(0)).asElement().toString();
                    pw.println("                    if (_ev == JsonParser.Event.VALUE_NULL) " + target + " = java.util.Optional.empty();");
                    pw.println("                    else " + target + " = java.util.Optional.of(" + readLeaf("_ev", "p", innerFqn) + ");");
                }
            }
            case ARRAY -> {
                TypeMirror comp = ((ArrayType) tm).getComponentType();
                pw.println("                    if (_ev != JsonParser.Event.START_ARRAY) throw new IllegalStateException(\"Expected START_ARRAY\");");
                switch (comp.getKind()) {
                    case INT -> {
                        pw.println("                    java.util.ArrayList<Integer> _ints = new java.util.ArrayList<>();");
                        pw.println("                    JsonParser.Event _aev;");
                        pw.println("                    while ((_aev = p.next()) != JsonParser.Event.END_ARRAY) _ints.add(p.getInt());");
                        pw.println("                    int[] _arr = new int[_ints.size()];");
                        pw.println("                    for (int _i = 0; _i < _arr.length; _i++) _arr[_i] = _ints.get(_i);");
                        pw.println("                    " + target + " = _arr;");
                    }
                    case LONG -> {
                        pw.println("                    java.util.ArrayList<Long> _longs = new java.util.ArrayList<>();");
                        pw.println("                    JsonParser.Event _aev;");
                        pw.println("                    while ((_aev = p.next()) != JsonParser.Event.END_ARRAY) _longs.add(p.getLong());");
                        pw.println("                    long[] _arr = new long[_longs.size()];");
                        pw.println("                    for (int _i = 0; _i < _arr.length; _i++) _arr[_i] = _longs.get(_i);");
                        pw.println("                    " + target + " = _arr;");
                    }
                    case DOUBLE -> {
                        pw.println("                    java.util.ArrayList<Double> _doubles = new java.util.ArrayList<>();");
                        pw.println("                    JsonParser.Event _aev;");
                        pw.println("                    while ((_aev = p.next()) != JsonParser.Event.END_ARRAY) _doubles.add(p.getBigDecimal().doubleValue());");
                        pw.println("                    double[] _arr = new double[_doubles.size()];");
                        pw.println("                    for (int _i = 0; _i < _arr.length; _i++) _arr[_i] = _doubles.get(_i);");
                        pw.println("                    " + target + " = _arr;");
                    }
                    case BOOLEAN -> {
                        pw.println("                    java.util.ArrayList<Boolean> _bools = new java.util.ArrayList<>();");
                        pw.println("                    JsonParser.Event _aev;");
                        pw.println("                    while ((_aev = p.next()) != JsonParser.Event.END_ARRAY) _bools.add(_aev == JsonParser.Event.VALUE_TRUE);");
                        pw.println("                    boolean[] _arr = new boolean[_bools.size()];");
                        pw.println("                    for (int _i = 0; _i < _arr.length; _i++) _arr[_i] = _bools.get(_i);");
                        pw.println("                    " + target + " = _arr;");
                    }
                    case DECLARED -> {
                        // String[]
                        pw.println("                    java.util.ArrayList<String> _ss = new java.util.ArrayList<>();");
                        pw.println("                    JsonParser.Event _aev;");
                        pw.println("                    while ((_aev = p.next()) != JsonParser.Event.END_ARRAY) {");
                        pw.println("                        _ss.add(_aev == JsonParser.Event.VALUE_NULL ? null : p.getString());");
                        pw.println("                    }");
                        pw.println("                    " + target + " = _ss.toArray(new String[0]);");
                    }
                    default -> {}
                }
            }
            default -> {}
        }
    }

    /** Lit la valeur courante en tant que type leaf. {@code ev} = event déjà consommé pour cette valeur. */
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

    private void writeServicesFile() {
        Filer filer = processingEnv.getFiler();
        try {
            // Lecture des services existants (pour append en multi-rounds / incrémental)
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
