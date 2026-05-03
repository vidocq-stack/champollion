package io.vidocq.champollion.codegen.apt;

import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.List;

/**
 * Émet directement le bytecode du binding statique via {@link ClassFile} (JDK 24+).
 *
 * <p>Chemin "fast path" pour les records dont tous les composants sont primitifs
 * ou {@link String}. Les types complexes (containers, nested records) font basculer
 * le {@link JsonbStaticProcessor} sur la génération de sources.</p>
 *
 * <p>Format des classes générées :</p>
 * <pre>{@code
 * public final class <Pkg>.<Simple>$$Binding implements JsonbBinding {
 *     public Class type() { return <Target>.class; }
 *     public void write(JsonGenerator g, Object v) {
 *         if (v == null) { g.writeNull(); return; }
 *         <Target> t = (<Target>) v;
 *         g.writeStartObject();
 *         g.write("c1", t.c1());
 *         ...
 *         g.writeEnd();
 *     }
 *     public Object read(JsonParser p) {
 *         <init locals>
 *         JsonParser.Event e = p.next();
 *         if (e == VALUE_NULL) return null;
 *         <consume START_OBJECT>
 *         while ((e = p.next()) != END_OBJECT) {
 *             String key = p.getString();
 *             p.next();
 *             <switch on key, set locals>
 *         }
 *         return new <Target>(...);
 *     }
 * }
 * }</pre>
 *
 * <p>Note : on n'émet pas de bridges generics — l'effacement de
 * {@code JsonbBinding<T>} donne {@code Class type()}, {@code void write(JsonGenerator, Object)},
 * {@code Object read(JsonParser)}, exactement les signatures qu'on génère.</p>
 */
final class BindingBytecodeEmitter {

    private static final ClassDesc CD_OBJECT = ConstantDescs.CD_Object;
    private static final ClassDesc CD_STRING = ConstantDescs.CD_String;
    private static final ClassDesc CD_CLASS = ConstantDescs.CD_Class;
    private static final ClassDesc CD_JSONB_BINDING = ClassDesc.of("io.vidocq.champollion.jsonb.spi.JsonbBinding");
    private static final ClassDesc CD_JSON_GENERATOR = ClassDesc.of("jakarta.json.stream.JsonGenerator");
    private static final ClassDesc CD_JSON_PARSER = ClassDesc.of("jakarta.json.stream.JsonParser");
    private static final ClassDesc CD_JSON_PARSER_EVENT = ClassDesc.of("jakarta.json.stream.JsonParser$Event");
    private static final ClassDesc CD_ARRAYLIST = ClassDesc.of("java.util.ArrayList");
    private static final ClassDesc CD_BIGDECIMAL = ClassDesc.of("java.math.BigDecimal");
    private static final ClassDesc CD_OPTIONAL = ClassDesc.of("java.util.Optional");

    /**
     * Slots locaux fixes utilisés par les méthodes de container côté <em>write</em>.
     * Les locals 0-3 sont occupés par {@code this/g/v/t}.
     */
    private static final int W_TMP = 4;
    private static final int W_IDX = 5;
    private static final int W_LEN = 6;

    /**
     * Test : tous les composants sont-ils dans le subset bytecode ?
     * Couverture actuelle : primitives, String, enums, arrays {int[]/long[]/double[]/boolean[]/String[]}.
     */
    static boolean eligible(List<? extends RecordComponentElement> comps) {
        for (var c : comps) {
            TypeMirror tm = c.asType();
            switch (tm.getKind()) {
                case INT, LONG, DOUBLE, FLOAT, SHORT, BYTE, BOOLEAN -> { /* OK */ }
                case DECLARED -> {
                    DeclaredType dt = (DeclaredType) tm;
                    String fqn = dt.asElement().toString();
                    if ("java.lang.String".equals(fqn)) continue;
                    if (dt.asElement().getKind() == javax.lang.model.element.ElementKind.ENUM) continue;
                    if ("java.util.Optional".equals(fqn)) {
                        var args = dt.getTypeArguments();
                        if (args.size() != 1) return false;
                        TypeMirror inner = args.get(0);
                        boolean okInner = inner.getKind() == TypeKind.DECLARED
                                && (("java.lang.String".equals(((DeclaredType) inner).asElement().toString()))
                                    || ((DeclaredType) inner).asElement().getKind()
                                            == javax.lang.model.element.ElementKind.ENUM
                                    || isLeafBox(((DeclaredType) inner).asElement().toString()));
                        if (!okInner) return false;
                        continue;
                    }
                    return false;
                }
                case ARRAY -> {
                    TypeMirror comp = ((ArrayType) tm).getComponentType();
                    boolean ok = switch (comp.getKind()) {
                        case INT, LONG, DOUBLE, BOOLEAN -> true;
                        case DECLARED -> "java.lang.String".equals(comp.toString());
                        default -> false;
                    };
                    if (!ok) return false;
                }
                default -> { return false; }
            }
        }
        return true;
    }

    private static boolean isEnum(TypeMirror tm) {
        return tm.getKind() == TypeKind.DECLARED
                && ((DeclaredType) tm).asElement().getKind() == javax.lang.model.element.ElementKind.ENUM;
    }

    private static boolean isLeafBox(String fqn) {
        return switch (fqn) {
            case "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float",
                 "java.lang.Short", "java.lang.Byte", "java.lang.Boolean" -> true;
            default -> false;
        };
    }

    /** Émet le bytecode du binding pour {@code record}. */
    static byte[] emit(TypeElement record, String bindingFqn) {
        String targetFqn = record.getQualifiedName().toString();
        ClassDesc CD_TARGET = ClassDesc.of(targetFqn);
        ClassDesc CD_BINDING = ClassDesc.of(bindingFqn);
        List<? extends RecordComponentElement> comps = record.getRecordComponents();

        return ClassFile.of().build(CD_BINDING, cb -> {
            cb.withVersion(ClassFile.JAVA_25_VERSION, 0);
            cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
            cb.withSuperclass(CD_OBJECT);
            cb.withInterfaceSymbols(CD_JSONB_BINDING);

            emitDefaultCtor(cb);
            emitTypeMethod(cb, CD_TARGET);
            emitWrite(cb, CD_TARGET, comps);
            emitRead(cb, CD_TARGET, comps);
        });
    }

    // ============================================================
    // <init>()V
    // ============================================================

    private static void emitDefaultCtor(ClassBuilder cb) {
        cb.withMethodBody("<init>", MethodTypeDesc.of(ConstantDescs.CD_void),
                ClassFile.ACC_PUBLIC, code -> code
                        .aload(0)
                        .invokespecial(CD_OBJECT, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void))
                        .return_());
    }

    // ============================================================
    // public Class type() { return <Target>.class; }
    // ============================================================

    private static void emitTypeMethod(ClassBuilder cb, ClassDesc CD_TARGET) {
        cb.withMethodBody("type", MethodTypeDesc.of(CD_CLASS),
                ClassFile.ACC_PUBLIC, code -> code
                        .ldc(CD_TARGET)
                        .areturn());
    }

    // ============================================================
    // public void write(JsonGenerator g, Object v)
    // ============================================================

    private static void emitWrite(ClassBuilder cb, ClassDesc CD_TARGET, List<? extends RecordComponentElement> comps) {
        // Locals : 0 this, 1 g, 2 v(Object), 3 t(Target)
        cb.withMethodBody("write",
                MethodTypeDesc.of(ConstantDescs.CD_void, CD_JSON_GENERATOR, CD_OBJECT),
                ClassFile.ACC_PUBLIC, code -> {
                    Label notNull = code.newLabel();
                    // if (v != null) goto notNull
                    code.aload(2);
                    code.ifnonnull(notNull);
                    // g.writeNull(); return;
                    code.aload(1);
                    code.invokeinterface(CD_JSON_GENERATOR, "writeNull",
                            MethodTypeDesc.of(CD_JSON_GENERATOR));
                    code.pop();
                    code.return_();

                    code.labelBinding(notNull);
                    // Target t = (Target) v;
                    code.aload(2);
                    code.checkcast(CD_TARGET);
                    code.astore(3);

                    // g.writeStartObject();
                    code.aload(1);
                    code.invokeinterface(CD_JSON_GENERATOR, "writeStartObject",
                            MethodTypeDesc.of(CD_JSON_GENERATOR));
                    code.pop();

                    for (var c : comps) {
                        emitWriteComponent(code, CD_TARGET, c);
                    }

                    // g.writeEnd();
                    code.aload(1);
                    code.invokeinterface(CD_JSON_GENERATOR, "writeEnd",
                            MethodTypeDesc.of(CD_JSON_GENERATOR));
                    code.pop();
                    code.return_();
                });
    }

    private static void emitWriteComponent(CodeBuilder code, ClassDesc CD_TARGET, RecordComponentElement c) {
        String name = c.getSimpleName().toString();
        TypeMirror tm = c.asType();

        switch (tm.getKind()) {
            case INT, SHORT, BYTE -> {
                // g.write("name", t.<name>())
                code.aload(1);
                code.ldc(name);
                code.aload(3);
                code.invokevirtual(CD_TARGET, name, MethodTypeDesc.of(toCD(tm)));
                if (tm.getKind() == TypeKind.SHORT || tm.getKind() == TypeKind.BYTE) {
                    // Already pushed as int
                }
                code.invokeinterface(CD_JSON_GENERATOR, "write",
                        MethodTypeDesc.of(CD_JSON_GENERATOR, CD_STRING, ConstantDescs.CD_int));
                code.pop();
            }
            case LONG -> {
                code.aload(1);
                code.ldc(name);
                code.aload(3);
                code.invokevirtual(CD_TARGET, name, MethodTypeDesc.of(ConstantDescs.CD_long));
                code.invokeinterface(CD_JSON_GENERATOR, "write",
                        MethodTypeDesc.of(CD_JSON_GENERATOR, CD_STRING, ConstantDescs.CD_long));
                code.pop();
            }
            case DOUBLE -> {
                code.aload(1);
                code.ldc(name);
                code.aload(3);
                code.invokevirtual(CD_TARGET, name, MethodTypeDesc.of(ConstantDescs.CD_double));
                code.invokeinterface(CD_JSON_GENERATOR, "write",
                        MethodTypeDesc.of(CD_JSON_GENERATOR, CD_STRING, ConstantDescs.CD_double));
                code.pop();
            }
            case FLOAT -> {
                // g.write(name, (double) t.name())
                code.aload(1);
                code.ldc(name);
                code.aload(3);
                code.invokevirtual(CD_TARGET, name, MethodTypeDesc.of(ConstantDescs.CD_float));
                code.f2d();
                code.invokeinterface(CD_JSON_GENERATOR, "write",
                        MethodTypeDesc.of(CD_JSON_GENERATOR, CD_STRING, ConstantDescs.CD_double));
                code.pop();
            }
            case BOOLEAN -> {
                code.aload(1);
                code.ldc(name);
                code.aload(3);
                code.invokevirtual(CD_TARGET, name, MethodTypeDesc.of(ConstantDescs.CD_boolean));
                code.invokeinterface(CD_JSON_GENERATOR, "write",
                        MethodTypeDesc.of(CD_JSON_GENERATOR, CD_STRING, ConstantDescs.CD_boolean));
                code.pop();
            }
            case DECLARED -> {
                if (isOptional(tm)) {
                    emitWriteOptional(code, CD_TARGET, name, (DeclaredType) tm);
                    return;
                }
                if (isEnum(tm)) {
                    // if (t.name() != null) g.write("name", t.<accessor>().name());
                    ClassDesc CD_ENUM = ClassDesc.of(((DeclaredType) tm).asElement().toString());
                    Label skip = code.newLabel();
                    code.aload(3);
                    code.invokevirtual(CD_TARGET, name, MethodTypeDesc.of(CD_ENUM));
                    code.ifnull(skip);
                    code.aload(1);
                    code.ldc(name);
                    code.aload(3);
                    code.invokevirtual(CD_TARGET, name, MethodTypeDesc.of(CD_ENUM));
                    code.invokevirtual(ClassDesc.of("java.lang.Enum"), "name",
                            MethodTypeDesc.of(CD_STRING));
                    code.invokeinterface(CD_JSON_GENERATOR, "write",
                            MethodTypeDesc.of(CD_JSON_GENERATOR, CD_STRING, CD_STRING));
                    code.pop();
                    code.labelBinding(skip);
                } else {
                    // String : if (t.name() != null) g.write("name", t.name());
                    Label skip = code.newLabel();
                    code.aload(3);
                    code.invokevirtual(CD_TARGET, name, MethodTypeDesc.of(CD_STRING));
                    code.ifnull(skip);
                    code.aload(1);
                    code.ldc(name);
                    code.aload(3);
                    code.invokevirtual(CD_TARGET, name, MethodTypeDesc.of(CD_STRING));
                    code.invokeinterface(CD_JSON_GENERATOR, "write",
                            MethodTypeDesc.of(CD_JSON_GENERATOR, CD_STRING, CD_STRING));
                    code.pop();
                    code.labelBinding(skip);
                }
            }
            case ARRAY -> emitWriteArray(code, CD_TARGET, name, (ArrayType) tm);
            default -> throw new IllegalStateException("Unsupported component for bytecode emitter: " + tm);
        }
    }

    /** Émet le bytecode write pour un composant array primitif ou {@code String[]}. */
    private static void emitWriteArray(CodeBuilder code, ClassDesc CD_TARGET, String name, ArrayType arrTm) {
        TypeMirror compTm = arrTm.getComponentType();
        ClassDesc CD_COMP_ARR = arrayCDOf(compTm);

        // if (t.<name>() != null) {
        Label skipNull = code.newLabel();
        code.aload(3);
        code.invokevirtual(CD_TARGET, name, MethodTypeDesc.of(CD_COMP_ARR));
        code.ifnull(skipNull);

        // g.writeKey(name);
        code.aload(1);
        code.ldc(name);
        code.invokeinterface(CD_JSON_GENERATOR, "writeKey",
                MethodTypeDesc.of(CD_JSON_GENERATOR, CD_STRING));
        code.pop();

        // g.writeStartArray();
        code.aload(1);
        code.invokeinterface(CD_JSON_GENERATOR, "writeStartArray",
                MethodTypeDesc.of(CD_JSON_GENERATOR));
        code.pop();

        // <comp>[] arr = t.<name>();
        code.aload(3);
        code.invokevirtual(CD_TARGET, name, MethodTypeDesc.of(CD_COMP_ARR));
        code.astore(W_TMP);
        // int len = arr.length;
        code.aload(W_TMP);
        code.arraylength();
        code.istore(W_LEN);
        // int i = 0;
        code.iconst_0();
        code.istore(W_IDX);

        Label loopStart = code.newLabel();
        Label loopEnd = code.newLabel();
        code.labelBinding(loopStart);
        code.iload(W_IDX);
        code.iload(W_LEN);
        code.if_icmpge(loopEnd);

        // body : g.write(arr[i])
        code.aload(1);                       // [g]
        code.aload(W_TMP);          // [g, arr]
        code.iload(W_IDX);          // [g, arr, i]
        switch (compTm.getKind()) {
            case INT -> {
                code.iaload();
                code.invokeinterface(CD_JSON_GENERATOR, "write",
                        MethodTypeDesc.of(CD_JSON_GENERATOR, ConstantDescs.CD_int));
            }
            case LONG -> {
                code.laload();
                code.invokeinterface(CD_JSON_GENERATOR, "write",
                        MethodTypeDesc.of(CD_JSON_GENERATOR, ConstantDescs.CD_long));
            }
            case DOUBLE -> {
                code.daload();
                code.invokeinterface(CD_JSON_GENERATOR, "write",
                        MethodTypeDesc.of(CD_JSON_GENERATOR, ConstantDescs.CD_double));
            }
            case BOOLEAN -> {
                code.baload();
                code.invokeinterface(CD_JSON_GENERATOR, "write",
                        MethodTypeDesc.of(CD_JSON_GENERATOR, ConstantDescs.CD_boolean));
            }
            case DECLARED -> {
                // String[] : on doit gérer null pour chaque élément
                code.aaload();              // [g, String|null]
                Label nullElem = code.newLabel();
                Label afterElem = code.newLabel();
                code.dup();
                code.ifnull(nullElem);
                // not null : g.write(s)
                code.invokeinterface(CD_JSON_GENERATOR, "write",
                        MethodTypeDesc.of(CD_JSON_GENERATOR, CD_STRING));
                code.goto_(afterElem);
                code.labelBinding(nullElem);
                // null : pop la null + g already on stack? Non, dup a laissé : [g, null]
                // On a fait dup → [g, null, null]. ifnull a sauté quand top était null,
                // mais on a aussi consommé un null par ifnull. Reste : [g, null]. Il faut
                // pop le null restant et appeler g.writeNull().
                code.pop();
                code.invokeinterface(CD_JSON_GENERATOR, "writeNull",
                        MethodTypeDesc.of(CD_JSON_GENERATOR));
                code.labelBinding(afterElem);
            }
            default -> throw new IllegalStateException();
        }
        code.pop();

        // i++
        code.iinc(W_IDX, 1);
        code.goto_(loopStart);
        code.labelBinding(loopEnd);

        // g.writeEnd();
        code.aload(1);
        code.invokeinterface(CD_JSON_GENERATOR, "writeEnd",
                MethodTypeDesc.of(CD_JSON_GENERATOR));
        code.pop();

        code.labelBinding(skipNull);
    }

    // ============================================================
    // Optional<X>
    // ============================================================

    /**
     * Émet le bytecode write pour {@code Optional<X>}.
     *
     * <p>Si l'Optional est null ou empty → pas d'émission (cohérent runtime §3.14.2).
     * Sinon, on extrait via {@code .get()} et on émet via {@code g.write(name, val)}
     * avec unbox au besoin pour les wrappers primitifs.</p>
     */
    private static void emitWriteOptional(CodeBuilder code, ClassDesc CD_TARGET, String name, DeclaredType optTm) {
        TypeMirror inner = optTm.getTypeArguments().get(0);
        String innerFqn = ((DeclaredType) inner).asElement().toString();

        Label skip = code.newLabel();

        // if (t.field() == null) skip
        code.aload(3);
        code.invokevirtual(CD_TARGET, name, MethodTypeDesc.of(CD_OPTIONAL));
        code.ifnull(skip);

        // if (!t.field().isPresent()) skip
        code.aload(3);
        code.invokevirtual(CD_TARGET, name, MethodTypeDesc.of(CD_OPTIONAL));
        code.invokevirtual(CD_OPTIONAL, "isPresent", MethodTypeDesc.of(ConstantDescs.CD_boolean));
        code.ifeq(skip);

        // g.writeKey(name);
        code.aload(1);
        code.ldc(name);
        code.invokeinterface(CD_JSON_GENERATOR, "writeKey",
                MethodTypeDesc.of(CD_JSON_GENERATOR, CD_STRING));
        code.pop();

        // g.write(t.field().get().<unbox>());
        code.aload(1);
        code.aload(3);
        code.invokevirtual(CD_TARGET, name, MethodTypeDesc.of(CD_OPTIONAL));
        code.invokevirtual(CD_OPTIONAL, "get", MethodTypeDesc.of(CD_OBJECT));

        if (isEnum(inner)) {
            ClassDesc CD_ENUM = ClassDesc.of(innerFqn);
            code.checkcast(CD_ENUM);
            code.invokevirtual(ClassDesc.of("java.lang.Enum"), "name", MethodTypeDesc.of(CD_STRING));
            code.invokeinterface(CD_JSON_GENERATOR, "write",
                    MethodTypeDesc.of(CD_JSON_GENERATOR, CD_STRING));
        } else {
            switch (innerFqn) {
                case "java.lang.String" -> {
                    code.checkcast(CD_STRING);
                    code.invokeinterface(CD_JSON_GENERATOR, "write",
                            MethodTypeDesc.of(CD_JSON_GENERATOR, CD_STRING));
                }
                case "java.lang.Integer", "java.lang.Short", "java.lang.Byte" -> {
                    code.checkcast(ConstantDescs.CD_Integer);
                    code.invokevirtual(ConstantDescs.CD_Integer, "intValue",
                            MethodTypeDesc.of(ConstantDescs.CD_int));
                    code.invokeinterface(CD_JSON_GENERATOR, "write",
                            MethodTypeDesc.of(CD_JSON_GENERATOR, ConstantDescs.CD_int));
                }
                case "java.lang.Long" -> {
                    code.checkcast(ConstantDescs.CD_Long);
                    code.invokevirtual(ConstantDescs.CD_Long, "longValue",
                            MethodTypeDesc.of(ConstantDescs.CD_long));
                    code.invokeinterface(CD_JSON_GENERATOR, "write",
                            MethodTypeDesc.of(CD_JSON_GENERATOR, ConstantDescs.CD_long));
                }
                case "java.lang.Double", "java.lang.Float" -> {
                    code.checkcast(ConstantDescs.CD_Double);
                    code.invokevirtual(ConstantDescs.CD_Double, "doubleValue",
                            MethodTypeDesc.of(ConstantDescs.CD_double));
                    code.invokeinterface(CD_JSON_GENERATOR, "write",
                            MethodTypeDesc.of(CD_JSON_GENERATOR, ConstantDescs.CD_double));
                }
                case "java.lang.Boolean" -> {
                    code.checkcast(ConstantDescs.CD_Boolean);
                    code.invokevirtual(ConstantDescs.CD_Boolean, "booleanValue",
                            MethodTypeDesc.of(ConstantDescs.CD_boolean));
                    code.invokeinterface(CD_JSON_GENERATOR, "write",
                            MethodTypeDesc.of(CD_JSON_GENERATOR, ConstantDescs.CD_boolean));
                }
                default -> throw new IllegalStateException("Unsupported Optional inner: " + innerFqn);
            }
        }
        code.pop();
        code.labelBinding(skip);
    }

    /**
     * Émet le bytecode read pour {@code Optional<X>}.
     *
     * <p>{@code _ev == VALUE_NULL} → {@code Optional.empty()}, sinon
     * {@code Optional.of(<read leaf>)} où le leaf est lu via le parser et boxé.</p>
     */
    private static void emitReadOptional(CodeBuilder code, DeclaredType optTm, int slot) {
        TypeMirror inner = optTm.getTypeArguments().get(0);
        String innerFqn = ((DeclaredType) inner).asElement().toString();

        Label nul = code.newLabel();
        Label join = code.newLabel();
        code.aload(2);
        code.getstatic(CD_JSON_PARSER_EVENT, "VALUE_NULL", CD_JSON_PARSER_EVENT);
        code.if_acmpne(nul);
        code.invokestatic(CD_OPTIONAL, "empty", MethodTypeDesc.of(CD_OPTIONAL));
        code.goto_(join);
        code.labelBinding(nul);

        // Lit la valeur leaf et la boxe en Object pour Optional.of(Object).
        if (isEnum(inner)) {
            ClassDesc CD_ENUM = ClassDesc.of(innerFqn);
            code.aload(1);
            code.invokeinterface(CD_JSON_PARSER, "getString", MethodTypeDesc.of(CD_STRING));
            code.invokestatic(CD_ENUM, "valueOf", MethodTypeDesc.of(CD_ENUM, CD_STRING));
        } else {
            switch (innerFqn) {
                case "java.lang.String" -> {
                    code.aload(1);
                    code.invokeinterface(CD_JSON_PARSER, "getString", MethodTypeDesc.of(CD_STRING));
                }
                case "java.lang.Integer", "java.lang.Short", "java.lang.Byte" -> {
                    code.aload(1);
                    code.invokeinterface(CD_JSON_PARSER, "getInt", MethodTypeDesc.of(ConstantDescs.CD_int));
                    code.invokestatic(ConstantDescs.CD_Integer, "valueOf",
                            MethodTypeDesc.of(ConstantDescs.CD_Integer, ConstantDescs.CD_int));
                }
                case "java.lang.Long" -> {
                    code.aload(1);
                    code.invokeinterface(CD_JSON_PARSER, "getLong", MethodTypeDesc.of(ConstantDescs.CD_long));
                    code.invokestatic(ConstantDescs.CD_Long, "valueOf",
                            MethodTypeDesc.of(ConstantDescs.CD_Long, ConstantDescs.CD_long));
                }
                case "java.lang.Double", "java.lang.Float" -> {
                    code.aload(1);
                    code.invokeinterface(CD_JSON_PARSER, "getBigDecimal", MethodTypeDesc.of(CD_BIGDECIMAL));
                    code.invokevirtual(CD_BIGDECIMAL, "doubleValue",
                            MethodTypeDesc.of(ConstantDescs.CD_double));
                    code.invokestatic(ConstantDescs.CD_Double, "valueOf",
                            MethodTypeDesc.of(ConstantDescs.CD_Double, ConstantDescs.CD_double));
                }
                case "java.lang.Boolean" -> {
                    Label fal = code.newLabel();
                    Label j2 = code.newLabel();
                    code.aload(2);
                    code.getstatic(CD_JSON_PARSER_EVENT, "VALUE_TRUE", CD_JSON_PARSER_EVENT);
                    code.if_acmpne(fal);
                    code.iconst_1();
                    code.goto_(j2);
                    code.labelBinding(fal);
                    code.iconst_0();
                    code.labelBinding(j2);
                    code.invokestatic(ConstantDescs.CD_Boolean, "valueOf",
                            MethodTypeDesc.of(ConstantDescs.CD_Boolean, ConstantDescs.CD_boolean));
                }
                default -> throw new IllegalStateException("Unsupported Optional inner: " + innerFqn);
            }
        }
        code.invokestatic(CD_OPTIONAL, "of", MethodTypeDesc.of(CD_OPTIONAL, CD_OBJECT));
        code.labelBinding(join);
        code.astore(slot);
    }

    /** ClassDesc d'un array dont la composante est {@code compTm}. */
    private static ClassDesc arrayCDOf(TypeMirror compTm) {
        return switch (compTm.getKind()) {
            case INT -> ConstantDescs.CD_int.arrayType();
            case LONG -> ConstantDescs.CD_long.arrayType();
            case DOUBLE -> ConstantDescs.CD_double.arrayType();
            case BOOLEAN -> ConstantDescs.CD_boolean.arrayType();
            case DECLARED -> ClassDesc.of(((DeclaredType) compTm).asElement().toString()).arrayType();
            default -> throw new IllegalStateException();
        };
    }

    // ============================================================
    // public Object read(JsonParser p)
    // ============================================================

    private static void emitRead(ClassBuilder cb, ClassDesc CD_TARGET, List<? extends RecordComponentElement> comps) {
        cb.withMethodBody("read",
                MethodTypeDesc.of(CD_OBJECT, CD_JSON_PARSER),
                ClassFile.ACC_PUBLIC, code -> {
                    // Locals layout :
                    //   0  this
                    //   1  p
                    //   2  e (Event)
                    //   3  key (String, réutilisé dans la boucle)
                    //   4..  composants un par un (slot count en fonction du type)
                    int[] slotByIdx = new int[comps.size()];
                    int slot = 4;
                    for (int i = 0; i < comps.size(); i++) {
                        slotByIdx[i] = slot;
                        slot += slotsFor(comps.get(i).asType());
                    }
                    // Base des slots temporaires utilisés par les containers (arrays...) :
                    // après le dernier slot composant pour éviter toute collision.
                    final int tmpBase = slot;

                    // Initialise les slots à leur défaut.
                    for (int i = 0; i < comps.size(); i++) {
                        emitDefaultStore(code, comps.get(i).asType(), slotByIdx[i]);
                    }

                    // e = p.next();
                    code.aload(1);
                    code.invokeinterface(CD_JSON_PARSER, "next", MethodTypeDesc.of(CD_JSON_PARSER_EVENT));
                    code.astore(2);

                    // if (e == VALUE_NULL) return null;
                    Label notNullRoot = code.newLabel();
                    code.aload(2);
                    code.getstatic(CD_JSON_PARSER_EVENT, "VALUE_NULL", CD_JSON_PARSER_EVENT);
                    code.if_acmpne(notNullRoot);
                    code.aconst_null();
                    code.areturn();
                    code.labelBinding(notNullRoot);

                    // Boucle while ((e = p.next()) != END_OBJECT)
                    Label loopStart = code.newLabel();
                    Label loopEnd = code.newLabel();
                    code.labelBinding(loopStart);
                    code.aload(1);
                    code.invokeinterface(CD_JSON_PARSER, "next", MethodTypeDesc.of(CD_JSON_PARSER_EVENT));
                    code.astore(2);
                    code.aload(2);
                    code.getstatic(CD_JSON_PARSER_EVENT, "END_OBJECT", CD_JSON_PARSER_EVENT);
                    code.if_acmpeq(loopEnd);

                    // String key = p.getString();
                    code.aload(1);
                    code.invokeinterface(CD_JSON_PARSER, "getString", MethodTypeDesc.of(CD_STRING));
                    code.astore(3);

                    // p.next() — consomme l'event de la valeur
                    code.aload(1);
                    code.invokeinterface(CD_JSON_PARSER, "next", MethodTypeDesc.of(CD_JSON_PARSER_EVENT));
                    code.astore(2);

                    // chaîne if/else if sur la clé
                    Label nextIter = code.newLabel();
                    for (int i = 0; i < comps.size(); i++) {
                        var c = comps.get(i);
                        String name = c.getSimpleName().toString();
                        Label notMatch = code.newLabel();
                        code.aload(3);
                        code.ldc(name);
                        code.invokevirtual(CD_STRING, "equals",
                                MethodTypeDesc.of(ConstantDescs.CD_boolean, CD_OBJECT));
                        code.ifeq(notMatch);
                        emitReadComponent(code, c.asType(), slotByIdx[i], tmpBase);
                        code.goto_(nextIter);
                        code.labelBinding(notMatch);
                    }
                    // default : skip object/array si nécessaire
                    Label noSkip = code.newLabel();
                    code.aload(2);
                    code.getstatic(CD_JSON_PARSER_EVENT, "START_OBJECT", CD_JSON_PARSER_EVENT);
                    code.if_acmpne(noSkip);
                    code.aload(1);
                    code.invokeinterface(CD_JSON_PARSER, "skipObject", MethodTypeDesc.of(ConstantDescs.CD_void));
                    code.goto_(nextIter);
                    code.labelBinding(noSkip);
                    Label noSkipArr = code.newLabel();
                    code.aload(2);
                    code.getstatic(CD_JSON_PARSER_EVENT, "START_ARRAY", CD_JSON_PARSER_EVENT);
                    code.if_acmpne(noSkipArr);
                    code.aload(1);
                    code.invokeinterface(CD_JSON_PARSER, "skipArray", MethodTypeDesc.of(ConstantDescs.CD_void));
                    code.labelBinding(noSkipArr);

                    code.labelBinding(nextIter);
                    code.goto_(loopStart);

                    code.labelBinding(loopEnd);

                    // return new Target(arg1, arg2, ...)
                    code.new_(CD_TARGET);
                    code.dup();
                    var paramDescs = new ClassDesc[comps.size()];
                    for (int i = 0; i < comps.size(); i++) {
                        paramDescs[i] = toCD(comps.get(i).asType());
                        emitLoad(code, comps.get(i).asType(), slotByIdx[i]);
                    }
                    code.invokespecial(CD_TARGET, "<init>",
                            MethodTypeDesc.of(ConstantDescs.CD_void, paramDescs));
                    code.areturn();
                });
    }

    private static void emitDefaultStore(CodeBuilder code, TypeMirror tm, int slot) {
        switch (tm.getKind()) {
            case INT, SHORT, BYTE -> { code.iconst_0(); code.istore(slot); }
            case LONG -> { code.lconst_0(); code.lstore(slot); }
            case DOUBLE -> { code.dconst_0(); code.dstore(slot); }
            case FLOAT -> { code.fconst_0(); code.fstore(slot); }
            case BOOLEAN -> { code.iconst_0(); code.istore(slot); }
            case DECLARED -> {
                // Optional<X> : default = Optional.empty() (cohérent avec le source path).
                if (isOptional(tm)) {
                    code.invokestatic(CD_OPTIONAL, "empty", MethodTypeDesc.of(CD_OPTIONAL));
                    code.astore(slot);
                } else {
                    code.aconst_null();
                    code.astore(slot);
                }
            }
            case ARRAY -> { code.aconst_null(); code.astore(slot); }
            default -> throw new IllegalStateException("Unsupported");
        }
    }

    private static boolean isOptional(TypeMirror tm) {
        return tm.getKind() == TypeKind.DECLARED
                && "java.util.Optional".equals(((DeclaredType) tm).asElement().toString());
    }

    private static void emitReadComponent(CodeBuilder code, TypeMirror tm, int slot, int tmpBase) {
        // Si _ev == VALUE_NULL on saute (le slot reste à sa valeur par défaut).
        Label nullSkip = code.newLabel();
        Label after = code.newLabel();
        code.aload(2);
        code.getstatic(CD_JSON_PARSER_EVENT, "VALUE_NULL", CD_JSON_PARSER_EVENT);
        code.if_acmpeq(nullSkip);

        switch (tm.getKind()) {
            case INT -> {
                code.aload(1);
                code.invokeinterface(CD_JSON_PARSER, "getInt", MethodTypeDesc.of(ConstantDescs.CD_int));
                code.istore(slot);
            }
            case SHORT -> {
                code.aload(1);
                code.invokeinterface(CD_JSON_PARSER, "getInt", MethodTypeDesc.of(ConstantDescs.CD_int));
                code.i2s();
                code.istore(slot);
            }
            case BYTE -> {
                code.aload(1);
                code.invokeinterface(CD_JSON_PARSER, "getInt", MethodTypeDesc.of(ConstantDescs.CD_int));
                code.i2b();
                code.istore(slot);
            }
            case LONG -> {
                code.aload(1);
                code.invokeinterface(CD_JSON_PARSER, "getLong", MethodTypeDesc.of(ConstantDescs.CD_long));
                code.lstore(slot);
            }
            case DOUBLE -> {
                code.aload(1);
                code.invokeinterface(CD_JSON_PARSER, "getBigDecimal",
                        MethodTypeDesc.of(ClassDesc.of("java.math.BigDecimal")));
                code.invokevirtual(ClassDesc.of("java.math.BigDecimal"), "doubleValue",
                        MethodTypeDesc.of(ConstantDescs.CD_double));
                code.dstore(slot);
            }
            case FLOAT -> {
                code.aload(1);
                code.invokeinterface(CD_JSON_PARSER, "getBigDecimal",
                        MethodTypeDesc.of(ClassDesc.of("java.math.BigDecimal")));
                code.invokevirtual(ClassDesc.of("java.math.BigDecimal"), "floatValue",
                        MethodTypeDesc.of(ConstantDescs.CD_float));
                code.fstore(slot);
            }
            case BOOLEAN -> {
                // _ev == VALUE_TRUE ? 1 : 0
                Label setTrue = code.newLabel();
                Label done = code.newLabel();
                code.aload(2);
                code.getstatic(CD_JSON_PARSER_EVENT, "VALUE_TRUE", CD_JSON_PARSER_EVENT);
                code.if_acmpeq(setTrue);
                code.iconst_0();
                code.goto_(done);
                code.labelBinding(setTrue);
                code.iconst_1();
                code.labelBinding(done);
                code.istore(slot);
            }
            case DECLARED -> {
                if (isOptional(tm)) {
                    emitReadOptional(code, (DeclaredType) tm, slot);
                } else if (isEnum(tm)) {
                    // <Enum>.valueOf(p.getString())
                    ClassDesc CD_ENUM = ClassDesc.of(((DeclaredType) tm).asElement().toString());
                    code.aload(1);
                    code.invokeinterface(CD_JSON_PARSER, "getString", MethodTypeDesc.of(CD_STRING));
                    code.invokestatic(CD_ENUM, "valueOf", MethodTypeDesc.of(CD_ENUM, CD_STRING));
                    code.astore(slot);
                } else {
                    code.aload(1);
                    code.invokeinterface(CD_JSON_PARSER, "getString", MethodTypeDesc.of(CD_STRING));
                    code.astore(slot);
                }
            }
            case ARRAY -> emitReadArray(code, (ArrayType) tm, slot, tmpBase);
            default -> throw new IllegalStateException("Unsupported");
        }
        code.goto_(after);
        code.labelBinding(nullSkip);
        code.labelBinding(after);
    }

    /**
     * Émet le bytecode read pour un composant array primitif ou {@code String[]}.
     *
     * <p>Stratégie : on accumule dans un {@link java.util.ArrayList}, puis on transfère
     * vers un array du type cible. Coût : une boxing par élément primitif. Optimisable
     * en lecture directe à taille connue, mais le parser ne donne pas la taille à l'avance.</p>
     */
    private static void emitReadArray(CodeBuilder code, ArrayType arrTm, int slot, int tmpBase) {
        TypeMirror compTm = arrTm.getComponentType();
        final int R_TMP = tmpBase;
        final int R_IDX = tmpBase + 1;
        final int R_LEN = tmpBase + 2;

        // Vérification : _ev (slot 2) doit être START_ARRAY (sinon le source path
        // throw IllegalStateException ; on reproduit ce comportement).
        Label okStart = code.newLabel();
        code.aload(2);
        code.getstatic(CD_JSON_PARSER_EVENT, "START_ARRAY", CD_JSON_PARSER_EVENT);
        code.if_acmpeq(okStart);
        code.new_(ClassDesc.of("java.lang.IllegalStateException"));
        code.dup();
        code.ldc("Expected START_ARRAY");
        code.invokespecial(ClassDesc.of("java.lang.IllegalStateException"), "<init>",
                MethodTypeDesc.of(ConstantDescs.CD_void, CD_STRING));
        code.athrow();
        code.labelBinding(okStart);

        // ArrayList<T> list = new ArrayList<>();
        code.new_(CD_ARRAYLIST);
        code.dup();
        code.invokespecial(CD_ARRAYLIST, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void));
        code.astore(R_TMP);

        // boucle : while ((aev = p.next()) != END_ARRAY) list.add(read());
        Label loopStart = code.newLabel();
        Label loopEnd = code.newLabel();
        code.labelBinding(loopStart);
        code.aload(1);
        code.invokeinterface(CD_JSON_PARSER, "next", MethodTypeDesc.of(CD_JSON_PARSER_EVENT));
        // store aev en slot 2 (réutilise _ev)
        code.astore(2);
        code.aload(2);
        code.getstatic(CD_JSON_PARSER_EVENT, "END_ARRAY", CD_JSON_PARSER_EVENT);
        code.if_acmpeq(loopEnd);

        // list.add(<element>)
        code.aload(R_TMP);
        // Pour les primitifs : box → Integer/Long/Double/Boolean ; pour String : valeur directe
        switch (compTm.getKind()) {
            case INT -> {
                code.aload(1);
                code.invokeinterface(CD_JSON_PARSER, "getInt", MethodTypeDesc.of(ConstantDescs.CD_int));
                code.invokestatic(ConstantDescs.CD_Integer, "valueOf",
                        MethodTypeDesc.of(ConstantDescs.CD_Integer, ConstantDescs.CD_int));
            }
            case LONG -> {
                code.aload(1);
                code.invokeinterface(CD_JSON_PARSER, "getLong", MethodTypeDesc.of(ConstantDescs.CD_long));
                code.invokestatic(ConstantDescs.CD_Long, "valueOf",
                        MethodTypeDesc.of(ConstantDescs.CD_Long, ConstantDescs.CD_long));
            }
            case DOUBLE -> {
                code.aload(1);
                code.invokeinterface(CD_JSON_PARSER, "getBigDecimal", MethodTypeDesc.of(CD_BIGDECIMAL));
                code.invokevirtual(CD_BIGDECIMAL, "doubleValue", MethodTypeDesc.of(ConstantDescs.CD_double));
                code.invokestatic(ConstantDescs.CD_Double, "valueOf",
                        MethodTypeDesc.of(ConstantDescs.CD_Double, ConstantDescs.CD_double));
            }
            case BOOLEAN -> {
                // aev == VALUE_TRUE
                Label fal = code.newLabel();
                Label join = code.newLabel();
                code.aload(2);
                code.getstatic(CD_JSON_PARSER_EVENT, "VALUE_TRUE", CD_JSON_PARSER_EVENT);
                code.if_acmpne(fal);
                code.iconst_1();
                code.goto_(join);
                code.labelBinding(fal);
                code.iconst_0();
                code.labelBinding(join);
                code.invokestatic(ConstantDescs.CD_Boolean, "valueOf",
                        MethodTypeDesc.of(ConstantDescs.CD_Boolean, ConstantDescs.CD_boolean));
            }
            case DECLARED -> {
                // String : null si VALUE_NULL, sinon p.getString()
                Label nul = code.newLabel();
                Label join = code.newLabel();
                code.aload(2);
                code.getstatic(CD_JSON_PARSER_EVENT, "VALUE_NULL", CD_JSON_PARSER_EVENT);
                code.if_acmpne(nul);
                code.aconst_null();
                code.goto_(join);
                code.labelBinding(nul);
                code.aload(1);
                code.invokeinterface(CD_JSON_PARSER, "getString", MethodTypeDesc.of(CD_STRING));
                code.labelBinding(join);
            }
            default -> throw new IllegalStateException();
        }
        code.invokevirtual(CD_ARRAYLIST, "add",
                MethodTypeDesc.of(ConstantDescs.CD_boolean, CD_OBJECT));
        code.pop();

        code.goto_(loopStart);
        code.labelBinding(loopEnd);

        // Maintenant convertit la List en array typé.
        // int n = list.size();
        code.aload(R_TMP);
        code.invokevirtual(CD_ARRAYLIST, "size", MethodTypeDesc.of(ConstantDescs.CD_int));
        code.dup();
        code.istore(R_LEN);

        // crée le tableau cible
        switch (compTm.getKind()) {
            case INT -> code.newarray(java.lang.classfile.TypeKind.INT);
            case LONG -> code.newarray(java.lang.classfile.TypeKind.LONG);
            case DOUBLE -> code.newarray(java.lang.classfile.TypeKind.DOUBLE);
            case BOOLEAN -> code.newarray(java.lang.classfile.TypeKind.BOOLEAN);
            case DECLARED -> code.anewarray(CD_STRING);
            default -> throw new IllegalStateException();
        }
        // store en slot, puis on remplit à partir de la list
        code.astore(slot);

        // for (int i = 0; i < n; i++) arr[i] = (cast) list.get(i)[.<unbox>()]
        code.iconst_0();
        code.istore(R_IDX);

        Label fillStart = code.newLabel();
        Label fillEnd = code.newLabel();
        code.labelBinding(fillStart);
        code.iload(R_IDX);
        code.iload(R_LEN);
        code.if_icmpge(fillEnd);

        code.aload(slot);                       // [arr]
        code.iload(R_IDX);             // [arr, i]
        code.aload(R_TMP);             // [arr, i, list]
        code.iload(R_IDX);             // [arr, i, list, i]
        code.invokevirtual(CD_ARRAYLIST, "get",
                MethodTypeDesc.of(CD_OBJECT, ConstantDescs.CD_int));      // [arr, i, Object]

        switch (compTm.getKind()) {
            case INT -> {
                code.checkcast(ConstantDescs.CD_Integer);
                code.invokevirtual(ConstantDescs.CD_Integer, "intValue",
                        MethodTypeDesc.of(ConstantDescs.CD_int));
                code.iastore();
            }
            case LONG -> {
                code.checkcast(ConstantDescs.CD_Long);
                code.invokevirtual(ConstantDescs.CD_Long, "longValue",
                        MethodTypeDesc.of(ConstantDescs.CD_long));
                code.lastore();
            }
            case DOUBLE -> {
                code.checkcast(ConstantDescs.CD_Double);
                code.invokevirtual(ConstantDescs.CD_Double, "doubleValue",
                        MethodTypeDesc.of(ConstantDescs.CD_double));
                code.dastore();
            }
            case BOOLEAN -> {
                code.checkcast(ConstantDescs.CD_Boolean);
                code.invokevirtual(ConstantDescs.CD_Boolean, "booleanValue",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean));
                code.bastore();
            }
            case DECLARED -> {
                code.checkcast(CD_STRING);
                code.aastore();
            }
            default -> throw new IllegalStateException();
        }

        code.iinc(R_IDX, 1);
        code.goto_(fillStart);
        code.labelBinding(fillEnd);
    }

    private static void emitLoad(CodeBuilder code, TypeMirror tm, int slot) {
        switch (tm.getKind()) {
            case INT, SHORT, BYTE, BOOLEAN -> code.iload(slot);
            case LONG -> code.lload(slot);
            case DOUBLE -> code.dload(slot);
            case FLOAT -> code.fload(slot);
            case DECLARED, ARRAY -> code.aload(slot);
            default -> throw new IllegalStateException("Unsupported");
        }
    }

    private static int slotsFor(TypeMirror tm) {
        return switch (tm.getKind()) {
            case LONG, DOUBLE -> 2;
            default -> 1;
        };
    }

    private static ClassDesc toCD(TypeMirror tm) {
        return switch (tm.getKind()) {
            case INT -> ConstantDescs.CD_int;
            case LONG -> ConstantDescs.CD_long;
            case DOUBLE -> ConstantDescs.CD_double;
            case FLOAT -> ConstantDescs.CD_float;
            case SHORT -> ConstantDescs.CD_short;
            case BYTE -> ConstantDescs.CD_byte;
            case BOOLEAN -> ConstantDescs.CD_boolean;
            case DECLARED -> ClassDesc.of(((DeclaredType) tm).asElement().toString());
            case ARRAY -> arrayCDOf(((ArrayType) tm).getComponentType());
            default -> throw new IllegalStateException();
        };
    }

    private BindingBytecodeEmitter() {}
}
