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
    /** Why a type of another compilation (a dependency's, say) gets no binding here. */
    static final String NOT_COMPILED = "not compiled with the resources (a dependency's type, or an incremental build)";
    private static final String HTTP_METHOD = "jakarta.ws.rs.HttpMethod";
    private static final String JSONB_ANNOTATIONS = "jakarta.json.bind.annotation.";
    private static final String JSONB_PROPERTY = JSONB_ANNOTATIONS + "JsonbProperty";
    private static final String JSONB_TRANSIENT = JSONB_ANNOTATIONS + "JsonbTransient";
    private static final String JSONB_NILLABLE = JSONB_ANNOTATIONS + "JsonbNillable";
    private static final String JSONB_DATE_FORMAT = JSONB_ANNOTATIONS + "JsonbDateFormat";
    private static final String JSONB_NUMBER_FORMAT = JSONB_ANNOTATIONS + "JsonbNumberFormat";
    private static final String JSONB_TYPE_ADAPTER = JSONB_ANNOTATIONS + "JsonbTypeAdapter";
    private static final String JSONB_TYPE_INFO = JSONB_ANNOTATIONS + "JsonbTypeInfo";
    private static final String JSONB_ADAPTER = "jakarta.json.bind.adapter.JsonbAdapter";
    private static final String JSONB_TYPE_SERIALIZER = JSONB_ANNOTATIONS + "JsonbTypeSerializer";
    private static final String JSONB_TYPE_DESERIALIZER = JSONB_ANNOTATIONS + "JsonbTypeDeserializer";
    private static final String JSONB_SERIALIZER = "jakarta.json.bind.serializer.JsonbSerializer";
    private static final String JSONB_DESERIALIZER = "jakarta.json.bind.serializer.JsonbDeserializer";
    /** The JSON-B annotations the generated code implements on a property. */
    private static final Set<String> PROPERTY_ANNOTATIONS = Set.of(JSONB_PROPERTY, JSONB_TRANSIENT, JSONB_NILLABLE,
            JSONB_DATE_FORMAT, JSONB_NUMBER_FORMAT, JSONB_TYPE_ADAPTER, JSONB_TYPE_SERIALIZER, JSONB_TYPE_DESERIALIZER);
    /** The JSON-B annotations the generated code implements on a type or a package: they apply to its properties. */
    private static final Set<String> TYPE_ANNOTATIONS = Set.of(JSONB_NILLABLE, JSONB_DATE_FORMAT, JSONB_NUMBER_FORMAT);
    /** The types {@code @JsonbDateFormat} formats (JSON-B 3.0 §3.5; durations and periods stay ISO 8601). */
    private static final Set<String> FORMATTED_DATES = Set.of("java.time.Instant", "java.time.LocalDate",
            "java.time.LocalTime", "java.time.LocalDateTime", "java.time.OffsetDateTime", "java.time.OffsetTime",
            "java.time.ZonedDateTime");
    /** The types {@code @JsonbNumberFormat} formats, primitives aside. */
    private static final Set<String> FORMATTED_NUMBERS = Set.of("java.lang.Integer", "java.lang.Long",
            "java.lang.Double", "java.lang.Float", "java.lang.Short", "java.lang.Byte", "java.math.BigDecimal",
            "java.math.BigInteger");
    private static final String JSONB_STATIC = "io.vidocq.champollion.jsonb.spi.JsonbStatic";
    private static final String JAVA_LANG_OBJECT = "java.lang.Object";
    private static final String PROPERTY = "property ";
    private static final String QUOTED_PROPERTY = "property \"";
    private static final String TYPE = "type ";
    /** The element of an annotation that holds its main value. */
    private static final String VALUE = "value";
    private static final String CREATOR_PARAMETER = "@JsonbCreator parameter ";
    private static final String COMPONENT = "component ";

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

    /** A {@code @JsonbDateFormat} ({@code date}) or {@code @JsonbNumberFormat} that applies to a property. */
    record Format(boolean date, String pattern, String locale) {}

    /**
     * A {@code @JsonbTypeAdapter} that applies to a property: the binding calls {@code className}'s
     * {@code adaptToJson}/{@code adaptFromJson} and maps the {@code adapted} type.
     */
    record Adapter(String className, TypeMirror adapted) {}

    /**
     * How a property's value is mapped in one direction: through an adapter, a {@code JsonbSerializer} or
     * {@code JsonbDeserializer} ({@code custom}, the class created once with {@code new}), a format, or as its type.
     */
    record Mapping(Adapter adapter, Format format, String custom) {

        static final Mapping PLAIN = new Mapping(null, null, null);

        /**
         * The type the binding maps: the adapted type, or the property's; {@code null} when a serializer or a
         * deserializer maps the value itself.
         */
        TypeMirror jsonType(TypeMirror propertyType) {
            if (custom != null) return null;
            return adapter != null ? adapter.adapted() : propertyType;
        }
    }

    /** How a property is reached on an instance: the access and the member's name ({@code getX()}, {@code x}…). */
    record Member(Access access, String name) {}

    /**
     * One JSON property of a modelled type; its mapping may differ on write ({@code out}) and on read ({@code in}),
     * where the runtime looks at the property's members in another order.
     */
    record Property(String jsonName, TypeMirror type, Member source, Member target, boolean nillable, Mapping out,
                    Mapping in) {

        Access read() {
            return source.access();
        }

        String readMember() {
            return source.name();
        }

        Access write() {
            return target.access();
        }

        String writeMember() {
            return target.name();
        }

        /** The types the binding maps for this property, on write and on read. */
        List<TypeMirror> jsonTypes() {
            return java.util.stream.Stream.of(out.jsonType(type), in.jsonType(type)).filter(java.util.Objects::nonNull)
                    .toList();
        }
    }

    /** A {@code @JsonbCreator} parameter: the JSON name its {@code @JsonbProperty} gives, and its type. */
    record Param(String jsonName, TypeMirror type) {}

    /**
     * The {@code @JsonbCreator} of a class: {@code call} ({@code new T} or {@code T.factory}) takes the parameters;
     * the binding sets the other properties read afterwards, as the runtime does.
     */
    record Creator(String call, List<Param> params) {

        boolean takes(String jsonName) {
            return params.stream().anyMatch(p -> p.jsonName().equals(jsonName));
        }
    }

    /** A {@code @JsonbSubtype} of a polymorphic base: its alias and its record. */
    record Variant(String alias, TypeElement type) {}

    /**
     * The {@code @JsonbTypeInfo} a model takes part in. For the base, {@code variants} lists its subtypes and the
     * binding dispatches to theirs; for a subtype, {@code alias} is the discriminator its binding writes and checks.
     */
    record Polymorphism(String key, String alias, List<Variant> variants) {

        boolean isBase() {
            return alias == null;
        }
    }

    /**
     * A type a binding is generated for; {@code creator} is {@code null} when the binding calls {@code new T()},
     * {@code polymorphism} when the type has no {@code @JsonbTypeInfo}.
     */
    record Model(TypeElement type, boolean isRecord, boolean subclassable, List<Property> properties,
                 Creator creator, Polymorphism polymorphism) {

        String packageName() {
            return packageOf(type);
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
    /** The {@code @JsonbCreator} of the classes {@link #classShape} accepted. */
    private final Map<TypeElement, Creator> creators = new LinkedHashMap<>();
    /** Records annotated {@code @JsonbStatic}: they have their own binding, which the generated code calls. */
    private final Map<TypeElement, String> staticBound = new LinkedHashMap<>();
    private final Deque<TypeElement> pending = new ArrayDeque<>();
    private final Set<TypeElement> responseEntities = new java.util.LinkedHashSet<>();
    private boolean responseEntitiesRead;

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
        reachResponseEntities();
        return settle();
    }

    /**
     * Queues the entities the code of the compilation gives a JAX-RS {@code Response} ({@link ResponseEntities}), and
     * keeps the application types they name: JAX-RS hands them to JSON-B at run time, which no signature shows.
     */
    private void reachResponseEntities() {
        java.util.Optional<List<TypeMirror>> found;
        try {
            found = ResponseEntities.find(env, compiled);
        } catch (LinkageError _) {   // no jdk.compiler: the entities stay unknown
            found = java.util.Optional.empty();
        }
        responseEntitiesRead = found.isPresent();
        for (TypeMirror entity : found.orElse(List.of())) {
            reach(entity);
            referencedTypes(entity).stream().filter(t -> !isPlatform(t.getQualifiedName().toString()))
                    .forEach(responseEntities::add);
        }
    }

    private static boolean isPlatform(String name) {
        return name.startsWith("java.") || name.startsWith("jakarta.");
    }

    /** The application types the code gives a {@code Response} as its entity, or as a type an entity holds. */
    Set<TypeElement> responseEntities() {
        return responseEntities;
    }

    /** Whether the method bodies could be read for {@link #responseEntities}. */
    boolean responseEntitiesRead() {
        return responseEntitiesRead;
    }

    /**
     * Collects {@code types} and the types they reach, and models those it can: for the types of a dependency that
     * the resources of an application reach (their names come from its {@link #NOT_COMPILED} notes).
     */
    RestBindings collectTypes(Collection<TypeElement> types) {
        types.forEach(type -> reach(type.asType()));
        return settle();
    }

    private RestBindings settle() {
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
        if (!isBindable(element)) return;
        if (isPlatform(name)) return;
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
            // Its binding is generated where it is compiled, with the types its properties hold: list those too.
            reachMembers(type);
            return NOT_COMPILED;
        }
        if (hasAnnotation(type, JSONB_TYPE_INFO)) return polymorphicBase(type);
        String where = placement(type);
        if (where == null) where = typeAnnotations(type);
        if (where != null) return where;
        List<Property> properties = new ArrayList<>();
        String reason = type.getKind() == ElementKind.RECORD ? recordProperties(type, properties)
                : classProperties(type, properties);
        if (reason == null) reason = unsupportedProperty(properties);
        if (reason != null) return reason;
        return addModel(type, properties);
    }

    /** Records the model of {@code type}, whose {@code properties} are mapped; {@code null}, or why it cannot be. */
    private String addModel(TypeElement type, List<Property> properties) {
        boolean isRecord = type.getKind() == ElementKind.RECORD;
        boolean subclassable = !isRecord && !type.getModifiers().contains(Modifier.FINAL);
        Creator creator = creators.get(type);
        if (creator != null) {
            String unsupported = creatorReason(creator, properties);
            if (unsupported != null) return unsupported;
            creator.params().forEach(param -> reach(param.type()));
        }
        Polymorphism polymorphism = isRecord ? subtypePolymorphism(type, properties) : null;
        if (isRecord && polymorphism == null && lastReason != null) return lastReason;
        models.put(type, new Model(type, isRecord, subclassable, List.copyOf(properties), creator, polymorphism));
        for (Property p : properties) p.jsonTypes().forEach(this::reach);
        // An instance of a subclass reaches the runtime, which uses the subclass's binding when it has one.
        if (subclassable) reachSubclasses(type);
        return null;
    }

    /**
     * Reaches the types the components, public getters and public fields of {@code type} and its superclasses hold,
     * whatever their mapping: those of a type compiled elsewhere are listed with it ({@link #NOT_COMPILED}).
     */
    private void reachMembers(TypeElement type) {
        for (TypeElement level : hierarchy(type)) {
            for (Element member : level.getEnclosedElements()) {
                boolean visible = member.getModifiers().contains(Modifier.PUBLIC)
                        && !member.getModifiers().contains(Modifier.STATIC);
                if (member.getKind() == ElementKind.RECORD_COMPONENT) reach(member.asType());
                else if (visible && member instanceof ExecutableElement m && isGetter(m)) reach(m.getReturnType());
                else if (visible && member.getKind() == ElementKind.FIELD) reach(member.asType());
            }
        }
    }

    /**
     * Models the polymorphic base {@code type}, which carries {@code @JsonbTypeInfo}: a sealed interface whose permitted
     * subtypes are records, each listed by a {@code @JsonbSubtype}, so the binding knows every class a value can have.
     * {@code null}, or why it keeps the runtime.
     */
    private String polymorphicBase(TypeElement type) {
        if (type.getKind() != ElementKind.INTERFACE || !type.getModifiers().contains(Modifier.SEALED)) {
            return "@JsonbTypeInfo on a type that is not a sealed interface: its subtypes are not all known";
        }
        if (!typeInfoChain(type).equals(List.of(type))) return "@JsonbTypeInfo on several levels";
        AnnotationMirror info = annotationOf(type, JSONB_TYPE_INFO);
        String key = "@type";
        List<Variant> variants = new ArrayList<>();
        for (var e : env.getElementUtils().getElementValuesWithDefaults(info).entrySet()) {
            String name = e.getKey().getSimpleName().toString();
            if (name.equals("key")) key = (String) e.getValue().getValue();
            if (name.equals(VALUE)) variants = variants(e.getValue());
        }
        Set<TypeElement> listed = new java.util.HashSet<>();
        for (Variant v : variants) listed.add(v.type());
        for (TypeMirror permitted : type.getPermittedSubclasses()) {
            TypeElement sub = (TypeElement) ((DeclaredType) permitted).asElement();
            if (sub.getKind() != ElementKind.RECORD) return "permitted subtype " + sub.getQualifiedName() + " is no record";
            if (!listed.remove(sub)) return "permitted subtype " + sub.getQualifiedName() + " has no @JsonbSubtype";
        }
        if (!listed.isEmpty()) return "a @JsonbSubtype that is not a permitted subtype";
        variants.forEach(v -> pending.add(v.type()));
        models.put(type, new Model(type, false, false, List.of(), null, new Polymorphism(key, null, variants)));
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Variant> variants(javax.lang.model.element.AnnotationValue value) {
        List<Variant> variants = new ArrayList<>();
        for (var v : (List<? extends javax.lang.model.element.AnnotationValue>) value.getValue()) {
            AnnotationMirror subtype = (AnnotationMirror) v.getValue();
            String alias = null;
            TypeElement type = null;
            for (var e : subtype.getElementValues().entrySet()) {
                if (e.getKey().getSimpleName().contentEquals("alias")) alias = (String) e.getValue().getValue();
                else type = (TypeElement) ((DeclaredType) e.getValue().getValue()).asElement();
            }
            variants.add(new Variant(alias, type));
        }
        return variants;
    }

    /**
     * The {@code @JsonbTypeInfo} the record {@code type} takes part in through the interfaces it implements, as the
     * runtime finds it: {@code null} when none, or when its binding cannot honour it ({@link #lastReason} then says
     * why; it is {@code null} otherwise).
     */
    private Polymorphism subtypePolymorphism(TypeElement type, List<Property> properties) {
        lastReason = null;
        List<TypeElement> chain = typeInfoChain(type);
        if (chain.isEmpty()) return null;
        if (chain.size() > 1) return refuse("@JsonbTypeInfo on several levels of its interfaces");
        TypeElement bearer = chain.getFirst();
        AnnotationMirror info = annotationOf(bearer, JSONB_TYPE_INFO);
        String key = "@type";
        List<Variant> variants = List.of();
        for (var e : env.getElementUtils().getElementValuesWithDefaults(info).entrySet()) {
            String name = e.getKey().getSimpleName().toString();
            if (name.equals("key")) key = (String) e.getValue().getValue();
            if (name.equals(VALUE)) variants = variants(e.getValue());
        }
        Variant own = variants.stream().filter(v -> env.getTypeUtils().isAssignable(type.asType(), v.type().asType()))
                .findFirst().orElse(null);
        if (own == null || own.type() != type) {
            return refuse("not listed by its own @JsonbSubtype in " + bearer.getQualifiedName());
        }
        String discriminator = key;
        if (properties.stream().anyMatch(p -> p.jsonName().equals(discriminator))) {
            return refuse("a property is named like the @JsonbTypeInfo key \"" + discriminator + "\"");
        }
        return new Polymorphism(key, own.alias(), List.of());
    }

    private Polymorphism refuse(String why) {
        lastReason = why;
        return null;
    }

    /** The types of {@code type}'s supertypes, itself included, that carry {@code @JsonbTypeInfo}: the runtime's chain. */
    private List<TypeElement> typeInfoChain(TypeElement type) {
        List<TypeElement> chain = new ArrayList<>();
        Deque<TypeElement> todo = new ArrayDeque<>(List.of(type));
        Set<TypeElement> seen = new java.util.HashSet<>();
        while (!todo.isEmpty()) {
            TypeElement t = todo.poll();
            if (!seen.add(t)) continue;
            if (hasAnnotation(t, JSONB_TYPE_INFO)) chain.add(t);
            for (TypeMirror i : t.getInterfaces()) todo.add((TypeElement) ((DeclaredType) i).asElement());
            TypeElement parent = superclassOf(t);
            if (parent != null) todo.add(parent);
        }
        return chain;
    }

    /**
     * A JSON-B annotation on the type, a superclass or one of their packages that only the runtime implements;
     * {@code null} when none. Those the generated code implements apply to the properties: see {@link #format} and
     * {@link #nillable}.
     */
    private String typeAnnotations(TypeElement type) {
        if (type.getKind() == ElementKind.CLASS && !typeInfoChain(type).isEmpty()) {
            return "it implements an interface carrying @JsonbTypeInfo, which only the runtime implements for a class";
        }
        for (TypeElement level : hierarchy(type)) {
            String onType = unsupportedAnnotation(level.getAnnotationMirrors());
            if (onType != null) return (level == type ? "the class" : "superclass " + level.getQualifiedName())
                    + " carries " + onType;
            PackageElement pkg = env.getElementUtils().getPackageOf(level);
            String onPackage = unsupportedAnnotation(pkg.getAnnotationMirrors());
            if (onPackage != null) return "package " + pkg.getQualifiedName() + " carries " + onPackage;
        }
        return null;
    }

    private static String unsupportedAnnotation(List<? extends AnnotationMirror> annotations) {
        return annotations.stream().filter(a -> nameOf(a).startsWith(JSONB_ANNOTATIONS))
                .filter(a -> !TYPE_ANNOTATIONS.contains(nameOf(a))).findFirst()
                .map(a -> "@" + simpleNameOf(a) + ", a customisation only the runtime implements").orElse(null);
    }

    /** Two properties with one JSON name, or a property type the generated code cannot map; {@code null} when none. */
    private String unsupportedProperty(List<Property> properties) {
        Set<String> names = new java.util.HashSet<>();
        for (Property p : properties) {
            if (!names.add(p.jsonName())) return "two properties have the JSON name \"" + p.jsonName() + "\"";
            for (TypeMirror t : p.jsonTypes()) {
                String unsupported = supported(t);
                if (unsupported != null) return QUOTED_PROPERTY + p.jsonName() + "\": " + unsupported;
            }
        }
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
            String reason = componentProperty(type, component, properties);
            if (reason != null) return COMPONENT + component.getSimpleName() + ": " + reason;
        }
        return null;
    }

    /** Adds the property of {@code component}, unless it is transient; {@code null}, or why it cannot be mapped. */
    private String componentProperty(TypeElement type, javax.lang.model.element.RecordComponentElement component,
                                     List<Property> properties) {
        List<Element> members = new ArrayList<>(List.of(component));
        if (component.getAccessor() != null) members.add(component.getAccessor());
        String annotations = jsonbAnnotations(members);
        if (annotations != null) return annotations;
        String name = component.getSimpleName().toString();
        // A transient component is not a JSON property: the emitter passes its default to the constructor.
        if (isTransient(members)) return null;
        if (hasAnnotation(members, JSONB_TYPE_SERIALIZER) || hasAnnotation(members, JSONB_TYPE_DESERIALIZER)) {
            return "a serializer or deserializer on a record component, which the runtime does not call";
        }
        // The runtime looks at the accessor, then at the field, where the component's annotations land.
        Element field = ElementFilter.fieldsIn(type.getEnclosedElements()).stream()
                .filter(f -> f.getSimpleName().contentEquals(name)).<Element>map(f -> f).findFirst()
                .orElse(component);
        List<Element> lookup = present(component.getAccessor(), field);
        // The runtime takes a component's adapter from its accessor only.
        Mapping mapping = mapping(type, component.asType(), lookup, present(component.getAccessor()), null);
        if (mapping == null) return lastReason;
        properties.add(new Property(jsonName(members, name), component.asType(), new Member(Access.METHOD,
                name + "()"), new Member(Access.COMPONENT, name), nillable(type, lookup), mapping, mapping));
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
            String reason = levelProperties(type, level, own);
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
    private String levelProperties(TypeElement owner, TypeElement level, List<Property> own) {
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
            String reason = classProperty(owner, m, own);
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
    private String classShape(TypeElement type) {
        var constructors = ElementFilter.constructorsIn(type.getEnclosedElements());
        List<ExecutableElement> annotated = new ArrayList<>(constructors.stream()
                .filter(RestBindings::hasJsonbCreator).toList());
        ElementFilter.methodsIn(type.getEnclosedElements()).stream()
                .filter(m -> m.getModifiers().contains(Modifier.STATIC) && hasJsonbCreator(m)).forEach(annotated::add);
        if (annotated.size() > 1) return "several @JsonbCreator";
        if (annotated.size() == 1) return creator(type, annotated.getFirst());
        boolean noArg = constructors.stream()
                .anyMatch(c -> c.getParameters().isEmpty() && !c.getModifiers().contains(Modifier.PRIVATE));
        return noArg || constructors.isEmpty() ? null : "no accessible no-argument constructor";
    }

    /** Records the {@code @JsonbCreator} {@code e} of {@code type}; {@code null}, or why the binding cannot call it. */
    private String creator(TypeElement type, ExecutableElement e) {
        if (e.getModifiers().contains(Modifier.PRIVATE)) return "a private @JsonbCreator";
        boolean factory = e.getKind() == ElementKind.METHOD;
        if (factory && !env.getTypeUtils().isAssignable(e.getReturnType(), type.asType())) {
            return "a @JsonbCreator factory that does not return " + type.getQualifiedName();
        }
        List<Param> params = new ArrayList<>();
        for (VariableElement parameter : e.getParameters()) {
            String name = jsonName(List.of(parameter), "");
            if (name.isEmpty()) {
                return CREATOR_PARAMETER + parameter.getSimpleName() + " has no @JsonbProperty name: its"
                        + " JSON name would depend on whether the class is compiled with -parameters";
            }
            boolean customised = parameter.getAnnotationMirrors().stream().map(RestBindings::nameOf)
                    .anyMatch(n -> n.startsWith(JSONB_ANNOTATIONS) && !n.equals(JSONB_PROPERTY));
            if (customised) return CREATOR_PARAMETER + parameter.getSimpleName() + " is customised";
            params.add(new Param(name, parameter.asType()));
        }
        String call = factory ? type.getQualifiedName() + "." + e.getSimpleName() : "new " + type.getQualifiedName();
        creators.put(type, new Creator(call, List.copyOf(params)));
        return null;
    }

    /**
     * Why the binding cannot read a type through its {@code creator}; {@code null} when it can. The runtime reads
     * the properties the creator does not take with their plain type: a format or an adapter there keeps the runtime.
     */
    private String creatorReason(Creator creator, List<Property> properties) {
        Set<String> names = new java.util.HashSet<>();
        for (Param param : creator.params()) {
            if (!names.add(param.jsonName())) return "two @JsonbCreator parameters named \"" + param.jsonName() + "\"";
            String unsupported = supported(param.type());
            if (unsupported != null) return CREATOR_PARAMETER + "\"" + param.jsonName() + "\": " + unsupported;
        }
        return properties.stream()
                .filter(p -> p.write() != Access.NONE && !creator.takes(p.jsonName()) && p.in() != Mapping.PLAIN)
                .findFirst().map(p -> QUOTED_PROPERTY + p.jsonName() + "\" is customised, and set after the"
                        + " @JsonbCreator: the runtime reads it without its customisation")
                .orElse(null);
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
    private String classProperty(TypeElement owner, Members m, List<Property> properties) {
        boolean transientField = m.field() != null && m.field().getModifiers().contains(Modifier.TRANSIENT);
        if (transientField && (m.getter() != null || m.setter() != null || m.publicField())) {
            return "field " + m.name() + " is transient; JSON-B leaves its meaning to the implementation";
        }
        if (m.read() == Access.NONE && m.write() == Access.NONE) return null;   // a private field: no JSON-B property
        TypeMirror t = m.type();
        if (!sameTypes(t, m)) return PROPERTY + m.name() + " has different types on its field and accessors";
        List<Element> members = m.all();
        String annotations = memberAnnotations(owner, m);
        if (annotations != null) return PROPERTY + m.name() + ": " + annotations;
        if (isTransient(members)) return transientReason(m);
        // On write, the runtime looks at the getter then the field; on read, at the setter, the getter, the field.
        List<Element> writeLookup = present(m.getter(), m.field());
        List<Element> readLookup = present(m.setter(), m.getter(), m.field());
        Mapping out = mapping(owner, t, writeLookup, writeLookup, JSONB_TYPE_SERIALIZER);
        // An adapter is looked for on the setter and the field, then on the getter and the field.
        Mapping in = out == null ? null
                : mapping(owner, t, readLookup, present(m.setter(), m.field(), m.getter()), JSONB_TYPE_DESERIALIZER);
        if (in == null) return PROPERTY + m.name() + ": " + lastReason;
        properties.add(new Property(jsonName(members, m.name()), t, new Member(m.read(), m.readMember()),
                new Member(m.write(), m.writeMember()), nillable(owner, writeLookup), out, in));
        return null;
    }

    /**
     * A JSON-B annotation on an interface method {@code accessor} implements, which the runtime would apply; the
     * generated code does not look there. {@code null} when none.
     */
    private String interfaceAnnotations(TypeElement owner, ExecutableElement accessor) {
        if (accessor == null) return null;
        Deque<TypeMirror> todo = new ArrayDeque<>();
        for (TypeElement level : hierarchy(owner)) todo.addAll(level.getInterfaces());
        while (!todo.isEmpty()) {
            TypeElement i = (TypeElement) ((DeclaredType) todo.poll()).asElement();
            todo.addAll(i.getInterfaces());
            for (ExecutableElement im : ElementFilter.methodsIn(i.getEnclosedElements())) {
                boolean annotated = im.getAnnotationMirrors().stream()
                        .anyMatch(a -> nameOf(a).startsWith(JSONB_ANNOTATIONS));
                if (annotated && env.getElementUtils().overrides(accessor, im, owner)) {
                    return "the interface method " + i.getQualifiedName() + "." + im.getSimpleName()
                            + "() it implements carries JSON-B annotations";
                }
            }
        }
        return null;
    }

    private static List<Element> present(Element... members) {
        return java.util.Arrays.stream(members).filter(java.util.Objects::nonNull).toList();
    }

    /** Why the last {@link #mapping} or {@link #adapter} call returned {@code null}. */
    private String lastReason;

    /**
     * How a property of {@code type} is mapped in one direction, in the runtime's order: the adapter on the first of
     * {@code customLookup} carrying one, else the {@code customAnnotation} ({@code @JsonbTypeSerializer} on write,
     * {@code @JsonbTypeDeserializer} on read; {@code null} for none) on the first carrying it, else the format that
     * applies. {@code null} when what applies cannot be called from generated code; {@link #lastReason} says why.
     */
    private Mapping mapping(TypeElement owner, TypeMirror type, List<Element> formatLookup,
                            List<Element> customLookup, String customAnnotation) {
        AnnotationMirror annotation = first(customLookup, JSONB_TYPE_ADAPTER);
        if (annotation != null) {
            Adapter adapter = adapter(annotation, type, owner);
            return adapter == null ? null : new Mapping(adapter, null, null);
        }
        annotation = customAnnotation == null ? null : first(customLookup, customAnnotation);
        if (annotation != null) {
            String custom = custom(annotation, type, owner);
            return custom == null ? null : new Mapping(null, null, custom);
        }
        Format format = format(owner, type, formatLookup);
        return format == null ? Mapping.PLAIN : new Mapping(null, format, null);
    }

    /** The {@code annotation} on the first of {@code members} carrying it, or {@code null}. */
    private static AnnotationMirror first(List<Element> members, String annotation) {
        return members.stream().map(m -> annotationOf(m, annotation)).filter(java.util.Objects::nonNull).findFirst()
                .orElse(null);
    }

    /**
     * The {@code JsonbSerializer} or {@code JsonbDeserializer} {@code annotation} names, when the binding can create it
     * with {@code new} and call it on a {@code propertyType}; else {@code null}, and {@link #lastReason} says why.
     */
    private String custom(AnnotationMirror annotation, TypeMirror propertyType, TypeElement owner) {
        TypeElement custom = (TypeElement) ((DeclaredType) annotationValue(annotation)).asElement();
        String name = custom.getQualifiedName().toString();
        boolean serializer = JSONB_TYPE_SERIALIZER.equals(nameOf(annotation));
        lastReason = "@" + simpleNameOf(annotation) + "(" + name + ".class): ";
        if (propertyType.getKind() != TypeKind.DECLARED || !((DeclaredType) propertyType).getTypeArguments().isEmpty()) {
            return fail("a primitive, array or generic property, whose type the runtime hands it");
        }
        String unusable = instantiable(custom, packageOf(owner));
        if (unusable != null) return fail(unusable);
        TypeMirror handled = typeArgument(custom, serializer ? JSONB_SERIALIZER : JSONB_DESERIALIZER);
        if (handled == null) return fail("its type argument is not a concrete type");
        boolean callable = serializer ? env.getTypeUtils().isAssignable(propertyType, handled)
                : env.getTypeUtils().isAssignable(handled, propertyType);
        return callable ? name : fail("it handles " + handled + ", not " + propertyType);
    }

    /** The type argument of the {@code generic} interface {@code type} implements, or {@code null}. */
    private static TypeMirror typeArgument(TypeElement type, String generic) {
        for (TypeElement c = type; c != null; c = superclassOf(c)) {
            for (TypeMirror i : c.getInterfaces()) {
                DeclaredType d = (DeclaredType) i;
                if (generic.equals(erasureName(d)) && d.getTypeArguments().size() == 1
                        && isConcrete(d.getTypeArguments().getFirst())) {
                    return d.getTypeArguments().getFirst();
                }
            }
        }
        return null;
    }

    /**
     * The adapter {@code annotation} names, when the binding of a type of {@code owner}'s package can create it with
     * {@code new} and call it on {@code propertyType}; else {@code null}, and {@link #lastReason} says why.
     */
    private Adapter adapter(AnnotationMirror annotation, TypeMirror propertyType, TypeElement owner) {
        TypeElement adapter = (TypeElement) ((DeclaredType) annotationValue(annotation)).asElement();
        String name = adapter.getQualifiedName().toString();
        lastReason = "@JsonbTypeAdapter(" + name + ".class): ";
        if (propertyType.getKind().isPrimitive()) return fail("a primitive property");
        if (OPTIONAL.equals(erasureName(propertyType))) return fail("an Optional property");
        String unusable = instantiable(adapter, packageOf(owner));
        if (unusable != null) return fail(unusable);
        List<? extends TypeMirror> args = adapterArguments(adapter);
        if (args.isEmpty()) return fail("its JsonbAdapter type arguments are not concrete types");
        if (!env.getTypeUtils().isSameType(args.get(0), propertyType)) {
            return fail("it adapts " + args.get(0) + ", not " + propertyType);
        }
        String unsupported = supported(args.get(1));
        if (unsupported != null) return fail("its adapted type: " + unsupported);
        return new Adapter(name, args.get(1));
    }

    private <T> T fail(String why) {
        lastReason += why;
        return null;
    }

    /**
     * Why generated code of package {@code pkg} cannot create {@code type} with {@code new}, as the runtime does
     * outside CDI; {@code null} when it can. A CDI bean (scoped, {@code @Singleton}, or with an {@code @Inject}) is
     * created by the container at run time: it keeps the runtime.
     */
    private static String instantiable(TypeElement type, String pkg) {
        if (type.getModifiers().contains(Modifier.ABSTRACT)) return "abstract";
        if (!accessibleFrom(type, pkg)) return "not accessible from package " + pkg;
        if (type.getNestingKind().isNested() && !type.getModifiers().contains(Modifier.STATIC)
                && !type.getKind().isInterface()) {
            return "an inner class";
        }
        boolean samePackage = packageOf(type).equals(pkg);
        var constructors = ElementFilter.constructorsIn(type.getEnclosedElements());
        boolean callable = constructors.stream().anyMatch(c -> c.getParameters().isEmpty()
                && (c.getModifiers().contains(Modifier.PUBLIC) || samePackage && !c.getModifiers().contains(Modifier.PRIVATE)));
        if (!callable) return "no no-argument constructor the binding can call";
        if (isLikelyBean(type)) return "a CDI bean, which the container creates at run time";
        return null;
    }

    /** The runtime's test for a CDI bean: a scope, {@code @Singleton}, or an {@code @Inject} member. */
    private static boolean isLikelyBean(TypeElement type) {
        for (AnnotationMirror a : type.getAnnotationMirrors()) {
            String n = nameOf(a);
            if (n.startsWith("jakarta.enterprise.context.") && n.endsWith("Scoped")
                    || "jakarta.inject.Singleton".equals(n)) {
                return true;
            }
        }
        return type.getEnclosedElements().stream().anyMatch(e -> hasAnnotation(e, "jakarta.inject.Inject"));
    }

    /**
     * The {@code <Original, Adapted>} arguments of the {@code JsonbAdapter} {@code adapter} implements, looked up as
     * the runtime does (its interfaces, then its superclasses'); {@code null} when they are not concrete types.
     */
    private static List<? extends TypeMirror> adapterArguments(TypeElement adapter) {
        for (TypeElement c = adapter; c != null; c = superclassOf(c)) {
            for (TypeMirror i : c.getInterfaces()) {
                DeclaredType d = (DeclaredType) i;
                if (JSONB_ADAPTER.equals(erasureName(d))) {
                    List<? extends TypeMirror> args = d.getTypeArguments();
                    boolean concrete = args.size() == 2 && args.stream().allMatch(RestBindings::isConcrete);
                    return concrete ? args : List.of();
                }
            }
        }
        return List.of();
    }

    /** A type with no type variable nor wildcard in it. */
    private static boolean isConcrete(TypeMirror t) {
        return switch (t.getKind()) {
            case DECLARED -> ((DeclaredType) t).getTypeArguments().stream().allMatch(RestBindings::isConcrete);
            case ARRAY -> isConcrete(((javax.lang.model.type.ArrayType) t).getComponentType());
            default -> t.getKind().isPrimitive();
        };
    }

    private static Object annotationValue(AnnotationMirror a) {
        return a.getElementValues().entrySet().stream()
                .filter(e -> e.getKey().getSimpleName().contentEquals(VALUE))
                .map(e -> e.getValue().getValue()).findFirst().orElseThrow();
    }

    private static String packageOf(Element element) {
        Element e = element;
        while (!(e instanceof PackageElement)) e = e.getEnclosingElement();
        return ((PackageElement) e).getQualifiedName().toString();
    }

    /**
     * The format that applies to a property of {@code owner} whose type takes one: on the first of {@code lookup}
     * carrying it, else on {@code owner} or its closest superclass carrying it, else on the package of one of them —
     * the runtime's order. {@code null} when none applies.
     */
    private Format format(TypeElement owner, TypeMirror type, List<Element> lookup) {
        String name = type.getKind().isPrimitive() ? null : erasureName(type);
        boolean date = name != null && FORMATTED_DATES.contains(name);
        boolean number = name == null ? isFormattedPrimitive(type) : FORMATTED_NUMBERS.contains(name);
        if (!date && !number) return null;
        AnnotationMirror a = applying(owner, lookup, date ? JSONB_DATE_FORMAT : JSONB_NUMBER_FORMAT);
        if (a == null) return null;
        String pattern = "##default";
        String locale = "##default";
        for (var e : env.getElementUtils().getElementValuesWithDefaults(a).entrySet()) {
            String key = e.getKey().getSimpleName().toString();
            if (key.equals(VALUE)) pattern = (String) e.getValue().getValue();
            else if (key.equals("locale")) locale = (String) e.getValue().getValue();
        }
        return new Format(date, pattern, locale);
    }

    private static boolean isFormattedPrimitive(TypeMirror type) {
        return switch (type.getKind()) {
            case INT, LONG, DOUBLE, FLOAT, SHORT, BYTE -> true;
            default -> false;
        };
    }

    /**
     * Whether a null value of the property is written: {@code @JsonbNillable} or {@code @JsonbProperty(nillable =
     * true)} on the first of {@code lookup} carrying one, else {@code @JsonbNillable} on {@code owner}, a
     * superclass or one of their packages; else not.
     */
    private boolean nillable(TypeElement owner, List<Element> lookup) {
        for (Element m : lookup) {
            for (AnnotationMirror a : m.getAnnotationMirrors()) {
                if (JSONB_NILLABLE.equals(nameOf(a))) return booleanValue(a, VALUE);
            }
            for (AnnotationMirror a : m.getAnnotationMirrors()) {
                if (JSONB_PROPERTY.equals(nameOf(a)) && booleanValue(a, "nillable")) return true;
            }
        }
        AnnotationMirror a = applying(owner, List.of(), JSONB_NILLABLE);
        return a != null && booleanValue(a, VALUE);
    }

    private boolean booleanValue(AnnotationMirror a, String element) {
        for (var e : env.getElementUtils().getElementValuesWithDefaults(a).entrySet()) {
            if (e.getKey().getSimpleName().contentEquals(element)) return (Boolean) e.getValue().getValue();
        }
        return false;
    }

    /** The {@code annotation} on the first of {@code lookup}, else up {@code owner}'s classes, else their packages. */
    private AnnotationMirror applying(TypeElement owner, List<Element> lookup, String annotation) {
        for (Element m : lookup) {
            AnnotationMirror a = annotationOf(m, annotation);
            if (a != null) return a;
        }
        List<TypeElement> chain = hierarchy(owner).reversed();
        for (TypeElement level : chain) {
            AnnotationMirror a = annotationOf(level, annotation);
            if (a != null) return a;
        }
        for (TypeElement level : chain) {
            AnnotationMirror a = annotationOf(env.getElementUtils().getPackageOf(level), annotation);
            if (a != null) return a;
        }
        return null;
    }

    private static AnnotationMirror annotationOf(Element e, String annotation) {
        return e.getAnnotationMirrors().stream().filter(a -> annotation.equals(nameOf(a))).findFirst().orElse(null);
    }

    /** The JSON-B annotations of the members of {@code m}, or of the interface methods they implement, not generated. */
    private String memberAnnotations(TypeElement owner, Members m) {
        String annotations = jsonbAnnotations(m.all());
        if (annotations == null) annotations = interfaceAnnotations(owner, m.getter());
        if (annotations == null) annotations = interfaceAnnotations(owner, m.setter());
        return annotations;
    }

    /** {@code null} when {@code m} is not a property ({@code @JsonbTransient} where the runtime honours it), else why. */
    private static String transientReason(Members m) {
        boolean everywhere = m.all().stream().allMatch(e -> hasAnnotation(e, JSONB_TRANSIENT));
        boolean onField = m.field() != null && hasAnnotation(m.field(), JSONB_TRANSIENT);
        return everywhere || onField ? null : PROPERTY + m.name() + " is @JsonbTransient on part of its members only";
    }

    private boolean sameTypes(TypeMirror t, Members m) {
        return (m.getter() == null || same(t, m.getter().getReturnType()))
                && (m.setter() == null || same(t, m.setter().getParameters().get(0).asType()))
                && (m.field() == null || same(t, m.field().asType()));
    }

    /** Whether {@code type} is mapped by the generated code; {@code null} when it is, else why not. */
    private String supported(TypeMirror type) {
        if (type.getKind().isPrimitive()) return null;
        if (type.getKind() == TypeKind.ARRAY) return supportedArray((javax.lang.model.type.ArrayType) type);
        if (type.getKind() != TypeKind.DECLARED) {
            return TYPE + type + " (type variables and wildcards are left to the runtime)";
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

    /** An array of primitives, or of what a container may hold but a generic type (Java creates no such array). */
    private String supportedArray(javax.lang.model.type.ArrayType array) {
        TypeMirror component = array.getComponentType();
        if (component.getKind().isPrimitive()) return null;
        if (component.getKind() == TypeKind.DECLARED && !((DeclaredType) component).getTypeArguments().isEmpty()) {
            return "an array of the generic type " + component;
        }
        return supportedElement(component);
    }

    /** Whether {@code type} is an array of primitives, which {@code JsonbLeaf} maps. */
    static boolean isPrimitiveArray(TypeMirror type) {
        return type.getKind() == TypeKind.ARRAY
                && ((javax.lang.model.type.ArrayType) type).getComponentType().getKind().isPrimitive();
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
        if (!isBindable(element) || isPlatform(name)) return TYPE + name;
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
        String reason = missingVariant(model);
        if (reason == null) reason = missingParameter(model);
        return reason != null ? reason : missingProperty(model);
    }

    /** A {@code @JsonbSubtype} of a polymorphic base without a binding its package reaches, or {@code null}. */
    private String missingVariant(Model model) {
        if (model.polymorphism() == null || !model.polymorphism().isBase()) return null;
        for (Variant v : model.polymorphism().variants()) {
            String reason = missing(List.of(v.type().asType()), List.of(v.type().asType()), model.packageName());
            if (reason != null) return "@JsonbSubtype \"" + v.alias() + "\" is " + reason;
        }
        return null;
    }

    /** A {@code @JsonbCreator} parameter whose type has no binding its package reaches, or {@code null}. */
    private String missingParameter(Model model) {
        if (model.creator() == null) return null;
        for (Param param : model.creator().params()) {
            String reason = missing(List.of(param.type()), List.of(param.type()), model.packageName());
            if (reason != null) return CREATOR_PARAMETER + "\"" + param.jsonName() + "\" holds " + reason;
        }
        return null;
    }

    /** A type of {@code named} the package cannot name, or one of {@code mapped} without a binding; or {@code null}. */
    private String missing(List<TypeMirror> named, List<TypeMirror> mapped, String pkg) {
        String reason = unreachable(named, pkg);
        return reason != null ? reason : unbound(mapped);
    }

    /** A property whose types have no binding the package reaches, or {@code null}. */
    private String missingProperty(Model model) {
        for (Property p : model.properties()) {
            // The binding names the property's type, and maps the types the JSON holds (an adapter's, maybe).
            List<TypeMirror> named = new ArrayList<>(p.jsonTypes());
            named.add(p.type());
            String reason = unreachable(named, model.packageName());
            if (reason == null) reason = unbound(p.jsonTypes());
            if (reason != null) return QUOTED_PROPERTY + p.jsonName() + "\" holds " + reason;
        }
        return null;
    }

    /** A type among {@code types} the binding's package cannot name, or {@code null}. */
    private static String unreachable(List<TypeMirror> types, String pkg) {
        return types.stream().flatMap(t -> declaredTypes(t).stream()).filter(used -> !accessibleFrom(used, pkg))
                .findFirst()
                .map(used -> used.getQualifiedName() + ", which the binding, in package " + pkg + ", cannot reach")
                .orElse(null);
    }

    /** A type among {@code types} that has no binding, or {@code null}. */
    private String unbound(List<TypeMirror> types) {
        return types.stream().flatMap(t -> referencedTypes(t).stream())
                .filter(used -> !models.containsKey(used) && !staticBound.containsKey(used)).findFirst()
                .map(used -> used.getQualifiedName() + ", which keeps the runtime mapping").orElse(null);
    }

    /** Every declared type {@code type} names, itself and its type arguments, recursively. */
    private static List<TypeElement> declaredTypes(TypeMirror type) {
        if (type.getKind() == TypeKind.ARRAY) {
            return declaredTypes(((javax.lang.model.type.ArrayType) type).getComponentType());
        }
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
        if (type.getKind() == TypeKind.ARRAY) {
            return referencedTypes(((javax.lang.model.type.ArrayType) type).getComponentType());
        }
        List<TypeElement> found = new ArrayList<>();
        if (type.getKind() != TypeKind.DECLARED) return found;
        DeclaredType declared = (DeclaredType) type;
        String name = erasureName(declared);
        if (COLLECTIONS.contains(name) || OPTIONAL.equals(name) || MAP.equals(name)) {
            for (TypeMirror argument : declared.getTypeArguments()) found.addAll(referencedTypes(argument));
            return found;
        }
        TypeElement element = (TypeElement) declared.asElement();
        if (isBindable(element) && !LEAVES.contains(name)) found.add(element);
        return found;
    }

    /** A record, a class, or an interface carrying {@code @JsonbTypeInfo}: a type that may get a binding. */
    static boolean isBindable(TypeElement element) {
        return element.getKind() == ElementKind.RECORD || element.getKind() == ElementKind.CLASS
                || element.getKind() == ElementKind.INTERFACE && hasAnnotation(element, JSONB_TYPE_INFO);
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

    /** The JSON-B annotations of {@code members} other than those the generated code implements, or null. */
    private static String jsonbAnnotations(List<Element> members) {
        for (Element m : members) {
            for (AnnotationMirror a : m.getAnnotationMirrors()) {
                String n = nameOf(a);
                if (n.startsWith(JSONB_ANNOTATIONS) && !PROPERTY_ANNOTATIONS.contains(n)) {
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
                .filter(e -> e.getKey().getSimpleName().contentEquals(VALUE))
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
