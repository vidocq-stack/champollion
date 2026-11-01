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
    private static final String PROPERTY = "property ";
    private static final String QUOTED_PROPERTY = "property \"";
    private static final String TYPE = "type ";

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
    record Model(TypeElement type, boolean isRecord, boolean subclassable, List<Property> properties) {

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
            TypeElement resource = resourceOf(element);
            if (resource != null) reachFrom(resource);
        }
        while (!pending.isEmpty()) {
            TypeElement type = pending.poll();
            if (!isDecided(type)) {
                String reason = model(type);
                if (reason != null) skipped.put(type, reason);
            }
        }
        dropModelsReferencingSkipped();
        return this;
    }

    /** The resource class an {@code @Path} element belongs to: the class itself, or the class of a method. */
    private static TypeElement resourceOf(Element element) {
        if (element instanceof TypeElement type) return type;
        return element.getEnclosingElement() instanceof TypeElement type ? type : null;
    }

    /** Queues what the resource methods of {@code resource} return and take as entity. */
    private void reachFrom(TypeElement resource) {
        for (ExecutableElement method : ElementFilter.methodsIn(resource.getEnclosedElements())) {
            if (isResourceMethod(method)) {
                reach(method.getReturnType());
                method.getParameters().stream().filter(RestBindings::isEntityParameter)
                        .forEach(parameter -> reach(parameter.asType()));
            }
        }
    }

    /** Queues the classes of the compilation that extend {@code type}, directly or not. */
    private void reachSubclasses(TypeElement type) {
        var erased = env.getTypeUtils().erasure(type.asType());
        for (TypeElement candidate : compiled) {
            if (candidate != type && candidate.getKind() == ElementKind.CLASS
                    && env.getTypeUtils().isSubtype(env.getTypeUtils().erasure(candidate.asType()), erased)) {
                pending.add(candidate);
            }
        }
    }

    private boolean isDecided(TypeElement type) {
        return models.containsKey(type) || skipped.containsKey(type) || staticBound.containsKey(type);
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
            if (unsupported != null) return QUOTED_PROPERTY + p.jsonName() + "\": " + unsupported;
        }
        // The runtime's default order: a class's properties lexicographically (JSON-B 3.0 §4.4, one declaring class
        // here), a record's components in their declaration order.
        boolean isRecord = type.getKind() == ElementKind.RECORD;
        boolean subclassable = !isRecord && !type.getModifiers().contains(Modifier.FINAL);
        models.put(type, new Model(type, isRecord, subclassable, List.copyOf(properties)));
        for (Property p : properties) reach(p.type());
        // An instance of a subclass reaches the runtime, which uses the subclass's binding when it has one.
        if (subclassable) reachSubclasses(type);
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
        String shape = classShape(type);
        if (shape != null) return shape;
        Set<String> seen = new java.util.HashSet<>();
        // The runtime's order: the properties of each class of the hierarchy, from the top one down, lexicographically
        // within a class.
        for (TypeElement level : hierarchy(type)) {
            List<Property> own = new ArrayList<>();
            String reason = levelProperties(level, own);
            if (reason != null) return reason;
            for (Property p : own) {
                if (!seen.add(p.jsonName())) {
                    return QUOTED_PROPERTY + p.jsonName() + "\" is declared on several classes of the hierarchy";
                }
            }
            own.sort(Comparator.comparing(Property::jsonName));
            properties.addAll(own);
        }
        return defaultAccessors(type);
    }

    /** The JSON-B properties {@code level} declares itself. */
    private String levelProperties(TypeElement level, List<Property> own) {
        Map<String, VariableElement> fields = new LinkedHashMap<>();
        for (VariableElement f : ElementFilter.fieldsIn(level.getEnclosedElements())) {
            if (!f.getModifiers().contains(Modifier.STATIC)) fields.put(f.getSimpleName().toString(), f);
        }
        Map<String, ExecutableElement> getters = new LinkedHashMap<>();
        Map<String, ExecutableElement> setters = new LinkedHashMap<>();
        Set<String> hidden = new java.util.HashSet<>();
        String accessors = accessors(level, getters, setters, hidden);
        if (accessors != null) return accessors;
        var names = new java.util.TreeSet<String>();
        names.addAll(fields.keySet());
        names.addAll(getters.keySet());
        names.addAll(setters.keySet());
        for (String name : names) {
            Members m = new Members(name, fields.get(name), getters.get(name), setters.get(name));
            if (hidden.contains(name) && m.publicField()) {
                return PROPERTY + name + " has a public field and a non-public accessor; JSON-B's visibility rules"
                        + " for it are left to the runtime";
            }
            String reason = classProperty(m, own);
            if (reason != null) return reason;
        }
        return null;
    }

    /** {@code type} and its superclasses but {@code Object}, the top one first. */
    private static List<TypeElement> hierarchy(TypeElement type) {
        Deque<TypeElement> chain = new ArrayDeque<>();
        for (TypeElement t = type; t != null && !JAVA_LANG_OBJECT.equals(t.getQualifiedName().toString());
             t = superclassOf(t)) {
            chain.push(t);
        }
        return List.copyOf(chain);
    }

    private static TypeElement superclassOf(TypeElement type) {
        TypeMirror s = type.getSuperclass();
        return s.getKind() == TypeKind.DECLARED ? (TypeElement) ((DeclaredType) s).asElement() : null;
    }

    /** A default getter of an interface the class implements: the runtime may see a property there. */
    private String defaultAccessors(TypeElement type) {
        for (TypeElement level : hierarchy(type)) {
            for (TypeMirror i : env.getTypeUtils().directSupertypes(level.asType())) {
                String reason = defaultAccessorsOf((TypeElement) ((DeclaredType) i).asElement());
                if (reason != null) return reason;
            }
        }
        return null;
    }

    private static String defaultAccessorsOf(TypeElement type) {
        if (!type.getKind().isInterface()) return null;
        for (ExecutableElement m : ElementFilter.methodsIn(type.getEnclosedElements())) {
            if (m.getModifiers().contains(Modifier.DEFAULT) && isGetter(m)) {
                return "interface " + type.getQualifiedName() + " has a default accessor " + m.getSimpleName() + "()";
            }
        }
        for (TypeMirror parent : type.getInterfaces()) {
            String reason = defaultAccessorsOf((TypeElement) ((DeclaredType) parent).asElement());
            if (reason != null) return reason;
        }
        return null;
    }

    /** Why a class cannot have a binding, whatever its properties; {@code null} when it can. */
    private static String classShape(TypeElement type) {
        var constructors = ElementFilter.constructorsIn(type.getEnclosedElements());
        boolean noArg = constructors.stream()
                .anyMatch(c -> c.getParameters().isEmpty() && !c.getModifiers().contains(Modifier.PRIVATE));
        if (!noArg && !constructors.isEmpty()) return "no accessible no-argument constructor";
        if (constructors.stream().anyMatch(RestBindings::hasJsonbCreator)) return "a @JsonbCreator constructor";
        return null;
    }

    /**
     * Sorts the public bean accessors of {@code type} by property; a non-public one, which JSON-B's default
     * visibility ignores, goes to {@code hidden}. {@code null}, or why the accessors cannot be mapped.
     */
    private static String accessors(TypeElement type, Map<String, ExecutableElement> getters,
                                    Map<String, ExecutableElement> setters, Set<String> hidden) {
        for (ExecutableElement m : ElementFilter.methodsIn(type.getEnclosedElements())) {
            String reason = m.getModifiers().contains(Modifier.STATIC) ? null : accessor(m, getters, setters, hidden);
            if (reason != null) return reason;
        }
        return null;
    }

    private static String accessor(ExecutableElement m, Map<String, ExecutableElement> getters,
                                   Map<String, ExecutableElement> setters, Set<String> hidden) {
        if (hasJsonbCreator(m)) return "a @JsonbCreator factory";
        String n = m.getSimpleName().toString();
        boolean getter = isGetter(m);
        if (!getter && !isSetter(m)) return null;
        String property = n.substring(getter && n.startsWith("is") ? 2 : 3);
        if (!Character.isUpperCase(property.charAt(0))) return null;
        if (property.length() > 1 && Character.isUpperCase(property.charAt(1))) {
            return "accessor " + n + "(): its property name depends on decapitalisation rules";
        }
        String name = Character.toLowerCase(property.charAt(0)) + property.substring(1);
        if (!m.getModifiers().contains(Modifier.PUBLIC)) {
            hidden.add(name);
            return null;
        }
        Map<String, ExecutableElement> kind = getter ? getters : setters;
        return kind.put(name, m) == null ? null : "two accessors for property " + name;
    }

    private static boolean isGetter(ExecutableElement m) {
        String n = m.getSimpleName().toString();
        if (!m.getParameters().isEmpty() || m.getReturnType().getKind() == TypeKind.VOID) return false;
        return n.startsWith("get") && n.length() > 3
                || n.startsWith("is") && n.length() > 2 && m.getReturnType().getKind() == TypeKind.BOOLEAN;
    }

    private static boolean isSetter(ExecutableElement m) {
        String n = m.getSimpleName().toString();
        return m.getParameters().size() == 1 && m.getReturnType().getKind() == TypeKind.VOID
                && n.startsWith("set") && n.length() > 3;
    }

    /** The field and public accessors of one property of a class, any of them {@code null}. */
    private record Members(String name, VariableElement field, ExecutableElement getter, ExecutableElement setter) {

        boolean publicField() {
            return field != null && field.getModifiers().contains(Modifier.PUBLIC);
        }

        Access read() {
            if (getter != null) return Access.METHOD;
            return publicField() ? Access.FIELD : Access.NONE;
        }

        Access write() {
            if (setter != null) return Access.METHOD;
            return publicField() && !field.getModifiers().contains(Modifier.FINAL) ? Access.FIELD : Access.NONE;
        }

        TypeMirror type() {
            if (getter != null) return getter.getReturnType();
            if (setter != null) return setter.getParameters().get(0).asType();
            return field.asType();
        }

        String readMember() {
            return getter != null ? getter.getSimpleName() + "()" : name;
        }

        String writeMember() {
            return setter != null ? setter.getSimpleName().toString() : name;
        }

        List<Element> all() {
            List<Element> members = new ArrayList<>();
            if (field != null) members.add(field);
            if (getter != null) members.add(getter);
            if (setter != null) members.add(setter);
            return members;
        }
    }

    /** Adds the property {@code m} describes, if JSON-B sees one; {@code null}, or why it cannot be mapped. */
    private String classProperty(Members m, List<Property> properties) {
        boolean transientField = m.field() != null && m.field().getModifiers().contains(Modifier.TRANSIENT);
        if (transientField && (m.getter() != null || m.setter() != null || m.publicField())) {
            return "field " + m.name() + " is transient; JSON-B leaves its meaning to the implementation";
        }
        if (m.read() == Access.NONE && m.write() == Access.NONE) return null;   // a private field: no JSON-B property
        TypeMirror t = m.type();
        if (!sameTypes(t, m)) return PROPERTY + m.name() + " has different types on its field and accessors";
        List<Element> members = m.all();
        String annotations = jsonbAnnotations(members);
        if (annotations != null) return PROPERTY + m.name() + ": " + annotations;
        if (isTransient(members)) {
            boolean everywhere = members.stream().allMatch(e -> hasAnnotation(e, JSONB_TRANSIENT));
            boolean onField = m.field() != null && hasAnnotation(m.field(), JSONB_TRANSIENT);
            return everywhere || onField ? null
                    : PROPERTY + m.name() + " is @JsonbTransient on part of its members only";
        }
        properties.add(new Property(jsonName(members, m.name()), t, m.read(), m.readMember(), m.write(),
                m.writeMember(), hasAnnotation(members, JSONB_NILLABLE)));
        return null;
    }

    private boolean sameTypes(TypeMirror t, Members m) {
        return (m.getter() == null || same(t, m.getter().getReturnType()))
                && (m.setter() == null || same(t, m.setter().getParameters().get(0).asType()))
                && (m.field() == null || same(t, m.field().asType()));
    }

    /** Whether {@code type} is mapped by the generated code; {@code null} when it is, else why not. */
    private String supported(TypeMirror type) {
        if (type.getKind().isPrimitive()) return null;
        if (type.getKind() != TypeKind.DECLARED) {
            return TYPE + type + " (arrays, type variables and wildcards are left to the runtime)";
        }
        DeclaredType declared = (DeclaredType) type;
        String name = erasureName(declared);
        List<? extends TypeMirror> args = declared.getTypeArguments();
        if (COLLECTIONS.contains(name) || OPTIONAL.equals(name)) {
            return args.size() == 1 ? supportedElement(args.get(0)) : "raw " + name;
        }
        if (!MAP.equals(name)) return supportedElement(type);
        if (args.size() != 2 || !"java.lang.String".equals(erasureName(args.get(0)))) {
            return "a map whose keys are not String";
        }
        return supportedElement(args.get(1));
    }

    /** A value a container holds, or a single value: a leaf, an enum, or a type that gets a binding. */
    private String supportedElement(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) return TYPE + type + " inside a container";
        DeclaredType declared = (DeclaredType) type;
        String name = erasureName(declared);
        if (LEAVES.contains(name)) return null;
        TypeElement element = (TypeElement) declared.asElement();
        if (element.getKind() == ElementKind.ENUM) return enumReason(element, name);
        if (COLLECTIONS.contains(name) || OPTIONAL.equals(name) || MAP.equals(name)) return "nested container " + type;
        boolean bindable = element.getKind() == ElementKind.RECORD || element.getKind() == ElementKind.CLASS;
        if (!bindable || name.startsWith("java.") || name.startsWith("jakarta.")) return TYPE + name;
        pending.add(element);
        return null;   // provisionally: dropModelsReferencingSkipped checks it got a binding
    }

    /** Why the generated code cannot map an enum, or {@code null}: it writes and reads the constants' names. */
    private static String enumReason(TypeElement element, String name) {
        if (element.getModifiers().contains(Modifier.PRIVATE)) return "private enum " + name;
        boolean customised = element.getEnclosedElements().stream().flatMap(c -> c.getAnnotationMirrors().stream())
                .anyMatch(a -> nameOf(a).startsWith(JSONB_ANNOTATIONS));
        return customised ? "enum " + name + " customises its constants" : null;
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
            String reason = unreachable(p, model.packageName());
            if (reason == null) reason = unbound(p);
            if (reason != null) return QUOTED_PROPERTY + p.jsonName() + "\" holds " + reason;
        }
        return null;
    }

    /** A type of the property the binding's package cannot name, or {@code null}. */
    private static String unreachable(Property p, String pkg) {
        return declaredTypes(p.type()).stream().filter(used -> !accessibleFrom(used, pkg)).findFirst()
                .map(used -> used.getQualifiedName() + ", which the binding, in package " + pkg + ", cannot reach")
                .orElse(null);
    }

    /** A type of the property that has no binding, or {@code null}. */
    private String unbound(Property p) {
        return referencedTypes(p.type()).stream()
                .filter(used -> !models.containsKey(used) && !staticBound.containsKey(used)).findFirst()
                .map(used -> used.getQualifiedName() + ", which keeps the runtime mapping").orElse(null);
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

    private ExecutableElement canonicalConstructor(TypeElement recordType) {
        List<TypeMirror> types = recordType.getRecordComponents().stream().map(Element::asType)
                .map(t -> env.getTypeUtils().erasure(t)).toList();
        for (ExecutableElement c : ElementFilter.constructorsIn(recordType.getEnclosedElements())) {
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

    /** The {@code @JsonbProperty} name the members set (the last non-empty one), else the property's name. */
    private static String jsonName(List<Element> members, String fallback) {
        return members.stream().flatMap(m -> m.getAnnotationMirrors().stream())
                .filter(a -> JSONB_PROPERTY.equals(nameOf(a)))
                .flatMap(a -> a.getElementValues().entrySet().stream())
                .filter(e -> e.getKey().getSimpleName().contentEquals("value"))
                .map(e -> (String) e.getValue().getValue())
                .filter(v -> !v.isEmpty())
                .reduce((first, second) -> second)
                .orElse(fallback);
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
