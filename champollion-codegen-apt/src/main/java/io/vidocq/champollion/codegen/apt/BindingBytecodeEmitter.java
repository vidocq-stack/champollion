package io.vidocq.champollion.codegen.apt;

import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
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

    /** Test : tous les composants sont-ils dans le subset bytecode (primitives + String + enum) ? */
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
                    return false;
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
            default -> throw new IllegalStateException("Unsupported component for bytecode emitter: " + tm);
        }
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
                        emitReadComponent(code, c.asType(), slotByIdx[i]);
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
            case DECLARED -> { code.aconst_null(); code.astore(slot); }
            default -> throw new IllegalStateException("Unsupported");
        }
    }

    private static void emitReadComponent(CodeBuilder code, TypeMirror tm, int slot) {
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
                if (isEnum(tm)) {
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
            default -> throw new IllegalStateException("Unsupported");
        }
        code.goto_(after);
        code.labelBinding(nullSkip);
        code.labelBinding(after);
    }

    private static void emitLoad(CodeBuilder code, TypeMirror tm, int slot) {
        switch (tm.getKind()) {
            case INT, SHORT, BYTE, BOOLEAN -> code.iload(slot);
            case LONG -> code.lload(slot);
            case DOUBLE -> code.dload(slot);
            case FLOAT -> code.fload(slot);
            case DECLARED -> code.aload(slot);
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
            default -> throw new IllegalStateException();
        };
    }

    private BindingBytecodeEmitter() {}
}
