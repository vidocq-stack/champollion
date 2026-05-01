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

    /** M5.2 : composants supportés = primitives + String. */
    private static boolean isSupportedComponentType(TypeMirror tm) {
        return switch (tm.getKind()) {
            case INT, LONG, DOUBLE, FLOAT, SHORT, BYTE, BOOLEAN -> true;
            case DECLARED -> "java.lang.String".equals(tm.toString());
            default -> false;
        };
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
            String name = c.getSimpleName().toString();
            String accessor = "value." + name + "()";
            String kind = c.asType().getKind().toString();
            switch (c.asType().getKind()) {
                case INT, SHORT, BYTE -> pw.println("        g.write(\"" + name + "\", (int) " + accessor + ");");
                case LONG -> pw.println("        g.write(\"" + name + "\", " + accessor + ");");
                case DOUBLE, FLOAT -> pw.println("        g.write(\"" + name + "\", (double) " + accessor + ");");
                case BOOLEAN -> pw.println("        g.write(\"" + name + "\", " + accessor + ");");
                default -> { // String : null = omit (cohérent runtime §3.14.2)
                    pw.println("        if (" + accessor + " != null) g.write(\"" + name + "\", " + accessor + ");");
                }
            }
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
        // Initialise les variables locales avec les défauts du record.
        for (var c : comps) {
            String name = c.getSimpleName().toString();
            switch (c.asType().getKind()) {
                case INT, SHORT, BYTE -> pw.println("        int _" + name + " = 0;");
                case LONG -> pw.println("        long _" + name + " = 0L;");
                case DOUBLE -> pw.println("        double _" + name + " = 0.0;");
                case FLOAT -> pw.println("        float _" + name + " = 0.0f;");
                case BOOLEAN -> pw.println("        boolean _" + name + " = false;");
                default -> pw.println("        String _" + name + " = null;");
            }
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
            switch (c.asType().getKind()) {
                case INT -> pw.println("                    if (_ev != JsonParser.Event.VALUE_NULL) _" + name + " = p.getInt();");
                case SHORT -> pw.println("                    if (_ev != JsonParser.Event.VALUE_NULL) _" + name + " = (short) p.getInt();");
                case BYTE -> pw.println("                    if (_ev != JsonParser.Event.VALUE_NULL) _" + name + " = (byte) p.getInt();");
                case LONG -> pw.println("                    if (_ev != JsonParser.Event.VALUE_NULL) _" + name + " = p.getLong();");
                case DOUBLE -> pw.println("                    if (_ev != JsonParser.Event.VALUE_NULL) _" + name + " = p.getBigDecimal().doubleValue();");
                case FLOAT -> pw.println("                    if (_ev != JsonParser.Event.VALUE_NULL) _" + name + " = p.getBigDecimal().floatValue();");
                case BOOLEAN -> pw.println("                    _" + name + " = (_ev == JsonParser.Event.VALUE_TRUE);");
                default -> pw.println("                    if (_ev != JsonParser.Event.VALUE_NULL) _" + name + " = p.getString();");
            }
            pw.println("                }");
        }
        pw.println("                default -> {");
        pw.println("                    if (_ev == JsonParser.Event.START_OBJECT) p.skipObject();");
        pw.println("                    else if (_ev == JsonParser.Event.START_ARRAY) p.skipArray();");
        pw.println("                }");
        pw.println("            }");
        pw.println("        }");
        // Construction record.
        pw.print("        return new " + targetFqn + "(");
        for (int i = 0; i < comps.size(); i++) {
            if (i > 0) pw.print(", ");
            pw.print("_" + comps.get(i).getSimpleName());
        }
        pw.println(");");
        pw.println("    }");
        pw.println("}");
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
