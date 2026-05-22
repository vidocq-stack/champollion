package io.vidocq.champollion.protobuf.codegen;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;
import io.vidocq.champollion.protobuf.ProtobufStatic;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
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
 * Annotation Processor pour {@link ProtobufStatic}.
 *
 * <p>Pour chaque record annoté, génère deux sources :</p>
 * <ul>
 *   <li>{@code <FQN>$$Parser.java} — implémente {@code Parser<T>} via un
 *       switch sur le {@code field_number} sans réflexion, MethodHandles,
 *       ni ClassLoader lookup. Compatible AOT (GraalVM, Leyden CDS).</li>
 *   <li>{@code <FQN>$$ParserProvider.java} — implémente
 *       {@code ParserProvider}, retourne le parser ci-dessus si le type
 *       demandé matche, sinon {@code null}.</li>
 * </ul>
 *
 * <p>En fin de traitement, écrit le service file
 * {@code META-INF/services/io.vidocq.champollion.protobuf.ParserProvider}
 * avec une ligne par provider généré ; le runtime
 * {@link io.vidocq.champollion.protobuf.Protobuf#parser(Class)} préfère
 * ces providers au runtime reflectif.</p>
 *
 * <p>M3.1 — couvre scalaires (int32/int64/uint32/uint64/sint32/sint64,
 * fixed32/64, sfixed32/64, float, double, bool, string, bytes), enum
 * (via {@code values()[ordinal]}) et repeated scalaire (packed lu via
 * {@code pushLimit}, expanded toléré). Messages imbriqués délégués à
 * {@code Protobuf.parser(NestedClass.class)} pour récursivité — cela
 * laisse le runtime résoudre via ServiceLoader si le nested est aussi
 * {@code @ProtobufStatic}.</p>
 */
@SupportedAnnotationTypes("io.vidocq.champollion.protobuf.ProtobufStatic")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class ProtobufStaticProcessor extends AbstractProcessor {

    private static final String SERVICE_FILE = "META-INF/services/io.vidocq.champollion.protobuf.ParserProvider";
    private final Set<String> providers = new LinkedHashSet<>();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Filer filer = processingEnv.getFiler();
        Messager messager = processingEnv.getMessager();
        for (Element e : roundEnv.getElementsAnnotatedWith(ProtobufStatic.class)) {
            if (e.getKind() != ElementKind.RECORD) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "@ProtobufStatic only supports records.", e);
                continue;
            }
            if (e.getAnnotation(ProtobufMessage.class) == null) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "@ProtobufStatic record must also be @ProtobufMessage.", e);
                continue;
            }
            TypeElement type = (TypeElement) e;
            try {
                emitParser(type, filer);
                String providerFqn = emitParserProvider(type, filer);
                providers.add(providerFqn);
            } catch (IOException ex) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "Failed to emit static parser for " + type + ": " + ex, e);
            }
        }
        if (roundEnv.processingOver() && !providers.isEmpty()) {
            try {
                writeServiceFile(filer);
            } catch (IOException ex) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "Failed to write service file: " + ex);
            }
        }
        return true;
    }

    // ============================================================ Parser emission

    private void emitParser(TypeElement type, Filer filer) throws IOException {
        String pkg = packageOf(type);
        String simple = type.getSimpleName().toString();
        String parserSimple = simple + "$$Parser";
        String parserFqn = pkg.isEmpty() ? parserSimple : pkg + "." + parserSimple;

        List<? extends RecordComponentElement> comps = type.getRecordComponents();

        JavaFileObject src = filer.createSourceFile(parserFqn, type);
        try (Writer w = src.openWriter(); PrintWriter pw = new PrintWriter(w)) {
            if (!pkg.isEmpty()) pw.println("package " + pkg + ";");
            pw.println();
            pw.println("import io.vidocq.champollion.protobuf.CodedInputStream;");
            pw.println("import io.vidocq.champollion.protobuf.Parser;");
            pw.println("import io.vidocq.champollion.protobuf.Protobuf;");
            pw.println("import io.vidocq.champollion.protobuf.WireFormat;");
            pw.println("import java.io.IOException;");
            pw.println("import java.util.ArrayList;");
            pw.println("import java.util.List;");
            pw.println();
            pw.println("/** Generated by ProtobufStaticProcessor — do not edit. */");
            pw.println("public final class " + parserSimple
                    + " implements Parser<" + simple + "> {");
            pw.println();
            pw.println("    @Override");
            pw.println("    public " + simple + " parseFrom(CodedInputStream in) throws IOException {");
            // Variables locales (une par champ, dans l'ordre du record).
            for (RecordComponentElement rc : comps) {
                ProtobufField pf = rc.getAnnotation(ProtobufField.class);
                if (pf == null) {
                    pw.println("        // skip " + rc.getSimpleName() + " (no @ProtobufField)");
                    continue;
                }
                pw.println("        " + localDecl(rc, pf));
            }
            pw.println("        while (true) {");
            pw.println("            int tag = in.readTag();");
            pw.println("            if (tag == 0) break;");
            pw.println("            int fn = WireFormat.getTagFieldNumber(tag);");
            pw.println("            int wt = WireFormat.getTagWireType(tag);");
            pw.println("            switch (fn) {");
            for (RecordComponentElement rc : comps) {
                ProtobufField pf = rc.getAnnotation(ProtobufField.class);
                if (pf == null) continue;
                pw.println("                case " + pf.number() + ": {");
                emitFieldRead(pw, rc, pf);
                pw.println("                    break;");
                pw.println("                }");
            }
            pw.println("                default: in.skipField(tag);");
            pw.println("            }");
            pw.println("        }");
            // Construction du record
            pw.print("        return new " + simple + "(");
            boolean first = true;
            for (RecordComponentElement rc : comps) {
                if (!first) pw.print(", ");
                first = false;
                pw.print(rc.getSimpleName());
            }
            pw.println(");");
            pw.println("    }");
            pw.println();
            // Helper pour les nested messages : limit + recursion guard.
            pw.println("    private static <X> X readNested(Class<X> cls, CodedInputStream in) throws IOException {");
            pw.println("        int sz = in.readRawVarint32();");
            pw.println("        int ol = in.pushLimit(sz);");
            pw.println("        in.incrementRecursionDepth();");
            pw.println("        X result = Protobuf.parser(cls).parseFrom(in);");
            pw.println("        in.decrementRecursionDepth();");
            pw.println("        in.popLimit(ol);");
            pw.println("        return result;");
            pw.println("    }");
            pw.println();
            // Helper enum-lenient (proto3 forward-compat : valeurs unknown → null).
            pw.println("    private static <E> E lookupEnumOrNull(E[] values, int ordinal) {");
            pw.println("        if (ordinal < 0 || ordinal >= values.length) return null;");
            pw.println("        return values[ordinal];");
            pw.println("    }");
            pw.println("}");
        }
    }

    private String emitParserProvider(TypeElement type, Filer filer) throws IOException {
        String pkg = packageOf(type);
        String simple = type.getSimpleName().toString();
        String providerSimple = simple + "$$ParserProvider";
        String providerFqn = pkg.isEmpty() ? providerSimple : pkg + "." + providerSimple;

        JavaFileObject src = filer.createSourceFile(providerFqn, type);
        try (Writer w = src.openWriter(); PrintWriter pw = new PrintWriter(w)) {
            if (!pkg.isEmpty()) pw.println("package " + pkg + ";");
            pw.println();
            pw.println("import io.vidocq.champollion.protobuf.Parser;");
            pw.println("import io.vidocq.champollion.protobuf.ParserProvider;");
            pw.println();
            pw.println("/** Generated by ProtobufStaticProcessor — do not edit. */");
            pw.println("public final class " + providerSimple + " implements ParserProvider {");
            pw.println();
            pw.println("    @Override");
            pw.println("    @SuppressWarnings(\"unchecked\")");
            pw.println("    public <T> Parser<T> parserFor(Class<T> type) {");
            pw.println("        return type == " + simple + ".class ? (Parser<T>) new "
                    + simple + "$$Parser() : null;");
            pw.println("    }");
            pw.println("}");
        }
        return providerFqn;
    }

    private void writeServiceFile(Filer filer) throws IOException {
        // Lire l'existant s'il y en a un (incrémental compile).
        Set<String> all = new LinkedHashSet<>(providers);
        try {
            FileObject existing = filer.getResource(StandardLocation.CLASS_OUTPUT, "", SERVICE_FILE);
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(existing.openInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) all.add(line);
                }
            }
        } catch (IOException ignored) {
            // Pas de service file existant — on en crée un.
        }
        FileObject out = filer.createResource(StandardLocation.CLASS_OUTPUT, "", SERVICE_FILE);
        try (Writer w = out.openWriter()) {
            for (String p : all) {
                w.write(p);
                w.write('\n');
            }
        }
    }

    // ============================================================ Helpers

    private static String packageOf(TypeElement type) {
        String fqn = type.getQualifiedName().toString();
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    private String localDecl(RecordComponentElement rc, ProtobufField pf) {
        String name = rc.getSimpleName().toString();
        TypeMirror tm = rc.asType();
        if (isListType(tm)) {
            String elem = listElementJavaType(tm);
            return "java.util.List<" + boxIfPrimitive(elem) + "> " + name
                    + " = new java.util.ArrayList<>();";
        }
        // Si le record component est un type wrapper boxé (Integer, Long, etc.)
        // OU si @ProtobufField(explicitPresence=true), on initialise à null —
        // sémantique de proto2/Edition 2023 'EXPLICIT' / oneof.
        boolean boxedOrExplicit = pf.explicitPresence()
                || (tm.getKind().name().equals("DECLARED") && isWrapperType(tm));
        if (boxedOrExplicit) {
            return javaTypeName(tm) + " " + name + " = null;";
        }
        return switch (pf.type()) {
            case INT32, UINT32, SINT32, FIXED32, SFIXED32 -> "int " + name + " = 0;";
            case INT64, UINT64, SINT64, FIXED64, SFIXED64 -> "long " + name + " = 0L;";
            case FLOAT -> "float " + name + " = 0.0f;";
            case DOUBLE -> "double " + name + " = 0.0;";
            case BOOL -> "boolean " + name + " = false;";
            case STRING -> "String " + name + " = \"\";";
            case BYTES -> "byte[] " + name + " = new byte[0];";
            case ENUM -> javaTypeName(tm) + " " + name + " = " + javaTypeName(tm)
                    + ".values()[0];";
            case MESSAGE -> javaTypeName(tm) + " " + name + " = null;";
            case MAP -> "java.util.Map<Object,Object> " + name + " = new java.util.LinkedHashMap<>();";
        };
    }

    private static boolean isWrapperType(TypeMirror tm) {
        String n = tm.toString();
        return n.equals("java.lang.Integer") || n.equals("java.lang.Long")
                || n.equals("java.lang.Boolean") || n.equals("java.lang.Float")
                || n.equals("java.lang.Double") || n.equals("java.lang.Short")
                || n.equals("java.lang.Byte") || n.equals("java.lang.Character");
    }

    private void emitFieldRead(PrintWriter pw, RecordComponentElement rc, ProtobufField pf) {
        String name = rc.getSimpleName().toString();
        TypeMirror tm = rc.asType();
        boolean repeated = isListType(tm);
        if (repeated) {
            String elem = listElementJavaType(tm);
            if (pf.type().packable()) {
                // Packed payload : un seul tag LEN, payload = concat ; tolère
                // aussi la forme expanded (un tag par valeur).
                pw.println("                    if (wt == WireFormat.WIRETYPE_LENGTH_DELIMITED) {");
                pw.println("                        int sz = in.readRawVarint32();");
                pw.println("                        int ol = in.pushLimit(sz);");
                pw.println("                        while (!in.isAtEnd()) {");
                pw.println("                            " + name + ".add(" + scalarRead(pf.type(), elem) + ");");
                pw.println("                        }");
                pw.println("                        in.popLimit(ol);");
                pw.println("                    } else {");
                pw.println("                        " + name + ".add(" + scalarRead(pf.type(), elem) + ");");
                pw.println("                    }");
            } else {
                // STRING/BYTES/MESSAGE non packable : un tag par valeur.
                pw.println("                    " + name + ".add(" + scalarRead(pf.type(), elem) + ");");
            }
            return;
        }
        pw.println("                    " + name + " = " + scalarRead(pf.type(), javaTypeName(tm)) + ";");
    }

    private static String scalarRead(FieldType type, String javaElemType) {
        return switch (type) {
            case INT32 -> "in.readInt32()";
            case INT64 -> "in.readInt64()";
            case UINT32 -> "in.readUInt32()";
            case UINT64 -> "in.readUInt64()";
            case SINT32 -> "in.readSInt32()";
            case SINT64 -> "in.readSInt64()";
            case FIXED32 -> "in.readFixed32()";
            case SFIXED32 -> "in.readSFixed32()";
            case FLOAT -> "in.readFloat()";
            case FIXED64 -> "in.readFixed64()";
            case SFIXED64 -> "in.readSFixed64()";
            case DOUBLE -> "in.readDouble()";
            case BOOL -> "in.readBool()";
            case STRING -> "in.readStringRequireUtf8()";
            case BYTES -> "in.readBytes()";
            case ENUM -> "lookupEnumOrNull(" + javaElemType + ".values(), in.readEnum())";
            case MESSAGE -> "readNested(" + javaElemType + ".class, in)";
            // MAP non supporté en static codegen — le runtime reflectif gère ces fields.
            case MAP -> "/* MAP handled by runtime */null";
        };
    }

    static boolean isListType(TypeMirror tm) {
        if (tm.getKind() != TypeKind.DECLARED) return false;
        DeclaredType dt = (DeclaredType) tm;
        String qname = ((TypeElement) dt.asElement()).getQualifiedName().toString();
        return qname.equals("java.util.List");
    }

    private static String listElementJavaType(TypeMirror tm) {
        DeclaredType dt = (DeclaredType) tm;
        if (dt.getTypeArguments().isEmpty()) return "Object";
        return dt.getTypeArguments().get(0).toString();
    }

    private static String javaTypeName(TypeMirror tm) {
        if (tm.getKind().isPrimitive()) return tm.toString();
        if (tm.getKind() == TypeKind.DECLARED) {
            return ((TypeElement) ((DeclaredType) tm).asElement()).getQualifiedName().toString();
        }
        return tm.toString();
    }

    private static String boxIfPrimitive(String t) {
        return switch (t) {
            case "int" -> "Integer";
            case "long" -> "Long";
            case "float" -> "Float";
            case "double" -> "Double";
            case "boolean" -> "Boolean";
            default -> t;
        };
    }
}
