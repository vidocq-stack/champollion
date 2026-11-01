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

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the types JSON-B binds because a JAX-RS resource of the compilation takes or returns them, with the types
 * their properties hold, and models those whose mapping is known at build time: records, and final classes with
 * public accessors or fields. {@link RestBindingEmitter} writes a {@code JsonbBinding} for each, so the runtime binds
 * them without reflection and their packages need no {@code opens} for JSON-B.
 *
 * <p>A type outside that model (a non-final class, a JSON-B customisation the generated code does not implement…)
 * keeps the runtime mapping; {@link #skipped()} says why, so the build can tell.</p>
 */
final class RestBindings {

    static final String PATH = "jakarta.ws.rs.Path";
    private static final String HTTP_METHOD = "jakarta.ws.rs.HttpMethod";
    private static final String JSONB_ANNOTATIONS = "jakarta.json.bind.annotation.";
    private static final String JSONB_PROPERTY = JSONB_ANNOTATIONS + "JsonbProperty";
    private static final String JSONB_TRANSIENT = JSONB_ANNOTATIONS + "JsonbTransient";
    private static final String JSONB_NILLABLE = JSONB_ANNOTATIONS + "JsonbNillable";
    private static final String JSONB_STATIC = "io.vidocq.champollion.jsonb.spi.JsonbStatic";
    private static final String JAVA_LANG_OBJECT = "java.lang.Object";

    /** The types JSON-B maps natively; {@code JsonbLeaf} hands their codecs to the generated code. */
    static final Set<String> LEAVES = Set.of(
            "java.lang.String", "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float",
            "java.lang.Short", "java.lang.Byte", "java.lang.Boolean", "java.lang.Character",
            "java.math.BigDecimal", "java.math.BigInteger", "java.util.UUID", "java.net.URI",
            "java.time.Instant", "java.time.LocalDate", "java.time.LocalTime", "java.time.LocalDateTime",
            "java.time.OffsetDateTime", "java.time.OffsetTime", "java.time.ZonedDateTime", "java.time.Duration",
            "java.time.Period", "java.time.MonthDay", "java.time.YearMonth", "java.time.Year");
    /** Collections a property may hold, read back as an {@code ArrayList} or a {@code LinkedHashSet}. */
    static final Set<String> COLLECTIONS = Set.of("java.util.List", "java.util.Collection", "java.util.Set");
    static final String OPTIONAL = "java.util.Optional";
    static final String MAP = "java.util.Map";
    /** Wrappers of a resource method's result, unwrapped to find the entity type. */
    private static final Set<String> ASYNC = Set.of("java.util.concurrent.CompletionStage",
            "java.util.concurrent.CompletableFuture");

    /** How a property is read from, or written into, an instance. */
    enum Access { NONE, METHOD, FIELD, COMPONENT }

    /** One JSON property of a modelled type. */
    record Property(String jsonName, TypeMirror type, Access read, String readMember, Access write,
                    String writeMember, boolean nillable) {}

    /** A type a binding is generated for. */
    record Model(TypeElement type, boolean isRecord, List<Property> properties) {

        String packageName() {
            Element e = type;
            while (!(e instanceof PackageElement)) e = e.getEnclosingElement();
            return ((PackageElement) e).getQualifiedName().toString();
        }

        /** {@code Outer$Inner$$Binding}: a top-level class of the type's package. */
        String bindingSimpleName() {
            Deque<String> names = new ArrayDeque<>();
            for (Element e = type; !(e instanceof PackageElement); e = e.getEnclosingElement()) {
                names.push(e.getSimpleName().toString());
            }
            return String.join("$", names) + "$$Binding";
        }

        String bindingName() {
            String pkg = packageName();
            return pkg.isEmpty() ? bindingSimpleName() : pkg + "." + bindingSimpleName();
        }

        String typeName() {
            return type.getQualifiedName().toString();
        }
    }

    private final ProcessingEnvironment env;
    private final Set<TypeElement> compiled;
    private final Map<TypeElement, Model> models = new LinkedHashMap<>();
    private final Map<TypeElement, String> skipped = new LinkedHashMap<>();
    /** Records annotated {@code @JsonbStatic}: they have their own binding, which the generated code calls. */
    private final Map<TypeElement, String> staticBound = new LinkedHashMap<>();
    private final Deque<TypeElement> pending = new ArrayDeque<>();

    /**
     * @param compiled the types of this compilation: a binding is generated in the package of its type, so only
     *                 those can have one
     */
    RestBindings(ProcessingEnvironment env, Set<TypeElement> compiled) {
        this.env = env;
        this.compiled = compiled;
    }

    /** Collects the types the resources of {@code resources} reach, and models those it can. */
    RestBindings collect(Collection<? extends Element> resources) {
        for (Element element : resources) {
            TypeElement resource = element instanceof TypeElement t ? t
                    : element.getEnclosingElement() instanceof TypeElement t ? t : null;
            if (resource == null) continue;
            for (ExecutableElement method : ElementFilter.methodsIn(resource.getEnclosedElements())) {
                if (!isResourceMethod(method)) continue;
                reach(method.getReturnType());
                for (VariableElement parameter : method.getParameters()) {
                    if (isEntityParameter(parameter)) reach(parameter.asType());
                }
            }
        }
        while (!pending.isEmpty()) {
            TypeElement type = pending.poll();
            if (models.containsKey(type) || skipped.containsKey(type) || staticBound.containsKey(type)) continue;
            String reason = model(type);
            if (reason != null) skipped.put(type, reason);
        }
        dropModelsReferencingSkipped();
        return this;
    }

    /** The types bindings are generated for. */
    Collection<Model> models() {
        return models.values();
    }

    /** Every type that has a binding, generated here or by {@code @JsonbStatic}: type to binding name. */
    Map<TypeElement, String> bindingNames() {
        Map<TypeElement, String> names = new LinkedHashMap<>(staticBound);
        for (Model m : models.values()) names.put(m.type(), m.bindingName());
        return names;
    }

    /** The types the resources reach that keep the runtime mapping, with the reason. */
    Map<TypeElement, String> skipped() {
        return skipped;
    }

    // ===== Roots =====

    private boolean isResourceMethod(ExecutableElement method) {
        if (method.getModifiers().contains(Modifier.STATIC)) return false;
        for (AnnotationMirror a : method.getAnnotationMirrors()) {
            for (AnnotationMirror meta : a.getAnnotationType().asElement().getAnnotationMirrors()) {
                if (HTTP_METHOD.equals(nameOf(meta))) return true;
            }
        }
        return false;
    }

    /** A parameter JAX-RS fills from the request body: no {@code jakarta.ws.rs} annotation. */
    private static boolean isEntityParameter(VariableElement parameter) {
        for (AnnotationMirror a : parameter.getAnnotationMirrors()) {
            if (nameOf(a).startsWith("jakarta.ws.rs.")) return false;
        }
        return true;
    }

    /** Queues the application types {@code type} leads to, through collections, maps, optionals and futures. */
    private void reach(TypeMirror type) {
        if (type.getKind() == TypeKind.ARRAY) {
            reach(((javax.lang.model.type.ArrayType) type).getComponentType());
            return;
        }
        if (type.getKind() != TypeKind.DECLARED) return;
        DeclaredType declared = (DeclaredType) type;
        String name = erasureName(declared);
        if (COLLECTIONS.contains(name) || OPTIONAL.equals(name) || MAP.equals(name) || ASYNC.contains(name)) {
            for (TypeMirror argument : declared.getTypeArguments()) reach(argument);
            return;
        }
        TypeElement element = (TypeElement) declared.asElement();
        if (LEAVES.contains(name) || element.getKind() == ElementKind.ENUM || JAVA_LANG_OBJECT.equals(name)) return;
        if (element.getKind() != ElementKind.RECORD && element.getKind() != ElementKind.CLASS) return;
        if (name.startsWith("java.") || name.startsWith("jakarta.")) return;
        pending.add(element);
    }

    // ===== Model =====

    /** Models {@code type}, or says why its mapping is left to the runtime. */
    private String model(TypeElement type) {
        if (hasAnnotation(type, JSONB_STATIC)) {
            staticBound.put(type, type.getQualifiedName() + "$$Binding");
            return null;
        }
        if (!compiled.contains(type)) {
            return "not compiled with the resources (a dependency's type, or an incremental build)";
        }
        String where = placement(type);
        if (where != null) return where;
        for (AnnotationMirror a : type.getAnnotationMirrors()) {
            if (nameOf(a).startsWith(JSONB_ANNOTATIONS)) {
                return "the class carries @" + simpleNameOf(a) + ", a customisation only the runtime implements";
            }
        }
        List<Property> properties = new ArrayList<>();
        String reason = type.getKind() == ElementKind.RECORD ? recordProperties(type, properties)
                : classProperties(type, properties);
        if (reason != null) return reason;
        Set<String> names = new java.util.HashSet<>();
        for (Property p : properties) {
            if (!names.add(p.jsonName())) return "two properties have the JSON name \"" + p.jsonName() + "\"";
            String unsupported = supported(p.type());
            if (unsupported != null) return "property \"" + p.jsonName() + "\": " + unsupported;
        }
        // The runtime's default order: a class's properties lexicographically (JSON-B 3.0 §4.4, one declaring class
        // here), a record's components in their declaration order.
        if (type.getKind() != ElementKind.RECORD) properties.sort(Comparator.comparing(Property::jsonName));
        models.put(type, new Model(type, type.getKind() == ElementKind.RECORD, List.copyOf(properties)));
        for (Property p : properties) reach(p.type());
        return null;
    }

    /** Where the type sits: a binding of its package must reach it. */
    private static String placement(TypeElement type) {
        for (Element e = type; !(e instanceof PackageElement); e = e.getEnclosingElement()) {
            if (e instanceof ExecutableElement) return "a local class";
            if (e.getModifiers().contains(Modifier.PRIVATE)) return "private";
            if (e instanceof TypeElement t && t.getKind() == ElementKind.CLASS
                    && t.getEnclosingElement() instanceof TypeElement outer && !outer.getKind().isInterface()
                    && !t.getModifiers().contains(Modifier.STATIC)) {
                return "an inner (non-static) class";
            }
        }
        if (type.getModifiers().contains(Modifier.ABSTRACT)) return "abstract";
        if (!type.getTypeParameters().isEmpty()) return "generic";
        return null;
    }

    private String recordProperties(TypeElement type, List<Property> properties) {
        ExecutableElement canonical = canonicalConstructor(type);
        if (canonical == null || canonical.getModifiers().contains(Modifier.PRIVATE)) {
            return "no accessible canonical constructor";
        }
        for (ExecutableElement m : ElementFilter.methodsIn(type.getEnclosedElements())) {
            if (hasJsonbCreator(m)) return "a @JsonbCreator factory";
        }
        for (var component : type.getRecordComponents()) {
            List<Element> members = new ArrayList<>(List.of(component));
            if (component.getAccessor() != null) members.add(component.getAccessor());
            String annotations = jsonbAnnotations(members);
            if (annotations != null) return "component " + component.getSimpleName() + ": " + annotations;
            String name = component.getSimpleName().toString();
            // A transient component is not a JSON property: the emitter passes its default to the constructor.
            if (isTransient(members)) continue;
            properties.add(new Property(jsonName(members, name), component.asType(), Access.METHOD, name + "()",
                    Access.COMPONENT, name, hasAnnotation(members, JSONB_NILLABLE)));
        }
        return null;
    }

    private String classProperties(TypeElement type, List<Property> properties) {
        if (!type.getModifiers().contains(Modifier.FINAL)) {
            return "not final: an instance of a subclass would be written without its own properties";
        }
        if (!JAVA_LANG_OBJECT.equals(erasureName(type.getSuperclass()))) {
            return "it extends " + type.getSuperclass() + "; a binding is generated for Object-rooted classes";
        }
        boolean noArg = ElementFilter.constructorsIn(type.getEnclosedElements()).stream()
                .anyMatch(c -> c.getParameters().isEmpty() && !c.getModifiers().contains(Modifier.PRIVATE));
        boolean anyConstructor = !ElementFilter.constructorsIn(type.getEnclosedElements()).isEmpty();
        if (!noArg && anyConstructor) return "no accessible no-argument constructor";
        for (ExecutableElement c : ElementFilter.constructorsIn(type.getEnclosedElements())) {
            if (hasJsonbCreator(c)) return "a @JsonbCreator constructor";
        }
        Map<String, VariableElement> fields = new LinkedHashMap<>();
        for (VariableElement f : ElementFilter.fieldsIn(type.getEnclosedElements())) {
            if (!f.getModifiers().contains(Modifier.STATIC)) fields.put(f.getSimpleName().toString(), f);
        }
        Map<String, ExecutableElement> getters = new LinkedHashMap<>();
        Map<String, ExecutableElement> setters = new LinkedHashMap<>();
        for (ExecutableElement m : ElementFilter.methodsIn(type.getEnclosedElements())) {
            if (m.getModifiers().contains(Modifier.STATIC)) continue;
            if (hasJsonbCreator(m)) return "a @JsonbCreator factory";
            String n = m.getSimpleName().toString();
            String property = null;
            boolean getter = false;
            if (m.getParameters().isEmpty() && m.getReturnType().getKind() != TypeKind.VOID) {
                if (n.startsWith("get") && n.length() > 3) property = n.substring(3);
                else if (n.startsWith("is") && n.length() > 2 && m.getReturnType().getKind() == TypeKind.BOOLEAN) {
                    property = n.substring(2);
                }
                getter = property != null;
            } else if (m.getParameters().size() == 1 && m.getReturnType().getKind() == TypeKind.VOID
                    && n.startsWith("set") && n.length() > 3) {
                property = n.substring(3);
            }
            if (property == null || !Character.isUpperCase(property.charAt(0))) continue;
            if (property.length() > 1 && Character.isUpperCase(property.charAt(1))) {
                return "accessor " + n + "(): its property name depends on decapitalisation rules";
            }
            String name = Character.toLowerCase(property.charAt(0)) + property.substring(1);
            if (!m.getModifiers().contains(Modifier.PUBLIC)) {
                return "accessor " + n + "() is not public; JSON-B's visibility rules for it are left to the runtime";
            }
            if ((getter ? getters : setters).put(name, m) != null) return "two accessors for property " + name;
        }
        var all = new java.util.TreeSet<String>();
        all.addAll(fields.keySet());
        all.addAll(getters.keySet());
        all.addAll(setters.keySet());
        for (String name : all) {
            VariableElement f = fields.get(name);
            ExecutableElement g = getters.get(name);
            ExecutableElement s = setters.get(name);
            boolean publicField = f != null && f.getModifiers().contains(Modifier.PUBLIC);
            if (f != null && f.getModifiers().contains(Modifier.TRANSIENT) && (g != null || s != null || publicField)) {
                return "field " + name + " is transient; JSON-B leaves its meaning to the implementation";
            }
            Access read = g != null ? Access.METHOD : publicField ? Access.FIELD : Access.NONE;
            Access write = s != null ? Access.METHOD
                    : publicField && !f.getModifiers().contains(Modifier.FINAL) ? Access.FIELD : Access.NONE;
            if (read == Access.NONE && write == Access.NONE) continue;   // a private field: not a JSON-B property
            TypeMirror t = g != null ? g.getReturnType() : s != null ? s.getParameters().get(0).asType() : f.asType();
            if ((g != null && !same(t, g.getReturnType())) || (s != null && !same(t, s.getParameters().get(0).asType()))
                    || (f != null && !same(t, f.asType()))) {
                return "property " + name + " has different types on its field and accessors";
            }
            List<Element> members = new ArrayList<>();
            if (f != null) members.add(f);
            if (g != null) members.add(g);
            if (s != null) members.add(s);
            String annotations = jsonbAnnotations(members);
            if (annotations != null) return "property " + name + ": " + annotations;
            if (isTransient(members)) {
                long marked = members.stream().filter(m -> hasAnnotation(m, JSONB_TRANSIENT)).count();
                if (marked != members.size() && !(f != null && hasAnnotation(f, JSONB_TRANSIENT))) {
                    return "property " + name + " is @JsonbTransient on part of its members only";
                }
                continue;
            }
            properties.add(new Property(jsonName(members, name), t,
                    read, read == Access.METHOD ? g.getSimpleName() + "()" : name,
                    write, write == Access.METHOD ? s.getSimpleName().toString() : name,
                    hasAnnotation(members, JSONB_NILLABLE)));
        }
        return null;
    }

    /** Whether {@code type} is mapped by the generated code; {@code null} when it is, else why not. */
    private String supported(TypeMirror type) {
        switch (type.getKind()) {
            case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE -> {
                return null;
            }
            case DECLARED -> {
                DeclaredType declared = (DeclaredType) type;
                String name = erasureName(declared);
                List<? extends TypeMirror> args = declared.getTypeArguments();
                if (COLLECTIONS.contains(name) || OPTIONAL.equals(name)) {
                    return args.size() == 1 ? supportedElement(args.get(0)) : "raw " + name;
                }
                if (MAP.equals(name)) {
                    if (args.size() != 2 || !"java.lang.String".equals(erasureName(args.get(0)))) {
                        return "a map whose keys are not String";
                    }
                    return supportedElement(args.get(1));
                }
                return supportedElement(type);
            }
            default -> {
                return "type " + type + " (arrays, type variables and wildcards are left to the runtime)";
            }
        }
    }

    /** A value a container holds, or a single value: a leaf, an enum, or a type that gets a binding. */
    private String supportedElement(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return "type " + type + " inside a container";
        }
        DeclaredType declared = (DeclaredType) type;
        String name = erasureName(declared);
        if (LEAVES.contains(name)) return null;
        TypeElement element = (TypeElement) declared.asElement();
        if (element.getKind() == ElementKind.ENUM) {
            for (Element constant : element.getEnclosedElements()) {
                for (AnnotationMirror a : constant.getAnnotationMirrors()) {
                    if (nameOf(a).startsWith(JSONB_ANNOTATIONS)) return "enum " + name + " customises its constants";
                }
            }
            return element.getModifiers().contains(Modifier.PRIVATE) ? "private enum " + name : null;
        }
        if (COLLECTIONS.contains(name) || OPTIONAL.equals(name) || MAP.equals(name)) {
            return "nested container " + type;
        }
        if (element.getKind() == ElementKind.RECORD || element.getKind() == ElementKind.CLASS) {
            if (name.startsWith("java.") || name.startsWith("jakarta.")) return "type " + name;
            pending.add(element);
            return null;   // provisionally: dropModelsReferencingSkipped checks it got a binding
        }
        return "type " + name;
    }

    /** A model whose property needs a type that got no binding keeps the runtime mapping, and so on up. */
    private void dropModelsReferencingSkipped() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var it = models.entrySet().iterator(); it.hasNext(); ) {
                var entry = it.next();
                String missing = missingBinding(entry.getValue());
                if (missing != null) {
                    skipped.put(entry.getKey(), missing);
                    it.remove();
                    changed = true;
                }
            }
        }
    }

    private String missingBinding(Model model) {
        for (Property p : model.properties()) {
            for (TypeElement used : declaredTypes(p.type())) {
                if (!accessibleFrom(used, model.packageName())) {
                    return "property \"" + p.jsonName() + "\" holds " + used.getQualifiedName()
                            + ", which the binding, in package " + model.packageName() + ", cannot reach";
                }
            }
            for (TypeElement used : referencedTypes(p.type())) {
                if (!models.containsKey(used) && !staticBound.containsKey(used)) {
                    return "property \"" + p.jsonName() + "\" holds " + used.getQualifiedName()
                            + ", which keeps the runtime mapping";
                }
            }
        }
        return null;
    }

    /** Every declared type {@code type} names, itself and its type arguments, recursively. */
    private static List<TypeElement> declaredTypes(TypeMirror type) {
        List<TypeElement> found = new ArrayList<>();
        if (type.getKind() != TypeKind.DECLARED) return found;
        DeclaredType declared = (DeclaredType) type;
        found.add((TypeElement) declared.asElement());
        for (TypeMirror argument : declared.getTypeArguments()) found.addAll(declaredTypes(argument));
        return found;
    }

    /** Whether code of package {@code pkg} can name {@code type}: same package, or public up to its top level. */
    private static boolean accessibleFrom(TypeElement type, String pkg) {
        Element e = type;
        while (!(e instanceof PackageElement)) e = e.getEnclosingElement();
        if (((PackageElement) e).getQualifiedName().contentEquals(pkg)) return true;
        for (Element t = type; !(t instanceof PackageElement); t = t.getEnclosingElement()) {
            if (!t.getModifiers().contains(Modifier.PUBLIC)) return false;
        }
        return true;
    }

    /** The record and class types a property type refers to directly or as a container's argument. */
    static List<TypeElement> referencedTypes(TypeMirror type) {
        List<TypeElement> found = new ArrayList<>();
        if (type.getKind() != TypeKind.DECLARED) return found;
        DeclaredType declared = (DeclaredType) type;
        String name = erasureName(declared);
        if (COLLECTIONS.contains(name) || OPTIONAL.equals(name) || MAP.equals(name)) {
            for (TypeMirror argument : declared.getTypeArguments()) found.addAll(referencedTypes(argument));
            return found;
        }
        TypeElement element = (TypeElement) declared.asElement();
        if ((element.getKind() == ElementKind.RECORD || element.getKind() == ElementKind.CLASS)
                && !LEAVES.contains(name)) {
            found.add(element);
        }
        return found;
    }

    // ===== Helpers =====

    private ExecutableElement canonicalConstructor(TypeElement record) {
        List<TypeMirror> types = record.getRecordComponents().stream().map(Element::asType)
                .map(t -> env.getTypeUtils().erasure(t)).toList();
        for (ExecutableElement c : ElementFilter.constructorsIn(record.getEnclosedElements())) {
            List<TypeMirror> params = c.getParameters().stream().map(Element::asType)
                    .map(t -> env.getTypeUtils().erasure(t)).toList();
            if (params.size() == types.size()) {
                boolean match = true;
                for (int i = 0; i < params.size() && match; i++) {
                    match = env.getTypeUtils().isSameType(params.get(i), types.get(i));
                }
                if (match) return c;
            }
        }
        return null;
    }

    private boolean same(TypeMirror a, TypeMirror b) {
        return env.getTypeUtils().isSameType(a, b);
    }

    /** The JSON-B annotations of {@code members} other than the three the generated code implements, or null. */
    private static String jsonbAnnotations(List<Element> members) {
        for (Element m : members) {
            for (AnnotationMirror a : m.getAnnotationMirrors()) {
                String n = nameOf(a);
                if (n.startsWith(JSONB_ANNOTATIONS) && !n.equals(JSONB_PROPERTY) && !n.equals(JSONB_TRANSIENT)
                        && !n.equals(JSONB_NILLABLE)) {
                    return "@" + simpleNameOf(a) + ", a customisation only the runtime implements";
                }
            }
        }
        return null;
    }

    private static boolean hasJsonbCreator(Element e) {
        return hasAnnotation(e, JSONB_ANNOTATIONS + "JsonbCreator");
    }

    private static boolean isTransient(List<Element> members) {
        return hasAnnotation(members, JSONB_TRANSIENT);
    }

    /** The {@code @JsonbProperty} name, the same on every member that sets one, else the property's name. */
    private static String jsonName(List<Element> members, String fallback) {
        String name = null;
        for (Element m : members) {
            for (AnnotationMirror a : m.getAnnotationMirrors()) {
                if (!JSONB_PROPERTY.equals(nameOf(a))) continue;
                for (var e : a.getElementValues().entrySet()) {
                    if (e.getKey().getSimpleName().contentEquals("value")) {
                        String v = (String) e.getValue().getValue();
                        if (!v.isEmpty()) name = v;
                    }
                }
            }
        }
        return name == null ? fallback : name;
    }

    private static boolean hasAnnotation(List<Element> members, String annotation) {
        return members.stream().anyMatch(m -> hasAnnotation(m, annotation));
    }

    private static boolean hasAnnotation(Element e, String annotation) {
        for (AnnotationMirror a : e.getAnnotationMirrors()) {
            if (annotation.equals(nameOf(a))) return true;
        }
        return false;
    }

    static String nameOf(AnnotationMirror a) {
        return ((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().toString();
    }

    private static String simpleNameOf(AnnotationMirror a) {
        return a.getAnnotationType().asElement().getSimpleName().toString();
    }

    static String erasureName(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) return type.toString();
        return ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().toString();
    }
}
