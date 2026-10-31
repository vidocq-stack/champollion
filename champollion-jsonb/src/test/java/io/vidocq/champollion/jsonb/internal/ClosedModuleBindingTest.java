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
package io.vidocq.champollion.jsonb.internal;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.constant.ModuleDesc;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Binding the types of a module that opens and exports nothing (BUG-20261031-01): each failure names the module, the
 * package and the {@code opens} line, wherever the runtime binding reaches the type — a getter written, a bean, a
 * record and a polymorphic subtype read.
 */
class ClosedModuleBindingTest {

    private static final String HINT = "module closed.sample does not open package closed.sample.p to "
            + "io.vidocq.champollion.jsonb — add `opens closed.sample.p;` to its module-info.java";
    private static final ClassDesc BEAN = ClassDesc.of("closed.sample.p.Bean");
    private static final ClassDesc REC = ClassDesc.of("closed.sample.p.Rec");
    private static final ClassDesc ANIMAL = ClassDesc.of("closed.sample.p.Animal");
    private static final ClassDesc DOG = ClassDesc.of("closed.sample.p.Dog");
    private static final ClassDesc ANIMAL_REC = ClassDesc.of("closed.sample.p.AnimalRec");
    private static final ClassDesc RUNNABLE = ClassDesc.of("java.lang.Runnable");

    @TempDir
    static Path dir;

    private static ModuleLayer layer;
    private static Jsonb jsonb;

    @BeforeAll
    static void defineTheClosedModule() throws IOException {
        Path root = dir.resolve("closed.sample");
        Files.createDirectories(root.resolve("closed/sample/p"));
        ClassFile cf = ClassFile.of();
        cf.buildModuleTo(root.resolve("module-info.class"), ModuleAttribute.of(ModuleDesc.of("closed.sample"),
                m -> m.requires(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null)
                        .requires(ModuleDesc.of("jakarta.json.bind"), 0, null)
                        .provides(RUNNABLE, BEAN)));
        write(root, BEAN, cf.build(BEAN, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC).withInterfaceSymbols(RUNNABLE);
            noArgConstructor(cb, ConstantDescs.CD_Object);
            cb.withMethodBody("getName", MethodTypeDesc.of(ConstantDescs.CD_String), ClassFile.ACC_PUBLIC,
                    code -> code.ldc("x").areturn());
            cb.withMethodBody("run", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC,
                    code -> code.return_());
        }));
        write(root, REC, record(cf, REC));
        write(root, ANIMAL_REC, record(cf, ANIMAL_REC, ANIMAL));
        write(root, ANIMAL, cf.build(ANIMAL, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT);
            cb.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(
                    ClassDesc.of("jakarta.json.bind.annotation.JsonbTypeInfo"),
                    AnnotationElement.ofString("key", "@type"),
                    AnnotationElement.ofArray("value", subtype("dog", DOG), subtype("rec", ANIMAL_REC)))));
        }));
        write(root, DOG, cf.build(DOG, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC).withInterfaceSymbols(ANIMAL);
            noArgConstructor(cb, ConstantDescs.CD_Object);
        }));

        ModuleLayer parent = ModuleLayer.boot();
        Configuration config = parent.configuration().resolve(ModuleFinder.of(root), ModuleFinder.of(),
                Set.of("closed.sample"));
        layer = parent.defineModulesWithOneLoader(config, ClosedModuleBindingTest.class.getClassLoader());
        ClosedModuleBindingTest.class.getModule().addUses(Runnable.class);
        jsonb = JsonbBuilder.create();
    }

    @AfterAll
    static void close() throws Exception {
        jsonb.close();
    }

    /** A record with one {@code String name} component. */
    private static byte[] record(ClassFile cf, ClassDesc type, ClassDesc... interfaces) {
        return cf.build(type, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL).withSuperclass(ClassDesc.of("java.lang.Record"))
                    .withInterfaceSymbols(interfaces);
            cb.with(RecordAttribute.of(RecordComponentInfo.of("name", ConstantDescs.CD_String)));
            cb.withField("name", ConstantDescs.CD_String, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
            cb.withMethodBody(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String),
                    ClassFile.ACC_PUBLIC, code -> code.aload(0)
                            .invokespecial(ClassDesc.of("java.lang.Record"), ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                            .aload(0).aload(1).putfield(type, "name", ConstantDescs.CD_String).return_());
            cb.withMethodBody("name", MethodTypeDesc.of(ConstantDescs.CD_String), ClassFile.ACC_PUBLIC,
                    code -> code.aload(0).getfield(type, "name", ConstantDescs.CD_String).areturn());
        });
    }

    private static AnnotationValue subtype(String alias, ClassDesc type) {
        return AnnotationValue.ofAnnotation(Annotation.of(ClassDesc.of("jakarta.json.bind.annotation.JsonbSubtype"),
                AnnotationElement.ofString("alias", alias), AnnotationElement.ofClass("type", type)));
    }

    private static void noArgConstructor(java.lang.classfile.ClassBuilder cb, ClassDesc superclass) {
        cb.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC,
                code -> code.aload(0).invokespecial(superclass, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .return_());
    }

    private static void write(Path root, ClassDesc type, byte[] bytes) throws IOException {
        Files.write(root.resolve(type.packageName().replace('.', '/')).resolve(type.displayName() + ".class"), bytes);
    }

    private static Class<?> load(ClassDesc type) throws ClassNotFoundException {
        return Class.forName(type.packageName() + "." + type.displayName(), false, layer.findLoader("closed.sample"));
    }

    private static void assertNamesTheAccessCause(JsonbException e) {
        assertTrue(e.getMessage().endsWith(HINT), e.getMessage());
    }

    @Test
    void writingAGetterOfAClosedTypeNamesTheModule() {
        Object bean = ServiceLoader.load(layer, Runnable.class).findFirst().orElseThrow();

        assertNamesTheAccessCause(assertThrows(JsonbException.class, () -> jsonb.toJson(bean)));
    }

    @Test
    void readingABeanOfAClosedTypeNamesTheModule() throws Exception {
        Class<?> type = load(BEAN);

        assertNamesTheAccessCause(assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"name\":\"y\"}", type)));
    }

    @Test
    void readingARecordOfAClosedTypeNamesTheModule() throws Exception {
        Class<?> type = load(REC);

        assertNamesTheAccessCause(assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"name\":\"y\"}", type)));
    }

    @Test
    void readingAPolymorphicSubtypeOfAClosedTypeNamesTheModule() throws Exception {
        Class<?> type = load(ANIMAL);

        assertNamesTheAccessCause(assertThrows(JsonbException.class, () -> jsonb.fromJson("{\"@type\":\"dog\"}", type)));
    }

    @Test
    void readingAPolymorphicRecordSubtypeOfAClosedTypeNamesTheModule() throws Exception {
        Class<?> type = load(ANIMAL);

        assertNamesTheAccessCause(assertThrows(JsonbException.class,
                () -> jsonb.fromJson("{\"@type\":\"rec\",\"name\":\"y\"}", type)));
    }
}
