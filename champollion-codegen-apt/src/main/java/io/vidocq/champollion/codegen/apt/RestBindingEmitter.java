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

import io.vidocq.champollion.codegen.apt.RestBindings.Access;
import io.vidocq.champollion.codegen.apt.RestBindings.Adapter;
import io.vidocq.champollion.codegen.apt.RestBindings.Format;
import io.vidocq.champollion.codegen.apt.RestBindings.Mapping;
import io.vidocq.champollion.codegen.apt.RestBindings.Polymorphism;
import io.vidocq.champollion.codegen.apt.RestBindings.Model;
import io.vidocq.champollion.codegen.apt.RestBindings.Property;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the {@code JsonbBinding} of a {@link Model}: the structure of the type (object, properties in JSON-B's
 * default order, collections, maps, optionals, enums, the bindings of the types it holds) as plain Java, and each
 * natively mapped value through {@code JsonbLeaf}, the runtime's own codec. The output is the runtime's, character for
 * character, under the default configuration; the runtime does not use generated bindings under another.
 */
final class RestBindingEmitter {

    private static final String READ_START = """
                        jakarta.json.stream.JsonParser.Event e = p.next();
                        if (e == jakarta.json.stream.JsonParser.Event.VALUE_NULL) return null;
                        if (e != jakarta.json.stream.JsonParser.Event.START_OBJECT) throw __expected("an object", e);
                """;
    private static final String LOOP_START = """
                        while ((e = p.next()) != jakarta.json.stream.JsonParser.Event.END_OBJECT) {
                            if (e != jakarta.json.stream.JsonParser.Event.KEY_NAME) throw __expected("a key", e);
                            switch (p.getString()) {
                """;
    private static final String LOOP_END = """
                                default -> __skip(p);
                            }
                        }
                """;
    private static final String CTX_OPEN = " ctx) {\n";
    private static final String PRIVATE_STATIC = "    private static ";
    private static final String NEW_END = "();\n";
    private static final String CASE = "                case ";
    private static final String ASSIGN = " = %s;";
    private static final String I8 = "        ";
    private static final String I16 = "                ";
    private static final String WRITE_G = ".write(g, ";
    private static final String NULL_GIVES_NULL = "        if (e == jakarta.json.stream.JsonParser.Event.VALUE_NULL) return null;\n";
    private static final String WITH_EVENT = "(p, e, ctx));\n";
    private static final String SER_CONTEXT = "jakarta.json.bind.serializer.SerializationContext";
    private static final String DESER_CONTEXT = "jakarta.json.bind.serializer.DeserializationContext";
    /** The parameters of a reader method: the parser, the value's leading event (consumed), the context. */
    private static final String READER_PARAMETERS = "(jakarta.json.stream.JsonParser p, "
            + "jakarta.json.stream.JsonParser.Event e, " + DESER_CONTEXT + CTX_OPEN;
    private final Model model;
    private final Map<TypeElement, String> others;
    /** Leaf codec fields, by the expression creating them. */
    private final Map<String, String> leaves = new LinkedHashMap<>();
    /** Fields of the adapters, serializers and deserializers the binding calls, by class name. */
    private final Map<String, String> instances = new LinkedHashMap<>();
    /** How each instance is created when the container does not provide it: a constructor reference, or null. */
    private final Map<String, String> creators = new LinkedHashMap<>();
    /** Binding fields of the types this one holds, by their binding's name. */
    private final Map<String, String> bindings = new LinkedHashMap<>();
    /** Reader methods, by the type they read. */
    private final Map<String, String> readerNames = new LinkedHashMap<>();
    private final StringBuilder readers = new StringBuilder();
    private int variables;

    /**
     * @param others the models of the compilation, to reach the bindings of the types this one holds
     */
    RestBindingEmitter(Model model, Map<TypeElement, String> others) {
        this.model = model;
        this.others = others;
    }

    String source() {
        Polymorphism poly = model.polymorphism();
        StringBuilder write = new StringBuilder();
        for (Property p : model.properties()) {
            if (p.read() != Access.NONE) emitWrite(write, p);
        }
        String read;
        if (poly != null && poly.isBase()) {
            read = readBase(poly);
            write = writeBase(poly);
        } else {
            read = model.isRecord() ? readRecord() : readClass();
        }

        StringBuilder out = new StringBuilder();
        String pkg = model.packageName();
        if (!pkg.isEmpty()) out.append("package ").append(pkg).append(";\n\n");
        String type = model.typeName();
        out.append("/** Generated by champollion-codegen-apt for the JSON-B types a JAX-RS resource reaches — do not edit. */\n");
        out.append("public final class ").append(model.bindingSimpleName())
                .append(" implements io.vidocq.champollion.jsonb.spi.JsonbBinding<").append(type).append("> {\n\n");
        for (var e : leaves.entrySet()) {
            out.append("    private static final io.vidocq.champollion.jsonb.spi.JsonbLeaf ").append(e.getValue())
                    .append(" = io.vidocq.champollion.jsonb.spi.JsonbLeaf.").append(e.getKey()).append(";\n");
        }
        for (var e : instances.entrySet()) {
            out.append("    private static final java.util.function.Supplier<").append(e.getKey()).append("> ")
                    .append(e.getValue()).append(" = io.vidocq.champollion.jsonb.spi.JsonbInstances.of(")
                    .append(e.getKey()).append(".class, ").append(creators.get(e.getKey())).append(");\n");
        }
        for (var e : bindings.entrySet()) {
            out.append("    private static final ").append(e.getKey()).append(' ').append(e.getValue())
                    .append(" = new ").append(e.getKey()).append(NEW_END);
        }
        out.append("\n    @Override public Class<").append(type).append("> type() { return ").append(type)
                .append(".class; }\n\n");
        out.append("    @Override\n    public void write(jakarta.json.stream.JsonGenerator g, ").append(type)
                .append(" value) {\n        write(g, value, null);\n    }\n\n");
        out.append("    @Override\n    public void write(jakarta.json.stream.JsonGenerator g, ").append(type)
                .append(" value, ").append(SER_CONTEXT).append(CTX_OPEN);
        out.append("        if (value == null) { g.writeNull(); return; }\n");
        if (poly != null && poly.isBase()) {
            out.append(write).append("    }\n\n");
        } else if (poly != null) {
            // A subtype writes its discriminator first, then its members: those the base's binding writes too.
            out.append("        g.writeStartObject();\n");
            out.append(I8).append(discriminatorKey(poly)).append(" g.write(").append(lit(poly.alias())).append(");\n");
            out.append("        writeMembers(g, value, ctx);\n");
            out.append("        g.writeEnd();\n    }\n\n");
            out.append("    /** Writes the members of {@code value}, after its discriminator. */\n");
            out.append("    public void writeMembers(jakarta.json.stream.JsonGenerator g, ").append(type)
                    .append(" value, ").append(SER_CONTEXT).append(CTX_OPEN);
            out.append(write);
            out.append("    }\n\n");
        } else {
            out.append("        g.writeStartObject();\n");
            out.append(write);
            out.append("        g.writeEnd();\n    }\n\n");
        }
        out.append(read);
        out.append(readers);
        out.append("""
                    private static void __skip(jakarta.json.stream.JsonParser p) {
                        jakarta.json.stream.JsonParser.Event e = p.next();
                        if (e == jakarta.json.stream.JsonParser.Event.START_OBJECT) p.skipObject();
                        else if (e == jakarta.json.stream.JsonParser.Event.START_ARRAY) p.skipArray();
                    }

                    private static void __key(jakarta.json.stream.JsonGenerator g, String preQuotedWithColon, String name) {
                        if (g instanceof io.vidocq.champollion.spi.RawJsonKeyWriter raw) raw.writeKeyRawWithColon(preQuotedWithColon);
                        else g.writeKey(name);
                    }

                    private static jakarta.json.bind.JsonbException __expected(String what, jakarta.json.stream.JsonParser.Event got) {
                        return new jakarta.json.bind.JsonbException("Expected " + what + ", got " + got);
                    }

                    private static String __discriminator(jakarta.json.stream.JsonParser p, String key) {
                        jakarta.json.stream.JsonParser.Event e = p.next();
                        if (e != jakarta.json.stream.JsonParser.Event.KEY_NAME || !p.getString().equals(key)) {
                            throw new jakarta.json.bind.JsonbException("Expected discriminator '" + key + "' as next member");
                        }
                        e = p.next();
                        if (e != jakarta.json.stream.JsonParser.Event.VALUE_STRING) throw __expected("a string discriminator", e);
                        return p.getString();
                    }
                }
                """);
        return out.toString();
    }

    // ===== write =====

    private void emitWrite(StringBuilder out, Property p) {
        String key = "__key(g, " + lit(preQuote(p.jsonName())) + ", " + lit(p.jsonName()) + ");";
        String getter = "value." + p.readMember();
        TypeMirror t = p.type();
        if (t.getKind().isPrimitive()) {
            out.append(I8).append(key).append(' ').append(leaf(t, p.out().format())).append(WRITE_G).append(getter)
                    .append(");\n");
            return;
        }
        String v = newVariable();
        out.append("        {\n            ").append(render(t)).append(' ').append(v).append(" = ").append(getter)
                .append(";\n");
        if (p.out().adapter() == null && RestBindings.OPTIONAL.equals(RestBindings.erasureName(t))) {
            TypeMirror inner = ((DeclaredType) t).getTypeArguments().get(0);
            out.append("            if (").append(v).append(" != null && ").append(v).append(".isPresent()) {\n");
            out.append(I16).append(key).append('\n');
            writeValue(out, v + ".get()", inner, I16);
            out.append("            }");
        } else {
            out.append("            if (").append(v).append(" != null) {\n");
            out.append(I16).append(key).append('\n');
            writeMapped(out, v, t, p.out());
            out.append("            }");
        }
        if (p.nillable()) out.append(" else { ").append(key).append(" g.writeNull(); }");
        out.append("\n        }\n");
    }

    /** Writes the non-null {@code v} of the property type {@code t}, through its adapter or its format if any. */
    private void writeMapped(StringBuilder out, String v, TypeMirror t, Mapping mapping) {
        Adapter adapter = mapping.adapter();
        if (adapter != null) {
            String a = newVariable();
            out.append(I16).append(render(adapter.adapted())).append(' ').append(a).append(";\n");
            out.append(I16).append("try { ").append(a).append(" = ").append(adapter(adapter)).append(".adaptToJson(")
                    .append(v).append("); }\n");
            out.append(I16).append("catch (Exception x) { throw new jakarta.json.bind.JsonbException(")
                    .append("\"Adapter failure on toJson: \" + x.getMessage(), x); }\n");
            out.append(I16).append("if (").append(a).append(" == null) g.writeNull();\n");
            out.append(I16).append("else {\n");
            writeValue(out, a, adapter.adapted(), I16 + "    ");
            out.append(I16).append("}\n");
        } else if (mapping.custom() != null) {
            out.append(I16).append(instance(mapping.custom())).append(".get().serialize(").append(v)
                    .append(", g, io.vidocq.champollion.jsonb.spi.JsonbContexts.serialization(ctx));\n");
        } else if (mapping.format() != null) {
            out.append(I16).append(leaf(t, mapping.format())).append(WRITE_G).append(v).append(");\n");
        } else {
            writeValue(out, v, t, I16);
        }
    }

    /** Writes the non-null {@code expr} of type {@code t}. */
    private void writeValue(StringBuilder out, String expr, TypeMirror t, String indent) {
        if (t.getKind().isPrimitive() || RestBindings.isPrimitiveArray(t)) {
            out.append(indent).append(leaf(t)).append(WRITE_G).append(expr).append(");\n");
            return;
        }
        if (t.getKind() == TypeKind.ARRAY) {
            writeElements(out, expr, ((javax.lang.model.type.ArrayType) t).getComponentType(), indent);
            return;
        }
        DeclaredType d = (DeclaredType) t;
        String name = RestBindings.erasureName(d);
        TypeElement element = (TypeElement) d.asElement();
        if (RestBindings.isDynamic(t)) {
            // Mapped by its class, as the runtime does: through the context, which uses that class's binding.
            out.append(indent).append("io.vidocq.champollion.jsonb.spi.JsonbContexts.serialization(ctx).serialize(")
                    .append(expr).append(", g);\n");
        } else if (RestBindings.LEAVES.contains(name)) {
            out.append(indent).append(leaf(t)).append(WRITE_G).append(expr).append(");\n");
        } else if (element.getKind() == ElementKind.ENUM) {
            out.append(indent).append("g.write(").append(expr).append(".name());\n");
        } else if (RestBindings.COLLECTIONS.contains(name)) {
            writeElements(out, expr, d.getTypeArguments().get(0), indent);
        } else if (RestBindings.MAP.equals(name)) {
            TypeMirror val = d.getTypeArguments().get(1);
            String e = newVariable();
            out.append(indent).append("g.writeStartObject();\n");
            out.append(indent).append("for (java.util.Map.Entry<String, ").append(render(val)).append("> ").append(e)
                    .append(" : ").append(expr).append(".entrySet()) {\n");
            out.append(indent).append("    g.writeKey(").append(e).append(".getKey());\n");
            out.append(indent).append("    if (").append(e).append(".getValue() == null) g.writeNull();\n");
            out.append(indent).append("    else {\n");
            writeValue(out, e + ".getValue()", val, indent + I8);
            out.append(indent).append("    }\n");
            out.append(indent).append("}\n");
            out.append(indent).append("g.writeEnd();\n");
        } else {
            out.append(indent).append(binding(element)).append(WRITE_G).append(expr).append(", ctx);\n");
        }
    }

    /** Writes the elements of the collection or array {@code expr} as a JSON array. */
    private void writeElements(StringBuilder out, String expr, TypeMirror elem, String indent) {
        String e = newVariable();
        out.append(indent).append("g.writeStartArray();\n");
        out.append(indent).append("for (").append(render(elem)).append(' ').append(e).append(" : ").append(expr)
                .append(") {\n");
        out.append(indent).append("    if (").append(e).append(" == null) g.writeNull();\n");
        out.append(indent).append("    else {\n");
        writeValue(out, e, elem, indent + I8);
        out.append(indent).append("    }\n");
        out.append(indent).append("}\n");
        out.append(indent).append("g.writeEnd();\n");
    }

    // ===== read =====

    private String readRecord() {
        String type = model.typeName();
        Polymorphism poly = model.polymorphism();
        StringBuilder out = new StringBuilder();
        out.append(readHeader(type));
        out.append(READ_START);
        if (poly != null) {
            // A subtype reads its discriminator first, as the runtime does, then its members.
            out.append("        String alias = __discriminator(p, ").append(lit(poly.key())).append(");\n");
            out.append("        if (!alias.equals(").append(lit(poly.alias())).append(")) {\n");
            out.append("            throw new jakarta.json.bind.JsonbException(\"Unexpected @JsonbSubtype alias \" + alias + ")
                    .append(lit(" for " + type)).append(");\n        }\n");
            out.append("        return readMembers(p, ctx);\n    }\n\n");
            out.append("    /** Reads the members of an object whose start and discriminator are consumed. */\n");
            out.append("    public ").append(type).append(" readMembers(jakarta.json.stream.JsonParser p, ")
                    .append(DESER_CONTEXT).append(CTX_OPEN);
            out.append("        jakarta.json.stream.JsonParser.Event e;\n");
        }
        List<? extends RecordComponentElement> components = model.type().getRecordComponents();
        Map<String, String> locals = new LinkedHashMap<>();
        for (var c : components) {
            String local = "c_" + c.getSimpleName();
            locals.put(c.getSimpleName().toString(), local);
            out.append(I8).append(render(c.asType())).append(' ').append(local).append(" = ")
                    .append(defaultOf(c.asType())).append(";\n");
        }
        out.append(LOOP_START);
        for (Property p : model.properties()) {
            out.append(CASE).append(lit(p.jsonName())).append(" -> ");
            assign(out, p, locals.get(p.writeMember()));
        }
        out.append(LOOP_END);
        out.append("        return new ").append(type).append('(');
        for (int i = 0; i < components.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(locals.get(components.get(i).getSimpleName().toString()));
        }
        out.append(");\n    }\n\n");
        return out.toString();
    }

    /** The key of a discriminator, as a statement writing it. */
    private static String discriminatorKey(Polymorphism poly) {
        return "__key(g, " + lit(preQuote(poly.key())) + ", " + lit(poly.key()) + ");";
    }

    /**
     * The body of a polymorphic base's {@code write}: the discriminator and members of the value's record, through its
     * binding; a class no {@code @JsonbSubtype} lists (an interface that is not sealed) goes to the runtime, by its class.
     */
    private StringBuilder writeBase(Polymorphism poly) {
        StringBuilder out = new StringBuilder();
        out.append("        switch (value) {\n");
        for (RestBindings.Variant v : poly.variants()) {
            String subtype = v.type().getQualifiedName().toString();
            out.append("            case ").append(subtype).append(" v -> {\n");
            out.append(I16).append("g.writeStartObject();\n");
            out.append(I16).append(discriminatorKey(poly)).append(" g.write(").append(lit(v.alias())).append(");\n");
            out.append(I16).append(binding(v.type())).append(".writeMembers(g, v, ctx);\n");
            out.append(I16).append("g.writeEnd();\n");
            out.append("            }\n");
        }
        out.append("            default -> io.vidocq.champollion.jsonb.spi.JsonbContexts.serialization(ctx).serialize(value, g);\n");
        out.append("        }\n");
        return out;
    }

    /** The {@code read} of a polymorphic base: its discriminator picks the subtype whose binding reads the rest. */
    private String readBase(Polymorphism poly) {
        StringBuilder out = new StringBuilder(readHeader(model.typeName()));
        out.append(READ_START);
        out.append("        String alias = __discriminator(p, ").append(lit(poly.key())).append(");\n");
        out.append("        return switch (alias) {\n");
        for (RestBindings.Variant v : poly.variants()) {
            out.append("            case ").append(lit(v.alias())).append(" -> ").append(binding(v.type()))
                    .append(".readMembers(p, ctx);\n");
        }
        out.append("            default -> throw new jakarta.json.bind.JsonbException(\"Unknown @JsonbSubtype alias: \" + alias);\n");
        out.append("        };\n    }\n\n");
        return out.toString();
    }

    private String readClass() {
        if (model.creator() != null) return readThroughCreator(model.creator());
        String type = model.typeName();
        StringBuilder out = new StringBuilder();
        out.append(readHeader(type));
        out.append(READ_START);
        out.append(I8).append(type).append(" o = new ").append(type).append(NEW_END);
        out.append(LOOP_START);
        for (Property p : model.properties()) {
            if (p.write() == Access.NONE) continue;   // read-only: its key is skipped like an unknown one
            out.append(CASE).append(lit(p.jsonName())).append(" -> ");
            assignWith(out, p, target(p, "o"));
        }
        out.append(LOOP_END);
        out.append("        return o;\n    }\n\n");
        return out.toString();
    }

    /** The two {@code read} methods: without a context, it calls the one with. */
    private static String readHeader(String type) {
        return "    @Override\n    public " + type + " read(jakarta.json.stream.JsonParser p) {\n"
                + "        return read(p, null);\n    }\n\n"
                + "    @Override\n    public " + type + " read(jakarta.json.stream.JsonParser p, " + DESER_CONTEXT
                + CTX_OPEN;
    }

    /** How a value is stored into property {@code p} of {@code o}: a format with one {@code %s}. */
    private static String target(Property p, String o) {
        return p.write() == Access.FIELD ? o + "." + p.writeMember() + ASSIGN : o + "." + p.writeMember() + "(%s);";
    }

    /**
     * Reads the creator's parameters and the other writable properties into locals, calls the creator, then sets
     * the properties present in the document, as the runtime does.
     */
    private String readThroughCreator(RestBindings.Creator creator) {
        String type = model.typeName();
        StringBuilder out = new StringBuilder();
        out.append(readHeader(type));
        out.append(READ_START);
        List<String> args = new java.util.ArrayList<>();
        StringBuilder cases = new StringBuilder();
        for (RestBindings.Param param : creator.params()) {
            String local = newVariable();
            args.add(local);
            out.append(I8).append(render(param.type())).append(' ').append(local).append(" = ")
                    .append(defaultOf(param.type())).append(";\n");
            cases.append(CASE).append(lit(param.jsonName())).append(" -> ");
            assignValue(cases, param.type(), param.jsonName(), reader(param.type()), local + ASSIGN);
        }
        StringBuilder after = new StringBuilder();
        for (Property p : model.properties()) {
            if (p.write() == Access.NONE || creator.takes(p.jsonName())) continue;
            String local = newVariable();
            String seen = local + "_set";
            out.append(I8).append(render(p.type())).append(' ').append(local).append(" = ")
                    .append(defaultOf(p.type())).append(";\n");
            out.append("        boolean ").append(seen).append(" = false;\n");
            cases.append(CASE).append(lit(p.jsonName())).append(" -> ");
            assignValue(cases, p.type(), p.jsonName(), reader(p.type()), local + " = %s; " + seen + " = true;");
            after.append("        if (").append(seen).append(") ").append(String.format(target(p, "o"), local))
                    .append('\n');
        }
        out.append(LOOP_START).append(cases).append(LOOP_END);
        out.append(I8).append(type).append(" o;\n");
        out.append("        try { o = ").append(creator.call()).append('(').append(String.join(", ", args))
                .append("); }\n");
        out.append("        catch (jakarta.json.bind.JsonbException x) { throw x; }\n");
        out.append("        catch (Exception x) { throw new jakarta.json.bind.JsonbException(")
                .append(lit("@JsonbCreator of " + type + " failed: ")).append(" + x.getMessage(), x); }\n");
        out.append(after);
        out.append("        return o;\n    }\n\n");
        return out.toString();
    }

    /** Reads the property into the record's local. */
    private void assign(StringBuilder out, Property p, String local) {
        assignWith(out, p, local + ASSIGN);
    }

    /** Reads the property and stores it through {@code target}, a format with one {@code %s}. */
    private void assignWith(StringBuilder out, Property p, String target) {
        assignValue(out, p.type(), p.jsonName(), mappedReader(p.type(), p.in()), target);
    }

    /** Reads a value of type {@code t} with {@code reader} and stores it through {@code target}. */
    private void assignValue(StringBuilder out, TypeMirror t, String jsonName, String reader, String target) {
        if (t.getKind().isPrimitive()) {
            // A JSON null cannot go into a primitive: the runtime rejects it, so does the binding.
            String v = newVariable();
            out.append("{ ").append(boxed(t)).append(' ').append(v).append(" = ").append(reader)
                    .append("(p, p.next(), ctx); if (").append(v).append(" == null) throw new jakarta.json.bind.JsonbException(")
                    .append(lit("null for the primitive property " + jsonName)).append("); ")
                    .append(String.format(target, v)).append(" }\n");
        } else {
            out.append("{ ").append(String.format(target, reader + "(p, p.next(), ctx)")).append(" }\n");
        }
    }

    /** The name of a {@code static T rN(JsonParser p, Event e)} reading a value whose leading event is {@code e}. */
    private String reader(TypeMirror t) {
        String key = render(t);
        String existing = readerNames.get(key);
        if (existing != null) return existing;
        String name = "__r" + readerNames.size();
        readerNames.put(key, name);
        String type = t.getKind().isPrimitive() ? boxed(t) : key;
        StringBuilder r = new StringBuilder();
        r.append(PRIVATE_STATIC).append(type).append(' ').append(name)
                .append(READER_PARAMETERS);
        if (t.getKind().isPrimitive() || RestBindings.isPrimitiveArray(t)
                || RestBindings.LEAVES.contains(RestBindings.erasureName(t))) {
            r.append("        return (").append(type).append(") ").append(leaf(t))
                    .append(".read(new io.vidocq.champollion.jsonb.spi.PrimedJsonParser(e, p));\n");
        } else if (t.getKind() == TypeKind.ARRAY) {
            readArray(r, ((javax.lang.model.type.ArrayType) t).getComponentType());
        } else if (RestBindings.isDynamic(t)) {
            // Read as the runtime reads the type (an interface or an abstract class it cannot create: its error).
            r.append("        return io.vidocq.champollion.jsonb.spi.JsonbContexts.deserialization(ctx).deserialize(")
                    .append(key).append(".class, p);\n");
        } else {
            DeclaredType d = (DeclaredType) t;
            String erased = RestBindings.erasureName(d);
            TypeElement element = (TypeElement) d.asElement();
            if (element.getKind() == ElementKind.ENUM) {
                r.append(NULL_GIVES_NULL);
                r.append("        if (e != jakarta.json.stream.JsonParser.Event.VALUE_STRING) throw __expected(\"a string\", e);\n");
                r.append("        String s = p.getString();\n");
                r.append("        try { return ").append(key).append(".valueOf(s); }\n");
                r.append("        catch (IllegalArgumentException x) { throw new jakarta.json.bind.JsonbException(\"No constant \" + s + \" in ")
                        .append(key).append("\", x); }\n");
            } else if (RestBindings.COLLECTIONS.contains(erased)) {
                TypeMirror elem = d.getTypeArguments().get(0);
                String impl = "java.util.Set".equals(erased) ? "java.util.LinkedHashSet" : "java.util.ArrayList";
                String elemReader = reader(elem);
                r.append(NULL_GIVES_NULL);
                r.append("        if (e != jakarta.json.stream.JsonParser.Event.START_ARRAY) throw __expected(\"an array\", e);\n");
                r.append(I8).append(impl).append('<').append(render(elem)).append("> c = new ").append(impl)
                        .append("<>();\n");
                r.append("        while ((e = p.next()) != jakarta.json.stream.JsonParser.Event.END_ARRAY) {\n");
                r.append("            c.add(e == jakarta.json.stream.JsonParser.Event.VALUE_NULL ? null : ")
                        .append(elemReader).append(WITH_EVENT);
                r.append("        }\n        return c;\n");
            } else if (RestBindings.MAP.equals(erased)) {
                TypeMirror val = d.getTypeArguments().get(1);
                String valReader = reader(val);
                r.append(NULL_GIVES_NULL);
                r.append("        if (e != jakarta.json.stream.JsonParser.Event.START_OBJECT) throw __expected(\"an object\", e);\n");
                r.append("        java.util.LinkedHashMap<String, ").append(render(val))
                        .append("> m = new java.util.LinkedHashMap<>();\n");
                r.append("        while ((e = p.next()) != jakarta.json.stream.JsonParser.Event.END_OBJECT) {\n");
                r.append("            String k = p.getString();\n");
                r.append("            e = p.next();\n");
                r.append("            m.put(k, e == jakarta.json.stream.JsonParser.Event.VALUE_NULL ? null : ")
                        .append(valReader).append(WITH_EVENT);
                r.append("        }\n        return m;\n");
            } else if (RestBindings.OPTIONAL.equals(erased)) {
                TypeMirror inner = d.getTypeArguments().get(0);
                String innerReader = reader(inner);
                r.append("        if (e == jakarta.json.stream.JsonParser.Event.VALUE_NULL) return java.util.Optional.empty();\n");
                r.append("        return java.util.Optional.ofNullable(").append(innerReader).append(WITH_EVENT);
            } else {
                r.append("        return ").append(binding(element))
                        .append(".read(new io.vidocq.champollion.jsonb.spi.PrimedJsonParser(e, p), ctx);\n");
            }
        }
        r.append("    }\n\n");
        readers.append(r);
        return name;
    }

    /** The body of a reader of an array of {@code elem}, read as a list first. */
    private void readArray(StringBuilder r, TypeMirror elem) {
        String elemReader = reader(elem);
        r.append(NULL_GIVES_NULL);
        r.append("        if (e != jakarta.json.stream.JsonParser.Event.START_ARRAY) throw __expected(\"an array\", e);\n");
        r.append("        java.util.ArrayList<").append(render(elem)).append("> c = new java.util.ArrayList<>();\n");
        r.append("        while ((e = p.next()) != jakarta.json.stream.JsonParser.Event.END_ARRAY) {\n");
        r.append("            c.add(e == jakarta.json.stream.JsonParser.Event.VALUE_NULL ? null : ")
                .append(elemReader).append(WITH_EVENT);
        r.append("        }\n        return c.toArray(new ").append(render(elem)).append("[0]);\n");
    }

    // ===== helpers =====

    private String leaf(TypeMirror t) {
        return leaves.computeIfAbsent("of(" + classLiteral(t) + ")", k -> "__L" + leaves.size());
    }

    /** The codec of {@code t} under {@code format}, or its plain codec when {@code format} is {@code null}. */
    private String leaf(TypeMirror t, Format format) {
        if (format == null) return leaf(t);
        String factory = (format.date() ? "date(" : "number(") + classLiteral(t) + ", " + lit(format.pattern()) + ", "
                + lit(format.locale()) + ")";
        return leaves.computeIfAbsent(factory, k -> "__L" + leaves.size());
    }

    private static String classLiteral(TypeMirror t) {
        return (t.getKind().isPrimitive() ? t.getKind().name().toLowerCase(java.util.Locale.ROOT)
                : RestBindings.erasureName(t)) + ".class";
    }

    /** The reader method of a property of type {@code t}, through its adapter or its format if any. */
    private String mappedReader(TypeMirror t, Mapping mapping) {
        if (mapping.adapter() != null) return adaptedReader(t, mapping.adapter());
        if (mapping.custom() != null) return deserializerReader(t, mapping.custom());
        return mapping.format() != null ? formattedReader(t, mapping.format()) : reader(t);
    }

    /** A reader method of {@code t} that reads the adapted type, then calls the adapter's {@code adaptFromJson}. */
    private String adaptedReader(TypeMirror t, Adapter adapter) {
        String field = adapter(adapter);
        String key = field + ":" + render(t);
        String existing = readerNames.get(key);
        if (existing != null) return existing;
        String name = "__r" + readerNames.size();
        readerNames.put(key, name);
        String inner = reader(adapter.adapted());
        String adapted = render(adapter.adapted());
        readers.append(PRIVATE_STATIC).append(render(t)).append(' ').append(name)
                .append(READER_PARAMETERS)
                .append(I8).append(adapted).append(" a = ").append(inner).append("(p, e, ctx);\n")
                .append("        if (a == null) return null;\n")
                .append("        try { return ").append(field).append(".adaptFromJson(a); }\n")
                .append("        catch (Exception x) { throw new jakarta.json.bind.JsonbException(")
                .append("\"Adapter failure on fromJson: \" + x.getMessage(), x); }\n    }\n\n");
        return name;
    }

    /**
     * A reader method of {@code t} that calls a {@code JsonbDeserializer}: like the runtime, it hands it the parser on
     * the value's first event (a JSON {@code null} included) and the property's type.
     */
    private String deserializerReader(TypeMirror t, RestBindings.Instance deserializer) {
        String field = instance(deserializer) + ".get()";
        String key = field + ":" + render(t);
        String existing = readerNames.get(key);
        if (existing != null) return existing;
        String name = "__r" + readerNames.size();
        readerNames.put(key, name);
        readers.append(PRIVATE_STATIC).append(render(t)).append(' ').append(name).append(READER_PARAMETERS)
                .append("        return ").append(field).append(".deserialize(p, ")
                .append("io.vidocq.champollion.jsonb.spi.JsonbContexts.deserialization(ctx), ").append(render(t))
                .append(".class);\n    }\n\n");
        return name;
    }

    /** A reader method, like {@link #reader}, of a leaf {@code t} under {@code format}. */
    private String formattedReader(TypeMirror t, Format format) {
        String codec = leaf(t, format);
        String key = codec + ":" + render(t);
        String existing = readerNames.get(key);
        if (existing != null) return existing;
        String name = "__r" + readerNames.size();
        readerNames.put(key, name);
        String type = t.getKind().isPrimitive() ? boxed(t) : render(t);
        readers.append(PRIVATE_STATIC).append(type).append(' ').append(name)
                .append(READER_PARAMETERS)
                .append("        return (").append(type).append(") ").append(codec)
                .append(".read(new io.vidocq.champollion.jsonb.spi.PrimedJsonParser(e, p));\n    }\n\n");
        return name;
    }

    /** The field holding an instance of the adapter, created once with {@code new}. */
    private String adapter(Adapter adapter) {
        return instance(adapter.instance()) + ".get()";
    }

    /** The field holding the supplier of {@code instance}: the CDI bean, or one created, resolved on first use. */
    private String instance(RestBindings.Instance instance) {
        String field = instances.get(instance.className());
        if (field != null) return field;
        field = "__I" + instances.size();
        instances.put(instance.className(), field);
        creators.put(instance.className(), instance.creatable() ? instance.className() + "::new" : "null");
        return field;
    }
    private String binding(TypeElement element) {
        String name = others.get(element);
        if (name == null) throw new IllegalStateException("no binding for " + element);
        return bindings.computeIfAbsent(name, k -> "__B" + bindings.size());
    }

    private String newVariable() {
        return "__v" + variables++;
    }

    /** The source form of {@code t}, without its type annotations. */
    static String render(TypeMirror t) {
        if (t.getKind().isPrimitive()) return t.getKind().name().toLowerCase(java.util.Locale.ROOT);
        if (t.getKind() == TypeKind.ARRAY) return render(((javax.lang.model.type.ArrayType) t).getComponentType()) + "[]";
        if (t.getKind() != TypeKind.DECLARED) return t.toString();
        DeclaredType d = (DeclaredType) t;
        String raw = ((TypeElement) d.asElement()).getQualifiedName().toString();
        if (d.getTypeArguments().isEmpty()) return raw;
        StringBuilder sb = new StringBuilder(raw).append('<');
        for (int i = 0; i < d.getTypeArguments().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(render(d.getTypeArguments().get(i)));
        }
        return sb.append('>').toString();
    }

    private static String boxed(TypeMirror t) {
        return switch (t.getKind()) {
            case BOOLEAN -> "Boolean";
            case BYTE -> "Byte";
            case SHORT -> "Short";
            case INT -> "Integer";
            case LONG -> "Long";
            case CHAR -> "Character";
            case FLOAT -> "Float";
            case DOUBLE -> "Double";
            default -> render(t);
        };
    }

    private static String defaultOf(TypeMirror t) {
        return switch (t.getKind()) {
            case BOOLEAN -> "false";
            case BYTE -> "(byte) 0";
            case SHORT -> "(short) 0";
            case INT -> "0";
            case LONG -> "0L";
            case CHAR -> "'\\u0000'";
            case FLOAT -> "0f";
            case DOUBLE -> "0d";
            default -> RestBindings.OPTIONAL.equals(RestBindings.erasureName(t)) ? "java.util.Optional.empty()" : "null";
        };
    }

    /** {@code "name":} with RFC 8259 escaping, for the raw key fast path. */
    private static String preQuote(String name) {
        return '"' + escape(name) + "\":";
    }

    private static String lit(String value) {
        return '"' + escape(value) + '"';
    }

    private static String escape(String value) {
        StringBuilder sb = new StringBuilder();
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.toString();
    }
}
