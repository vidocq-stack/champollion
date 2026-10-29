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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * JSON-B 3.0 §5: adapters, serializers and deserializers are CDI beans when a CDI container is available. In a
 * Vidocq application the container sits in the application layer, a child of Champollion's: the CDI API must be
 * looked up from the bean class, not from Champollion's own class loader, which does not see it.
 */
class CdiResolverTest {

    private static final ClassDesc CDI = ClassDesc.of("jakarta.enterprise.inject.spi.CDI");
    private static final ClassDesc INSTANCE = ClassDesc.of("jakarta.enterprise.inject.Instance");
    private static final ClassDesc SCOPED = ClassDesc.of("jakarta.enterprise.context.ApplicationScoped");
    private static final ClassDesc BEAN = ClassDesc.of("app.Adapter");
    private static final String FROM_CDI = "the CDI bean";

    /** How the minimal CDI API answers. */
    private enum Container {
        /** {@code Instance.get()} returns {@link #FROM_CDI}. */
        RUNNING,
        /** {@code CDI.current()} throws {@link IllegalStateException}, as without an active container. */
        STOPPED,
        /** {@code Instance.isUnsatisfied()} is true. */
        UNSATISFIED,
        /** {@code Instance.get()} throws, as when the bean cannot be created. */
        BROKEN
    }

    /** A loader that defines a minimal CDI API and a bean. */
    private static final class ApplicationLayer extends ClassLoader {
        private final Map<String, byte[]> classes;

        ApplicationLayer(Container container) {
            super(ClassLoader.getPlatformClassLoader());
            classes = Map.of(
                    "jakarta.enterprise.inject.spi.CDI", cdi(container),
                    "jakarta.enterprise.inject.Instance", instance(container),
                    "jakarta.enterprise.context.ApplicationScoped", scoped(),
                    "app.Adapter", bean());
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static byte[] cdi(Container container) {
        return ClassFile.of().build(CDI, cls -> cls
                .withFlags(AccessFlag.PUBLIC)
                .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> code
                        .aload(0).invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .return_())
                .withMethodBody("current", MethodTypeDesc.of(CDI), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                        code -> {
                            if (container == Container.STOPPED) {
                                throwNew(code, ClassDesc.of("java.lang.IllegalStateException"));
                            } else {
                                code.new_(CDI).dup()
                                        .invokespecial(CDI, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void).areturn();
                            }
                        })
                .withMethodBody("select", MethodTypeDesc.of(INSTANCE, ConstantDescs.CD_Class,
                                ClassDesc.of("java.lang.annotation.Annotation").arrayType()), ClassFile.ACC_PUBLIC,
                        code -> code.new_(INSTANCE).dup()
                                .invokespecial(INSTANCE, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void).areturn()));
    }

    private static void throwNew(java.lang.classfile.CodeBuilder code, ClassDesc exception) {
        code.new_(exception).dup()
                .invokespecial(exception, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void).athrow();
    }

    private static byte[] instance(Container container) {
        MethodTypeDesc bool = MethodTypeDesc.of(ConstantDescs.CD_boolean);
        return ClassFile.of().build(INSTANCE, cls -> cls
                .withFlags(AccessFlag.PUBLIC)
                .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> code
                        .aload(0).invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .return_())
                .withMethodBody("get", MethodTypeDesc.of(ConstantDescs.CD_Object), ClassFile.ACC_PUBLIC,
                        code -> {
                            if (container == Container.BROKEN) {
                                throwNew(code, ClassDesc.of("java.lang.RuntimeException"));
                            } else {
                                code.ldc(FROM_CDI).areturn();
                            }
                        })
                .withMethodBody("isUnsatisfied", bool, ClassFile.ACC_PUBLIC, code -> {
                    if (container == Container.UNSATISFIED) {
                        code.iconst_1().ireturn();
                    } else {
                        code.iconst_0().ireturn();
                    }
                })
                .withMethodBody("isAmbiguous", bool, ClassFile.ACC_PUBLIC, code -> code.iconst_0().ireturn()));
    }

    private static byte[] scoped() {
        Annotation retention = Annotation.of(ClassDesc.of("java.lang.annotation.Retention"),
                AnnotationElement.of("value", AnnotationValue.ofEnum(
                        ClassDesc.of("java.lang.annotation.RetentionPolicy"), "RUNTIME")));
        return ClassFile.of().build(SCOPED, cls -> cls
                .withFlags(AccessFlag.PUBLIC, AccessFlag.INTERFACE, AccessFlag.ABSTRACT, AccessFlag.ANNOTATION)
                .withInterfaceSymbols(ClassDesc.of("java.lang.annotation.Annotation"))
                .with(RuntimeVisibleAnnotationsAttribute.of(retention)));
    }

    private static byte[] bean() {
        return ClassFile.of().build(BEAN, cls -> cls
                .withFlags(AccessFlag.PUBLIC)
                .with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(SCOPED)))
                .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> code
                        .aload(0).invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .return_()));
    }

    @Test
    void aBeanIsResolvedThroughTheCdiApiItsOwnClassLoaderSees() throws Exception {
        Class<?> adapter = new ApplicationLayer(Container.RUNNING).loadClass("app.Adapter");

        assertEquals(FROM_CDI, CdiResolver.resolve(adapter));
    }

    @ParameterizedTest
    @EnumSource(value = Container.class, names = {"STOPPED", "UNSATISFIED", "BROKEN"})
    void withoutASingleCdiBeanTheNoArgumentConstructorIsUsed(Container container) throws Exception {
        Class<?> adapter = new ApplicationLayer(container).loadClass("app.Adapter");

        assertInstanceOf(adapter, CdiResolver.resolve(adapter));
    }

    @Test
    void withoutCdiTheNoArgumentConstructorIsUsed() throws Exception {
        assertInstanceOf(StringBuilder.class, CdiResolver.resolve(StringBuilder.class));
    }
}
