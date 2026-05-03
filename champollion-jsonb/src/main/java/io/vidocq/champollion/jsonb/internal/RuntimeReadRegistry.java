package io.vidocq.champollion.jsonb.internal;

import jakarta.json.bind.JsonbException;
import jakarta.json.stream.JsonParser;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Registre de {@link BindingReader} symétrique à {@link RuntimeBindingRegistry}.
 *
 * <p>Cache {@link ClassValue} pour les classes simples + map concurrente pour les types
 * paramétrés (clé = {@link Type#getTypeName()}).</p>
 */
final class RuntimeReadRegistry {

    private final String defaultDateFormat;
    private final String binaryDataStrategy;
    private final String propertyNamingStrategy;
    private final jakarta.json.bind.config.PropertyVisibilityStrategy propertyVisibilityStrategy;
    private final java.util.Locale configLocale;
    private final boolean failOnUnknownProperties;

    RuntimeReadRegistry() { this(null, null, null, null, null, false); }

    RuntimeReadRegistry(String defaultDateFormat) { this(defaultDateFormat, null, null, null, null, false); }

    RuntimeReadRegistry(String defaultDateFormat, String binaryDataStrategy) {
        this(defaultDateFormat, binaryDataStrategy, null, null, null, false);
    }

    RuntimeReadRegistry(String defaultDateFormat, String binaryDataStrategy,
                        String propertyNamingStrategy,
                        jakarta.json.bind.config.PropertyVisibilityStrategy propertyVisibilityStrategy) {
        this(defaultDateFormat, binaryDataStrategy, propertyNamingStrategy, propertyVisibilityStrategy, null, false);
    }

    RuntimeReadRegistry(String defaultDateFormat, String binaryDataStrategy,
                        String propertyNamingStrategy,
                        jakarta.json.bind.config.PropertyVisibilityStrategy propertyVisibilityStrategy,
                        java.util.Locale configLocale) {
        this(defaultDateFormat, binaryDataStrategy, propertyNamingStrategy, propertyVisibilityStrategy, configLocale, false);
    }

    RuntimeReadRegistry(String defaultDateFormat, String binaryDataStrategy,
                        String propertyNamingStrategy,
                        jakarta.json.bind.config.PropertyVisibilityStrategy propertyVisibilityStrategy,
                        java.util.Locale configLocale,
                        boolean failOnUnknownProperties) {
        this.defaultDateFormat = defaultDateFormat;
        this.binaryDataStrategy = binaryDataStrategy;
        this.propertyNamingStrategy = propertyNamingStrategy;
        this.propertyVisibilityStrategy = propertyVisibilityStrategy;
        this.configLocale = configLocale;
        this.failOnUnknownProperties = failOnUnknownProperties;
    }

    private final ClassValue<BindingReader> classCache = new ClassValue<>() {
        @Override protected BindingReader computeValue(Class<?> type) { return resolveClass(type); }
    };

    private final java.util.concurrent.ConcurrentHashMap<String, BindingReader> typeCache = new java.util.concurrent.ConcurrentHashMap<>();

    BindingReader readerFor(Type t) {
        if (t instanceof Class<?> c) {
            if (c.isArray()) return arrayReader(c.getComponentType());
            return classCache.get(c);
        }
        if (t instanceof java.lang.reflect.ParameterizedType p) {
            return typeCache.computeIfAbsent(p.getTypeName(), k -> parameterizedReader(p));
        }
        if (t instanceof java.lang.reflect.GenericArrayType ga) {
            Type comp = ga.getGenericComponentType();
            Class<?> rawComp = comp instanceof Class<?> cc ? cc
                    : comp instanceof java.lang.reflect.ParameterizedType pt ? (Class<?>) pt.getRawType()
                    : Object.class;
            return arrayReader(rawComp);
        }
        if (t instanceof java.lang.reflect.TypeVariable<?> || t instanceof java.lang.reflect.WildcardType) {
            return this::dynamicValue;
        }
        return classCache.get((Class<?>) t);
    }

    // ===== Resolution by raw class =====

    private BindingReader resolveClass(Class<?> type) {
        BindingReader b = Builtins.lookup(type);
        if (b != null) return b;
        if (type.isEnum()) return enumReader(type);
        // M4.5 : polymorphisme — si @JsonbTypeInfo, dispatch sur l'alias.
        var info = RuntimeBindingRegistry.findTypeInfo(type);
        if (info != null) return polymorphicReader(info);
        if (type.isRecord()) return resolveRecord(type);
        if (type.isPrimitive()) return classCache.get(box(type));
        if (java.util.Map.class.isAssignableFrom(type)) return mapReader(this::dynamicValue, type);
        if (java.util.Collection.class.isAssignableFrom(type)) return collectionReader(this::dynamicValue, type);
        if (type == Optional.class) return optionalReader(this::dynamicValue);
        if (type == Object.class) return this::dynamicValue;
        if (type == Number.class) return parser -> {
            JsonParser.Event ev = parser.next();
            return switch (ev) {
                case VALUE_NULL -> null;
                case VALUE_NUMBER -> parser.getBigDecimal();
                case VALUE_STRING -> new BigDecimal(parser.getString());
                default -> throw new JsonbException("Cannot deserialize Number from " + ev);
            };
        };
        if (Modifier.isAbstract(type.getModifiers()) && !type.isInterface()) {
            return this::dynamicValue;
        }
        return resolvePojo(type);
    }

    private static Class<?> box(Class<?> p) {
        if (p == int.class) return Integer.class;
        if (p == long.class) return Long.class;
        if (p == double.class) return Double.class;
        if (p == float.class) return Float.class;
        if (p == short.class) return Short.class;
        if (p == byte.class) return Byte.class;
        if (p == char.class) return Character.class;
        if (p == boolean.class) return Boolean.class;
        return Object.class;
    }

    private BindingReader resolveRecord(Class<?> type) {
        RecordComponent[] comps = type.getRecordComponents();
        Class<?>[] paramTypes = new Class<?>[comps.length];
        BindingReader[] readers = new BindingReader[comps.length];
        Map<String, Integer> indexByName = new HashMap<>(comps.length * 2);
        for (int i = 0; i < comps.length; i++) {
            final RecordComponent comp = comps[i];
            paramTypes[i] = comp.getType();
            // M4.4f @JsonbTypeAdapter > M4.4d @JsonbDateFormat > M4.7 JSONB_DATE_FORMAT > runtime.
            readers[i] = customAdapterReader(comp)
                    .or(() -> customDateReader(comp))
                    .or(() -> globalDateReader(comp.getType()))
                    .orElseGet(() -> readerFor(comp.getGenericType()));
            if (isJsonbTransient(comp)) continue;
            indexByName.put(jsonbName(comp), i);
        }
        Constructor<?> ctor;
        try {
            ctor = type.getDeclaredConstructor(paramTypes);
        } catch (NoSuchMethodException e) {
            throw new JsonbException("Canonical record constructor not found for " + type, e);
        }
        try { ctor.setAccessible(true); } catch (Exception ignore) {}
        final Constructor<?> finalCtor = ctor;

        return parser -> readObjectAndConstruct(parser, finalCtor, paramTypes, readers, indexByName);
    }

    private BindingReader resolvePojo(Class<?> type) {
        // M4.4e : si @JsonbCreator est sur un constructor ou une static factory, on l'utilise.
        var creator = findJsonbCreator(type);
        if (creator != null) return resolveCreator(creator);

        RuntimeBindingRegistry.validateTransientCombinations(type);

        // Fallback : ctor sans arg + champs publics.
        Constructor<?> ctor;
        try {
            ctor = type.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            throw new JsonbException("No no-arg constructor for " + type
                    + " (consider adding @JsonbCreator on a constructor or static factory).", e);
        }
        // JSON-B 3.0 §3.7.1 : sans @JsonbCreator, le constructeur doit être public ou protected.
        int cMods = ctor.getModifiers();
        if (!Modifier.isPublic(cMods) && !Modifier.isProtected(cMods)) {
            throw new JsonbException("No accessible no-arg constructor for " + type
                    + " (consider adding @JsonbCreator).");
        }
        try { ctor.setAccessible(true); } catch (Exception ignore) {}
        // Découverte des setters JavaBean (§3.7) + champs publics fallback.
        Map<String, BeanWriter> writersByName = new HashMap<>();

        // 1) Setters publics : setXxx(T) avec un getter correspondant pour vérifier @JsonbTransient.
        // Détection préalable de duplicate names (même logique que côté writer)
        validateNoDuplicateNames(type);

        // Découvrir TOUS les setters (incluant non-public) pour identifier les hidden properties.
        Map<String, Method> setterByProp = new java.util.LinkedHashMap<>();
        var hiddenProps = new java.util.HashSet<String>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (m.isBridge() || m.isSynthetic()) continue;
                if (m.getReturnType() != void.class) continue;
                String prop = RuntimeBindingRegistry.beanSetterOf(m);
                if (prop == null) continue;
                if (m.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class)) {
                    hiddenProps.add(prop);
                    continue;
                }
                if (!Modifier.isPublic(m.getModifiers())) {
                    hiddenProps.add(prop);
                    continue;
                }
                // Skip si field underlying static/transient/JsonbTransient
                Field underlying = findFieldByName(type, prop);
                if (underlying != null) {
                    int fMods = underlying.getModifiers();
                    if (Modifier.isStatic(fMods) || Modifier.isTransient(fMods)
                            || underlying.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class)) {
                        hiddenProps.add(prop);
                        continue;
                    }
                }
                Method existing = setterByProp.get(prop);
                if (existing == null) {
                    setterByProp.put(prop, m);
                } else {
                    Class<?> ep = existing.getParameterTypes()[0];
                    Class<?> np = m.getParameterTypes()[0];
                    if (ep.isAssignableFrom(np) && ep != np) setterByProp.put(prop, m);
                }
            }
        }
        setterByProp.keySet().removeAll(hiddenProps);
        for (var e : setterByProp.entrySet()) {
            String prop = e.getKey();
            Method m = e.getValue();
            Method getter = findGetter(type, prop);
            if (getter != null && getter.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class)) continue;
            try { m.setAccessible(true); } catch (Exception ignore) {}
            String name = methodJsonbName(m, getter, prop);
            Class<?> paramType = m.getParameterTypes()[0];
            BindingReader reader;
            BindingReader dateR = dateReaderFor(paramType, m, getter, type);
            if (dateR != null) {
                reader = dateR;
            } else {
                BindingReader numR = numberReaderFor(paramType, m, getter, type);
                reader = numR != null ? numR : readerFor(m.getGenericParameterTypes()[0]);
            }
            writersByName.put(name, new MethodSetter(m, reader));
        }

        // 2) Champs publics non couverts par un setter et non masqués.
        for (Field f : type.getFields()) {
            int mods = f.getModifiers();
            if (Modifier.isStatic(mods) || Modifier.isTransient(mods)) continue;
            if (Modifier.isFinal(mods)) continue;
            if (f.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class)) continue;
            if (hiddenProps.contains(f.getName())) continue;
            String name = jsonbName(f);
            if (writersByName.containsKey(name)) continue;
            try { f.setAccessible(true); } catch (Exception ignore) {}
            BindingReader reader = readerFor(f.getGenericType());
            writersByName.put(name, new FieldSetter(f, reader));
        }

        // Application de PropertyVisibilityStrategy (config / @JsonbVisibility / package).
        var visibility = effectiveVisibility(type);
        if (visibility != null) {
            var keep = new java.util.LinkedHashMap<String, BeanWriter>();
            for (var entry : writersByName.entrySet()) {
                BeanWriter bw = entry.getValue();
                boolean visible;
                if (bw instanceof MethodSetter ms) {
                    boolean methodVisible = visibility.isVisible(ms.m);
                    String prop = RuntimeBindingRegistry.beanSetterOf(ms.m);
                    Field underlying = prop == null ? null : findFieldByName(type, prop);
                    visible = underlying == null ? methodVisible : (methodVisible || visibility.isVisible(underlying));
                } else if (bw instanceof FieldSetter fs) {
                    visible = visibility.isVisible(fs.f);
                } else {
                    visible = true;
                }
                if (visible) keep.put(entry.getKey(), bw);
            }
            writersByName.clear();
            writersByName.putAll(keep);
        }

        return parser -> readObjectAndApply(parser, ctor, writersByName);
    }

    private jakarta.json.bind.config.PropertyVisibilityStrategy effectiveVisibility(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            var ann = c.getAnnotation(jakarta.json.bind.annotation.JsonbVisibility.class);
            if (ann != null) {
                try { return ann.value().getDeclaredConstructor().newInstance(); }
                catch (Exception e) { throw new JsonbException("Cannot instantiate @JsonbVisibility " + ann.value(), e); }
            }
        }
        var pkg = type.getPackage();
        if (pkg != null) {
            try { Class.forName(pkg.getName() + ".package-info", false, type.getClassLoader()); }
            catch (Throwable ignored) {}
            var pann = pkg.getAnnotation(jakarta.json.bind.annotation.JsonbVisibility.class);
            if (pann != null) {
                try { return pann.value().getDeclaredConstructor().newInstance(); }
                catch (Exception e) { throw new JsonbException("Cannot instantiate package @JsonbVisibility " + pann.value(), e); }
            }
        }
        return propertyVisibilityStrategy;
    }

    /**
     * Détecte si après application de @JsonbProperty / naming strategy, deux properties auront
     * le même nom JSON ; lève JsonbException sinon.
     */
    private void validateNoDuplicateNames(Class<?> type) {
        var seen = new java.util.HashMap<String, String>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                int mods = f.getModifiers();
                if (Modifier.isStatic(mods) || Modifier.isTransient(mods)) continue;
                if (f.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class)) continue;
                String prop = f.getName();
                String name = jsonbName(f);
                String prev = seen.put(name, prop);
                if (prev != null && !prev.equals(prop)) {
                    throw new JsonbException("Duplicate JSON property name '" + name
                            + "' on " + type + " (from '" + prev + "' and '" + prop + "')");
                }
            }
        }
    }

    /**
     * Cherche un reader date pour {@code paramType} en consultant @JsonbDateFormat à
     * plusieurs niveaux (setter, getter, field, type, package, config).
     */
    private BindingReader dateReaderFor(Class<?> paramType, Method setter, Method getter, Class<?> declaringType) {
        if (!RuntimeBindingRegistry.isDateLikeType(paramType)) return null;
        // Priorité : setter > getter > field underlying > type > package > config
        java.lang.reflect.AnnotatedElement member = setter.isAnnotationPresent(jakarta.json.bind.annotation.JsonbDateFormat.class) ? setter : null;
        if (member == null && getter != null && getter.isAnnotationPresent(jakarta.json.bind.annotation.JsonbDateFormat.class)) member = getter;
        if (member == null) {
            String prop = RuntimeBindingRegistry.beanSetterOf(setter);
            if (prop != null) {
                Field f = findFieldByName(declaringType, prop);
                if (f != null && f.isAnnotationPresent(jakarta.json.bind.annotation.JsonbDateFormat.class)) member = f;
            }
        }
        var spec = RuntimeBindingRegistry.findDateFormatSpec(member, declaringType);
        if (spec == null && defaultDateFormat != null) {
            java.util.Locale loc = configLocale != null ? configLocale : java.util.Locale.getDefault();
            spec = new RuntimeBindingRegistry.DateFormatSpec(defaultDateFormat, loc);
        }
        if (spec == null) return null;
        return makeDateReader(paramType, spec);
    }

    private BindingReader makeDateReader(Class<?> rawType, RuntimeBindingRegistry.DateFormatSpec spec) {
        if (java.util.Date.class.isAssignableFrom(rawType)) {
            return p -> {
                var ev = p.next();
                if (ev == JsonParser.Event.VALUE_NULL) return null;
                String s = p.getString();
                if (spec.isDefault()) return java.util.Date.from(parseAsInstant(s));
                try {
                    var sdf = new java.text.SimpleDateFormat(spec.pattern(), spec.locale());
                    sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                    return sdf.parse(s);
                } catch (java.text.ParseException pe) {
                    throw new JsonbException("Date parse error: " + pe.getMessage(), pe);
                }
            };
        }
        if (java.util.Calendar.class.isAssignableFrom(rawType)) {
            return p -> {
                var ev = p.next();
                if (ev == JsonParser.Event.VALUE_NULL) return null;
                String s = p.getString();
                if (spec.isDefault()) return java.util.GregorianCalendar.from(parseAsZonedDateTime(s));
                try {
                    var sdf = new java.text.SimpleDateFormat(spec.pattern(), spec.locale());
                    var d = sdf.parse(s);
                    var cal = new java.util.GregorianCalendar(sdf.getTimeZone());
                    cal.setTime(d);
                    return cal;
                } catch (java.text.ParseException pe) {
                    throw new JsonbException("Date parse error: " + pe.getMessage(), pe);
                }
            };
        }
        // java.time
        return p -> {
            var ev = p.next();
            if (ev == JsonParser.Event.VALUE_NULL) return null;
            String s = p.getString();
            if (spec.isDefault()) {
                if (rawType == java.time.LocalDate.class) return java.time.LocalDate.parse(s);
                if (rawType == java.time.LocalDateTime.class) return java.time.LocalDateTime.parse(s);
                if (rawType == java.time.OffsetDateTime.class) return java.time.OffsetDateTime.parse(s);
                if (rawType == java.time.ZonedDateTime.class) return java.time.ZonedDateTime.parse(s);
                if (rawType == java.time.LocalTime.class) return java.time.LocalTime.parse(s);
                if (rawType == java.time.OffsetTime.class) return java.time.OffsetTime.parse(s);
                if (rawType == Instant.class) return Instant.parse(s);
                if (rawType == java.time.Duration.class) return java.time.Duration.parse(s);
                if (rawType == java.time.Period.class) return java.time.Period.parse(s);
                return s;
            }
            var fmt = java.time.format.DateTimeFormatter.ofPattern(spec.pattern(), spec.locale());
            if (rawType == java.time.LocalDate.class) return java.time.LocalDate.parse(s, fmt);
            if (rawType == java.time.LocalDateTime.class) return java.time.LocalDateTime.parse(s, fmt);
            if (rawType == java.time.OffsetDateTime.class) return java.time.OffsetDateTime.parse(s, fmt);
            if (rawType == java.time.ZonedDateTime.class) return java.time.ZonedDateTime.parse(s, fmt);
            if (rawType == java.time.LocalTime.class) return java.time.LocalTime.parse(s, fmt);
            if (rawType == java.time.OffsetTime.class) return java.time.OffsetTime.parse(s, fmt);
            if (rawType == Instant.class) return java.time.ZonedDateTime.parse(s, fmt).toInstant();
            return s;
        };
    }

    /** Reader numérique custom via @JsonbNumberFormat. */
    private BindingReader numberReaderFor(Class<?> paramType, Method setter, Method getter, Class<?> declaringType) {
        Class<?> boxed = paramType.isPrimitive() ? boxOfPrim(paramType) : paramType;
        if (!Number.class.isAssignableFrom(boxed)) return null;
        java.lang.reflect.AnnotatedElement member = setter.isAnnotationPresent(jakarta.json.bind.annotation.JsonbNumberFormat.class) ? setter : null;
        if (member == null && getter != null && getter.isAnnotationPresent(jakarta.json.bind.annotation.JsonbNumberFormat.class)) member = getter;
        if (member == null) {
            String prop = RuntimeBindingRegistry.beanSetterOf(setter);
            if (prop != null) {
                Field f = findFieldByName(declaringType, prop);
                if (f != null && f.isAnnotationPresent(jakarta.json.bind.annotation.JsonbNumberFormat.class)) member = f;
            }
        }
        // walk class chain + package
        var ann = member == null ? null : member.getAnnotation(jakarta.json.bind.annotation.JsonbNumberFormat.class);
        if (ann == null) {
            for (Class<?> c = declaringType; c != null && c != Object.class && ann == null; c = c.getSuperclass()) {
                ann = c.getAnnotation(jakarta.json.bind.annotation.JsonbNumberFormat.class);
            }
        }
        if (ann == null) {
            for (Class<?> c = declaringType; c != null && c != Object.class && ann == null; c = c.getSuperclass()) {
                var pkg = c.getPackage();
                if (pkg == null) continue;
                ClassLoader cl = c.getClassLoader();
                if (cl == null) cl = ClassLoader.getSystemClassLoader();
                try { Class.forName(pkg.getName() + ".package-info", false, cl); } catch (Throwable ignored) {}
                ann = pkg.getAnnotation(jakarta.json.bind.annotation.JsonbNumberFormat.class);
            }
        }
        if (ann == null) return null;
        String pattern = ann.value();
        java.util.Locale locale = "##default".equals(ann.locale()) ? java.util.Locale.getDefault() : java.util.Locale.forLanguageTag(ann.locale());
        java.text.NumberFormat fmt;
        if ("##default".equals(pattern) || pattern.isEmpty()) {
            fmt = java.text.NumberFormat.getInstance(locale);
        } else {
            fmt = new java.text.DecimalFormat(pattern, new java.text.DecimalFormatSymbols(locale));
        }
        return p -> {
            var ev = p.next();
            if (ev == JsonParser.Event.VALUE_NULL) return null;
            String s = p.getString();
            try {
                Number parsed = fmt.parse(s);
                return convertNumber(parsed, boxed);
            } catch (java.text.ParseException pe) {
                throw new JsonbException("Number parse error: " + pe.getMessage(), pe);
            }
        };
    }

    private static Class<?> boxOfPrim(Class<?> p) {
        if (p == int.class) return Integer.class;
        if (p == long.class) return Long.class;
        if (p == double.class) return Double.class;
        if (p == float.class) return Float.class;
        if (p == short.class) return Short.class;
        if (p == byte.class) return Byte.class;
        return p;
    }

    private static Object convertNumber(Number n, Class<?> target) {
        if (target == Integer.class) return n.intValue();
        if (target == Long.class) return n.longValue();
        if (target == Double.class) return n.doubleValue();
        if (target == Float.class) return n.floatValue();
        if (target == Short.class) return n.shortValue();
        if (target == Byte.class) return n.byteValue();
        if (target == BigDecimal.class) return new BigDecimal(n.toString());
        if (target == BigInteger.class) return new BigInteger(n.toString());
        return n;
    }

    /** Cherche un field (toutes visibilités) sur la classe ou ses parents. */
    private static Field findFieldByName(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    /** Cherche un getter conventionnel pour la propriété {@code prop}. */
    private static Method findGetter(Class<?> type, String prop) {
        String cap = Character.toUpperCase(prop.charAt(0)) + prop.substring(1);
        for (Method m : type.getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getName().equals("get" + cap) || m.getName().equals("is" + cap)) return m;
        }
        return null;
    }

    private String methodJsonbName(Method setter, Method getter, String defaultName) {
        var prop = setter.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (prop != null && !prop.value().isEmpty()) return applyNaming(prop.value(), true);
        if (getter != null) {
            var p2 = getter.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
            if (p2 != null && !p2.value().isEmpty()) return applyNaming(p2.value(), true);
        }
        // Field underlying (spec §4.1.2)
        Field f = findFieldByName(setter.getDeclaringClass(), defaultName);
        if (f != null) {
            var fp = f.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
            if (fp != null && !fp.value().isEmpty()) return applyNaming(fp.value(), true);
        }
        return applyNaming(defaultName, false);
    }

    private String applyNaming(String name, boolean annotated) {
        if (annotated || propertyNamingStrategy == null) return name;
        return RuntimeBindingRegistry.transformName(name, propertyNamingStrategy);
    }

    /** Setter polymorphe : reçoit l'instance + parser et applique la valeur lue. */
    interface BeanWriter {
        void apply(Object target, JsonParser p);
    }

    private record MethodSetter(Method m, BindingReader reader) implements BeanWriter {
        public void apply(Object target, JsonParser p) {
            Object value;
            try { value = reader.read(p); }
            catch (JsonbException e) { throw e; }
            catch (RuntimeException e) { throw new JsonbException("Failed to read property " + m.getName() + ": " + e.getMessage(), e); }
            try { m.invoke(target, value); }
            catch (ReflectiveOperationException e) {
                throw new JsonbException("Setter failed: " + m, e);
            }
            catch (IllegalArgumentException e) {
                throw new JsonbException("Setter argument mismatch on " + m
                        + " — got " + (value == null ? "null" : value.getClass().getName())
                        + " for param " + m.getParameterTypes()[0].getName(), e);
            }
        }
    }

    private record FieldSetter(Field f, BindingReader reader) implements BeanWriter {
        public void apply(Object target, JsonParser p) {
            Object value;
            try { value = reader.read(p); }
            catch (JsonbException e) { throw e; }
            catch (RuntimeException e) { throw new JsonbException("Failed to read field " + f.getName() + ": " + e.getMessage(), e); }
            try { f.set(target, value); }
            catch (IllegalAccessException e) {
                throw new JsonbException("Field set failed: " + f, e);
            }
        }
    }

    private Object readObjectAndApply(JsonParser p, Constructor<?> ctor, Map<String, BeanWriter> writers) {
        JsonParser.Event e = p.next();
        if (e == JsonParser.Event.VALUE_NULL) return null;
        if (e != JsonParser.Event.START_OBJECT) throw new JsonbException("Expected object, got " + e);
        Object inst;
        try { inst = ctor.newInstance(); }
        catch (ReflectiveOperationException ex) { throw new JsonbException("ctor failed", ex); }
        while ((e = p.next()) != JsonParser.Event.END_OBJECT) {
            if (e != JsonParser.Event.KEY_NAME) throw new JsonbException("Expected KEY_NAME, got " + e);
            String key = p.getString();
            BeanWriter w = writers.get(key);
            if (w == null) {
                if (failOnUnknownProperties) {
                    throw new JsonbException("Unknown property: '" + key + "' on " + ctor.getDeclaringClass());
                }
                skipValue(p);
            } else {
                w.apply(inst, p);
            }
        }
        return inst;
    }

    /**
     * Cherche un constructor ou une static factory annotée {@code @JsonbCreator}.
     * Renvoie null si aucun n'est trouvé. La spec §4.6 autorise au plus un creator.
     */
    private static java.lang.reflect.Executable findJsonbCreator(Class<?> type) {
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            if (c.isAnnotationPresent(jakarta.json.bind.annotation.JsonbCreator.class)) return c;
        }
        for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                    && m.isAnnotationPresent(jakarta.json.bind.annotation.JsonbCreator.class)) return m;
        }
        return null;
    }

    /**
     * Construit un BindingReader qui lit le JSON, mappe les paramètres du creator
     * via {@code @JsonbProperty} (ou nom de paramètre par défaut), et invoque
     * le constructor ou la static factory pour produire l'instance.
     */
    private BindingReader resolveCreator(java.lang.reflect.Executable creator) {
        try { creator.setAccessible(true); } catch (Exception ignore) {}
        java.lang.reflect.Parameter[] params = creator.getParameters();
        Class<?>[] paramTypes = creator.getParameterTypes();
        BindingReader[] readers = new BindingReader[params.length];
        Map<String, Integer> indexByName = new HashMap<>(params.length * 2);
        for (int i = 0; i < params.length; i++) {
            readers[i] = readerFor(creator instanceof Constructor<?> c
                    ? c.getGenericParameterTypes()[i]
                    : ((java.lang.reflect.Method) creator).getGenericParameterTypes()[i]);
            String name = paramJsonbName(params[i]);
            indexByName.put(name, i);
        }
        return parser -> readObjectAndInvokeCreator(parser, creator, paramTypes, readers, indexByName);
    }

    private static String paramJsonbName(java.lang.reflect.Parameter p) {
        var prop = p.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (prop != null && !prop.value().isEmpty()) return prop.value();
        return p.getName();
    }

    @SuppressWarnings("unchecked")
    private Object readObjectAndInvokeCreator(JsonParser p, java.lang.reflect.Executable creator,
                                              Class<?>[] paramTypes, BindingReader[] readers,
                                              Map<String, Integer> indexByName) {
        JsonParser.Event e = p.next();
        if (e == JsonParser.Event.VALUE_NULL) return null;
        if (e != JsonParser.Event.START_OBJECT) {
            throw new JsonbException("Expected object, got " + e);
        }
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            args[i] = defaultFor(paramTypes[i]);
        }
        while ((e = p.next()) != JsonParser.Event.END_OBJECT) {
            if (e != JsonParser.Event.KEY_NAME) throw new JsonbException("Expected KEY_NAME, got " + e);
            String key = p.getString();
            Integer idx = indexByName.get(key);
            if (idx == null) {
                skipValue(p);
            } else {
                args[idx] = readers[idx].read(p);
            }
        }
        try {
            if (creator instanceof Constructor<?> c) return c.newInstance(args);
            return ((java.lang.reflect.Method) creator).invoke(null, args);
        } catch (ReflectiveOperationException ex) {
            throw new JsonbException("Failed to invoke @JsonbCreator: " + ex.getMessage(), ex);
        }
    }

    private Object readObjectAndConstruct(JsonParser p, Constructor<?> ctor,
                                          Class<?>[] paramTypes, BindingReader[] readers,
                                          Map<String, Integer> indexByName) {
        JsonParser.Event e = p.next();
        if (e == JsonParser.Event.VALUE_NULL) return null;
        if (e != JsonParser.Event.START_OBJECT) {
            throw new JsonbException("Expected object, got " + e);
        }
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            args[i] = defaultFor(paramTypes[i]);
        }
        while ((e = p.next()) != JsonParser.Event.END_OBJECT) {
            if (e != JsonParser.Event.KEY_NAME) throw new JsonbException("Expected KEY_NAME, got " + e);
            String key = p.getString();
            Integer idx = indexByName.get(key);
            if (idx == null) {
                skipValue(p);
            } else {
                args[idx] = readers[idx].read(p);
            }
        }
        try {
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException ex) {
            throw new JsonbException("Failed to instantiate record: " + ex.getMessage(), ex);
        }
    }

    private Object readObjectAndAssign(JsonParser p, Constructor<?> ctor, Map<String, Field> fields) {
        JsonParser.Event e = p.next();
        if (e == JsonParser.Event.VALUE_NULL) return null;
        if (e != JsonParser.Event.START_OBJECT) throw new JsonbException("Expected object, got " + e);
        Object inst;
        try {
            inst = ctor.newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new JsonbException("Failed to instantiate POJO: " + ex.getMessage(), ex);
        }
        while ((e = p.next()) != JsonParser.Event.END_OBJECT) {
            if (e != JsonParser.Event.KEY_NAME) throw new JsonbException("Expected KEY_NAME, got " + e);
            String key = p.getString();
            Field f = fields.get(key);
            if (f == null) { skipValue(p); continue; }
            BindingReader r = readerFor(f.getGenericType());
            Object value = r.read(p);
            try { f.set(inst, value); }
            catch (IllegalAccessException ex) { throw new JsonbException("Cannot set field " + f, ex); }
        }
        return inst;
    }

    private static Object defaultFor(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        if (type == boolean.class) return false;
        return null;
    }

    /** Saute la valeur courante (peut être un objet ou array imbriqué). */
    private static void skipValue(JsonParser p) {
        JsonParser.Event e = p.next();
        if (e == JsonParser.Event.START_OBJECT) p.skipObject();
        else if (e == JsonParser.Event.START_ARRAY) p.skipArray();
        // sinon scalaire → consommé par next()
    }

    // ===== Parameterized =====

    private BindingReader parameterizedReader(java.lang.reflect.ParameterizedType p) {
        Class<?> raw = (Class<?>) p.getRawType();
        Type[] args = p.getActualTypeArguments();
        if (java.util.Map.class.isAssignableFrom(raw)) {
            BindingReader vr = args.length >= 2 ? readerFor(args[1]) : (BindingReader) this::dynamicValue;
            return mapReader(vr, raw);
        }
        if (java.util.Collection.class.isAssignableFrom(raw)) {
            BindingReader er = args.length >= 1 ? readerFor(args[0]) : (BindingReader) this::dynamicValue;
            return collectionReader(er, raw);
        }
        if (raw == Optional.class) {
            BindingReader inner = args.length >= 1 ? readerFor(args[0]) : (BindingReader) this::dynamicValue;
            return optionalReader(inner);
        }
        return classCache.get(raw);
    }

    private BindingReader mapReader(BindingReader valueReader) {
        return mapReader(valueReader, Map.class);
    }

    private BindingReader mapReader(BindingReader valueReader, Class<?> raw) {
        return parser -> {
            JsonParser.Event e = parser.next();
            if (e == JsonParser.Event.VALUE_NULL) return null;
            if (e != JsonParser.Event.START_OBJECT) throw new JsonbException("Expected object for Map, got " + e);
            Map<String, Object> out = newMapFor(raw);
            while ((e = parser.next()) != JsonParser.Event.END_OBJECT) {
                if (e != JsonParser.Event.KEY_NAME) throw new JsonbException("Expected KEY_NAME, got " + e);
                String key = parser.getString();
                out.put(key, valueReader.read(parser));
            }
            return out;
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<String, Object> newMapFor(Class<?> raw) {
        if (java.util.SortedMap.class.isAssignableFrom(raw) || java.util.NavigableMap.class.isAssignableFrom(raw)
                || raw == java.util.TreeMap.class) {
            return new java.util.TreeMap<>();
        }
        if (raw == java.util.concurrent.ConcurrentHashMap.class) return new java.util.concurrent.ConcurrentHashMap<>();
        return new LinkedHashMap<>();
    }

    private BindingReader collectionReader(BindingReader elemReader, Class<?> raw) {
        return parser -> {
            JsonParser.Event e = parser.next();
            if (e == JsonParser.Event.VALUE_NULL) return null;
            if (e != JsonParser.Event.START_ARRAY) throw new JsonbException("Expected array, got " + e);
            java.util.Collection<Object> out = newCollectionFor(raw);
            while (true) {
                JsonParser.Event tok = parser.next();
                if (tok == JsonParser.Event.END_ARRAY) return out;
                JsonParser primed = new PrimedParser(tok, parser);
                out.add(elemReader.read(primed));
            }
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static java.util.Collection<Object> newCollectionFor(Class<?> raw) {
        // Cas exacts d'abord (avant les check d'interface qui pourraient matcher).
        if (raw == java.util.LinkedList.class) return new java.util.LinkedList<>();
        if (raw == java.util.ArrayDeque.class) return new java.util.ArrayDeque<>();
        if (raw == java.util.PriorityQueue.class) return new java.util.PriorityQueue<>();
        if (raw == java.util.concurrent.PriorityBlockingQueue.class) return new java.util.concurrent.PriorityBlockingQueue<>();
        if (raw == java.util.TreeSet.class) return new java.util.TreeSet<>();
        if (raw == java.util.HashSet.class) return new java.util.HashSet<>();
        if (raw == java.util.LinkedHashSet.class) return new java.util.LinkedHashSet<>();
        // Interfaces / supertypes ensuite.
        if (java.util.SortedSet.class.isAssignableFrom(raw) || java.util.NavigableSet.class.isAssignableFrom(raw)) {
            return new java.util.TreeSet<>();
        }
        if (java.util.Set.class.isAssignableFrom(raw)) return new java.util.LinkedHashSet<>();
        if (java.util.Deque.class.isAssignableFrom(raw)) return new java.util.ArrayDeque<>();
        if (java.util.Queue.class.isAssignableFrom(raw)) return new java.util.LinkedList<>();
        return new ArrayList<>();
    }

    // ===== Optional / Builtins / arrays =====

    private BindingReader optionalReader(BindingReader inner) {
        return parser -> {
            // Lit la valeur ; null → Optional.empty
            Object v = inner.read(parser);
            return v == null ? Optional.empty() : Optional.of(v);
        };
    }

    private BindingReader arrayReader(Class<?> componentType) {
        if (componentType == byte.class) return byteArrayReader();
        if (componentType == int.class) {
            return parser -> {
                List<Object> tmp = (List<Object>) collectArray(parser, ((BindingReader) (q -> { q.next(); return q.getInt(); })));
                if (tmp == null) return null;
                int[] out = new int[tmp.size()];
                for (int i = 0; i < out.length; i++) out[i] = (Integer) tmp.get(i);
                return out;
            };
        }
        if (componentType == long.class) {
            return parser -> {
                List<Object> tmp = (List<Object>) collectArray(parser, q -> { q.next(); return q.getLong(); });
                if (tmp == null) return null;
                long[] out = new long[tmp.size()];
                for (int i = 0; i < out.length; i++) out[i] = (Long) tmp.get(i);
                return out;
            };
        }
        if (componentType == double.class) {
            return parser -> {
                List<Object> tmp = (List<Object>) collectArray(parser, q -> { q.next(); return q.getBigDecimal().doubleValue(); });
                if (tmp == null) return null;
                double[] out = new double[tmp.size()];
                for (int i = 0; i < out.length; i++) out[i] = (Double) tmp.get(i);
                return out;
            };
        }
        if (componentType == boolean.class) {
            return parser -> {
                List<Object> tmp = (List<Object>) collectArray(parser, q -> {
                    JsonParser.Event ev = q.next();
                    return ev == JsonParser.Event.VALUE_TRUE;
                });
                if (tmp == null) return null;
                boolean[] out = new boolean[tmp.size()];
                for (int i = 0; i < out.length; i++) out[i] = (Boolean) tmp.get(i);
                return out;
            };
        }
        if (componentType == short.class) {
            return parser -> {
                List<Object> tmp = (List<Object>) collectArray(parser, q -> { q.next(); return (short) q.getInt(); });
                if (tmp == null) return null;
                short[] out = new short[tmp.size()];
                for (int i = 0; i < out.length; i++) out[i] = (Short) tmp.get(i);
                return out;
            };
        }
        if (componentType == float.class) {
            return parser -> {
                List<Object> tmp = (List<Object>) collectArray(parser, q -> { q.next(); return q.getBigDecimal().floatValue(); });
                if (tmp == null) return null;
                float[] out = new float[tmp.size()];
                for (int i = 0; i < out.length; i++) out[i] = (Float) tmp.get(i);
                return out;
            };
        }
        if (componentType == char.class) {
            return parser -> {
                List<Object> tmp = (List<Object>) collectArray(parser, q -> { q.next(); String s = q.getString(); return s.isEmpty() ? '\0' : s.charAt(0); });
                if (tmp == null) return null;
                char[] out = new char[tmp.size()];
                for (int i = 0; i < out.length; i++) out[i] = (Character) tmp.get(i);
                return out;
            };
        }
        BindingReader elem = readerFor(componentType);
        return parser -> {
            List<Object> tmp = (List<Object>) collectArray(parser, elem);
            if (tmp == null) return null;
            Object out = java.lang.reflect.Array.newInstance(componentType, tmp.size());
            for (int i = 0; i < tmp.size(); i++) java.lang.reflect.Array.set(out, i, tmp.get(i));
            return out;
        };
    }

    /**
     * JSON-B 3.0 §3.3.1 — désérialisation byte[] :
     * <ul>
     *   <li>BYTE (défaut) : lit un array d'entiers</li>
     *   <li>BASE_64 : lit une string base64 standard</li>
     *   <li>BASE_64_URL : lit une string base64 URL-safe</li>
     * </ul>
     * On accepte aussi un fallback : si la stratégie est BYTE mais qu'on lit une string,
     * on tente le décodage base64 (et inversement) pour rester tolérant.
     */
    private BindingReader byteArrayReader() {
        String s = binaryDataStrategy;
        if ("BASE_64".equals(s)) {
            return parser -> {
                JsonParser.Event ev = parser.next();
                if (ev == JsonParser.Event.VALUE_NULL) return null;
                if (ev != JsonParser.Event.VALUE_STRING) {
                    throw new JsonbException("Expected VALUE_STRING for BASE_64 byte[], got " + ev);
                }
                return java.util.Base64.getDecoder().decode(parser.getString());
            };
        }
        if ("BASE_64_URL".equals(s)) {
            return parser -> {
                JsonParser.Event ev = parser.next();
                if (ev == JsonParser.Event.VALUE_NULL) return null;
                if (ev != JsonParser.Event.VALUE_STRING) {
                    throw new JsonbException("Expected VALUE_STRING for BASE_64_URL byte[], got " + ev);
                }
                return java.util.Base64.getUrlDecoder().decode(parser.getString());
            };
        }
        // BYTE (défaut)
        return parser -> {
            List<Object> tmp = (List<Object>) collectArray(parser, q -> { q.next(); return (byte) q.getInt(); });
            if (tmp == null) return null;
            byte[] out = new byte[tmp.size()];
            for (int i = 0; i < out.length; i++) out[i] = (Byte) tmp.get(i);
            return out;
        };
    }

    /**
     * Lit un array et applique {@code elem.read} pour chaque élément. Gère le cas
     * où l'event de tête est consommé en regardant {@code hasNext} avant de relancer.
     */
    private static Object collectArray(JsonParser p, BindingReader elem) {
        JsonParser.Event e = p.next();
        if (e == JsonParser.Event.VALUE_NULL) return null;
        if (e != JsonParser.Event.START_ARRAY) throw new JsonbException("Expected array, got " + e);
        var out = new ArrayList<Object>();
        // Wrapper "lookahead" : on lit next, si END_ARRAY on sort, sinon on rebranche un
        // mini-parser qui restitue cet event au lecteur élément.
        while (true) {
            JsonParser.Event tok = p.next();
            if (tok == JsonParser.Event.END_ARRAY) return out;
            // Replay : crée un parser "amorcé" qui retourne tok puis délègue.
            JsonParser primed = new PrimedParser(tok, p);
            out.add(elem.read(primed));
        }
    }

    /**
     * Parser qui ré-émet un event consommé en amont, puis délègue le reste au parser sous-jacent.
     * Permet aux readers de toujours commencer par {@code parser.next()}.
     */
    private static final class PrimedParser implements JsonParser {
        private JsonParser.Event primed;
        private final JsonParser delegate;
        PrimedParser(JsonParser.Event primed, JsonParser delegate) { this.primed = primed; this.delegate = delegate; }
        @Override public boolean hasNext() { return primed != null || delegate.hasNext(); }
        @Override public Event next() {
            if (primed != null) { var e = primed; primed = null; return e; }
            return delegate.next();
        }
        @Override public String getString() { return delegate.getString(); }
        @Override public boolean isIntegralNumber() { return delegate.isIntegralNumber(); }
        @Override public int getInt() { return delegate.getInt(); }
        @Override public long getLong() { return delegate.getLong(); }
        @Override public java.math.BigDecimal getBigDecimal() { return delegate.getBigDecimal(); }
        @Override public jakarta.json.stream.JsonLocation getLocation() { return delegate.getLocation(); }
        @Override public jakarta.json.JsonValue getValue() { return delegate.getValue(); }
        @Override public jakarta.json.JsonObject getObject() { return delegate.getObject(); }
        @Override public jakarta.json.JsonArray getArray() { return delegate.getArray(); }
        @Override public java.util.stream.Stream<jakarta.json.JsonValue> getArrayStream() { return delegate.getArrayStream(); }
        @Override public java.util.stream.Stream<Map.Entry<String, jakarta.json.JsonValue>> getObjectStream() { return delegate.getObjectStream(); }
        @Override public java.util.stream.Stream<jakarta.json.JsonValue> getValueStream() { return delegate.getValueStream(); }
        @Override public void skipArray() { delegate.skipArray(); }
        @Override public void skipObject() { delegate.skipObject(); }
        @Override public void close() { delegate.close(); }
    }

    private BindingReader enumReader(Class<?> type) {
        @SuppressWarnings({"rawtypes", "unchecked"})
        Class<? extends Enum> e = (Class<? extends Enum>) type;
        return parser -> {
            JsonParser.Event ev = parser.next();
            if (ev == JsonParser.Event.VALUE_NULL) return null;
            if (ev != JsonParser.Event.VALUE_STRING) throw new JsonbException("Expected string for enum, got " + ev);
            @SuppressWarnings("unchecked")
            Object out = Enum.valueOf(e, parser.getString());
            return out;
        };
    }

    /** Lit la valeur courante en tant qu'Object / déterminée dynamiquement. */
    private Object dynamicValue(JsonParser p) {
        JsonParser.Event e = p.next();
        return switch (e) {
            case VALUE_STRING -> p.getString();
            case VALUE_NUMBER -> {
                BigDecimal bd = p.getBigDecimal();
                if (p.isIntegralNumber()) yield bd.toBigIntegerExact().longValueExact() <= Integer.MAX_VALUE && bd.toBigIntegerExact().longValueExact() >= Integer.MIN_VALUE
                        ? bd.intValueExact() : bd.toBigIntegerExact();
                yield bd;
            }
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            case START_OBJECT -> readDynamicMap(p);
            case START_ARRAY -> readDynamicArray(p);
            default -> throw new JsonbException("Unexpected event: " + e);
        };
    }

    private Map<String, Object> readDynamicMap(JsonParser p) {
        var out = new LinkedHashMap<String, Object>();
        while (true) {
            JsonParser.Event e = p.next();
            if (e == JsonParser.Event.END_OBJECT) return out;
            if (e != JsonParser.Event.KEY_NAME) throw new JsonbException("Expected KEY_NAME");
            String key = p.getString();
            // recursion on dynamic
            JsonParser primed;
            // We need to read a value, but dynamicValue advances next() itself : no extra primed needed
            out.put(key, dynamicValue(p));
        }
    }

    private List<Object> readDynamicArray(JsonParser p) {
        var out = new ArrayList<Object>();
        while (true) {
            JsonParser.Event e = p.next();
            if (e == JsonParser.Event.END_ARRAY) return out;
            JsonParser primed = new PrimedParser(e, p);
            out.add(dynamicValue(primed));
        }
    }

    /**
     * Reader polymorphe : lit la clé discriminante (en première position dans le JSON,
     * MVP M4.5), trouve la classe concrète associée à l'alias, puis lit les membres
     * restants directement dans le record/POJO concret via une variante de
     * readObjectAndConstruct qui assume START_OBJECT déjà consommé.
     *
     * <p>Note : Champollion écrit toujours la clé discriminante en première position.
     * Pour interopérer avec d'autres implémentations qui placent la clé ailleurs, il
     * faudrait bufferiser tout l'objet — reporté.</p>
     */
    private BindingReader polymorphicReader(jakarta.json.bind.annotation.JsonbTypeInfo info) {
        String discriminantKey = info.key();
        Map<String, Class<?>> aliasToType = new HashMap<>();
        for (var sub : info.value()) {
            aliasToType.put(sub.alias(), sub.type());
        }
        return parser -> {
            JsonParser.Event e = parser.next();
            if (e == JsonParser.Event.VALUE_NULL) return null;
            if (e != JsonParser.Event.START_OBJECT) {
                throw new JsonbException("Expected START_OBJECT for polymorphic value, got " + e);
            }
            // Lit la première clé : doit être discriminantKey
            e = parser.next();
            if (e != JsonParser.Event.KEY_NAME) {
                throw new JsonbException("Expected KEY_NAME, got " + e);
            }
            String key = parser.getString();
            if (!key.equals(discriminantKey)) {
                throw new JsonbException("Expected discriminator '" + discriminantKey + "' as first member, got '" + key + "'");
            }
            e = parser.next();
            if (e != JsonParser.Event.VALUE_STRING) {
                throw new JsonbException("Expected VALUE_STRING for discriminator, got " + e);
            }
            String alias = parser.getString();
            Class<?> concrete = aliasToType.get(alias);
            if (concrete == null) {
                throw new JsonbException("Unknown @JsonbSubtype alias: " + alias);
            }
            return readMembersOnly(parser, concrete);
        };
    }

    /**
     * Lit les membres restants d'un objet (sans START_OBJECT initial) et construit
     * une instance du type concret. Utilisé pour la deuxième phase de la lecture
     * polymorphe.
     */
    private Object readMembersOnly(JsonParser parser, Class<?> concrete) {
        if (concrete.isRecord()) {
            RecordComponent[] comps = concrete.getRecordComponents();
            Class<?>[] paramTypes = new Class<?>[comps.length];
            BindingReader[] readers = new BindingReader[comps.length];
            Map<String, Integer> indexByName = new HashMap<>(comps.length * 2);
            for (int i = 0; i < comps.length; i++) {
                final RecordComponent comp = comps[i];
                paramTypes[i] = comp.getType();
                readers[i] = customAdapterReader(comp)
                        .or(() -> customDateReader(comp))
                        .or(() -> globalDateReader(comp.getType()))
                        .orElseGet(() -> readerFor(comp.getGenericType()));
                if (isJsonbTransient(comp)) continue;
                indexByName.put(jsonbName(comp), i);
            }
            Constructor<?> ctor;
            try { ctor = concrete.getDeclaredConstructor(paramTypes); }
            catch (NoSuchMethodException e) {
                throw new JsonbException("Canonical record constructor not found for " + concrete, e);
            }
            try { ctor.setAccessible(true); } catch (Exception ignore) {}
            Object[] args = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) args[i] = defaultFor(paramTypes[i]);
            JsonParser.Event e;
            while ((e = parser.next()) != JsonParser.Event.END_OBJECT) {
                if (e != JsonParser.Event.KEY_NAME) {
                    throw new JsonbException("Expected KEY_NAME, got " + e);
                }
                String key = parser.getString();
                Integer idx = indexByName.get(key);
                if (idx == null) skipValue(parser);
                else args[idx] = readers[idx].read(parser);
            }
            try { return ctor.newInstance(args); }
            catch (ReflectiveOperationException ex) {
                throw new JsonbException("ctor failed for " + concrete, ex);
            }
        }
        // POJO non-record : on construit via no-arg constructor + setters publics.
        Constructor<?> ctor;
        try { ctor = concrete.getDeclaredConstructor(); }
        catch (NoSuchMethodException nse) {
            throw new JsonbException("No no-arg constructor for " + concrete, nse);
        }
        int cMods = ctor.getModifiers();
        if (!Modifier.isPublic(cMods) && !Modifier.isProtected(cMods)) {
            throw new JsonbException("No accessible no-arg constructor for " + concrete);
        }
        try { ctor.setAccessible(true); } catch (Exception ignore) {}
        Object inst;
        try { inst = ctor.newInstance(); }
        catch (ReflectiveOperationException ex) { throw new JsonbException("ctor failed for " + concrete, ex); }
        Map<String, BeanWriter> writers = new HashMap<>();
        for (Method sm : concrete.getMethods()) {
            if (Modifier.isStatic(sm.getModifiers()) || sm.isBridge() || sm.isSynthetic()) continue;
            if (sm.getReturnType() != void.class) continue;
            String prop = RuntimeBindingRegistry.beanSetterOf(sm);
            if (prop == null) continue;
            try { sm.setAccessible(true); } catch (Exception ignore) {}
            BindingReader reader = readerFor(sm.getGenericParameterTypes()[0]);
            writers.put(prop, new MethodSetter(sm, reader));
        }
        JsonParser.Event ev;
        while ((ev = parser.next()) != JsonParser.Event.END_OBJECT) {
            if (ev != JsonParser.Event.KEY_NAME) throw new JsonbException("Expected KEY_NAME, got " + ev);
            String key = parser.getString();
            BeanWriter w = writers.get(key);
            if (w == null) skipValue(parser);
            else w.apply(inst, parser);
        }
        return inst;
    }

    /**
     * Reader global pour les types {@code java.time.*} basé sur {@code JSONB_DATE_FORMAT}.
     * Renvoie empty si pas de pattern global ou si {@code raw} n'est pas un type java.time supporté.
     */
    private java.util.Optional<BindingReader> globalDateReader(Class<?> raw) {
        if (defaultDateFormat == null) return java.util.Optional.empty();
        if (raw != java.time.LocalDate.class
                && raw != java.time.LocalDateTime.class
                && raw != java.time.OffsetDateTime.class
                && raw != java.time.ZonedDateTime.class
                && raw != java.time.Instant.class) {
            return java.util.Optional.empty();
        }
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern(defaultDateFormat);
        return java.util.Optional.of(parser -> {
            JsonParser.Event ev = parser.next();
            if (ev == JsonParser.Event.VALUE_NULL) return null;
            String s = parser.getString();
            if (raw == java.time.LocalDate.class) return java.time.LocalDate.parse(s, fmt);
            if (raw == java.time.LocalDateTime.class) return java.time.LocalDateTime.parse(s, fmt);
            if (raw == java.time.OffsetDateTime.class) return java.time.OffsetDateTime.parse(s, fmt);
            if (raw == java.time.ZonedDateTime.class) return java.time.ZonedDateTime.parse(s, fmt);
            return java.time.Instant.from(fmt.parse(s));
        });
    }

    /**
     * Si le composant a {@code @JsonbTypeAdapter(class)}, retourne un reader qui :
     * 1. instancie l'adapter (no-arg ctor),
     * 2. délègue la lecture au reader du type {@code Adapted},
     * 3. invoque {@code adaptFromJson(adapted)} pour reconstruire l'Original.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private java.util.Optional<BindingReader> customAdapterReader(RecordComponent c) {
        var direct = c.getAnnotation(jakarta.json.bind.annotation.JsonbTypeAdapter.class);
        var fromAccessor = direct == null
                ? c.getAccessor().getAnnotation(jakarta.json.bind.annotation.JsonbTypeAdapter.class)
                : null;
        var ann = direct != null ? direct : fromAccessor;
        if (ann == null) return java.util.Optional.empty();
        Class<? extends jakarta.json.bind.adapter.JsonbAdapter> adapterClass = ann.value();
        jakarta.json.bind.adapter.JsonbAdapter adapter;
        try {
            adapter = adapterClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new JsonbException("Cannot instantiate JsonbAdapter " + adapterClass, e);
        }
        Class<?> adaptedType = RuntimeBindingRegistry.findAdaptedType(adapterClass);
        BindingReader inner = readerFor(adaptedType);
        return java.util.Optional.of(parser -> {
            Object adapted = inner.read(parser);
            if (adapted == null) return null;
            try { return adapter.adaptFromJson(adapted); }
            catch (Exception ex) { throw new JsonbException("Adapter failure on fromJson: " + ex.getMessage(), ex); }
        });
    }

    /**
     * Si le composant a {@code @JsonbDateFormat}, retourne un reader custom.
     * Couvre LocalDate, LocalDateTime, OffsetDateTime, ZonedDateTime, Instant.
     */
    private static java.util.Optional<BindingReader> customDateReader(RecordComponent c) {
        var direct = c.getAnnotation(jakarta.json.bind.annotation.JsonbDateFormat.class);
        var fromAccessor = direct == null
                ? c.getAccessor().getAnnotation(jakarta.json.bind.annotation.JsonbDateFormat.class)
                : null;
        var ann = direct != null ? direct : fromAccessor;
        if (ann == null) return java.util.Optional.empty();
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern(ann.value());
        Class<?> raw = c.getType();
        return java.util.Optional.of(parser -> {
            JsonParser.Event e = parser.next();
            if (e == JsonParser.Event.VALUE_NULL) return null;
            if (e != JsonParser.Event.VALUE_STRING) {
                throw new JsonbException("@JsonbDateFormat expects a JSON string, got " + e);
            }
            String s = parser.getString();
            if (raw == java.time.LocalDate.class) return java.time.LocalDate.parse(s, fmt);
            if (raw == java.time.LocalDateTime.class) return java.time.LocalDateTime.parse(s, fmt);
            if (raw == java.time.OffsetDateTime.class) return java.time.OffsetDateTime.parse(s, fmt);
            if (raw == java.time.ZonedDateTime.class) return java.time.ZonedDateTime.parse(s, fmt);
            if (raw == java.time.Instant.class) {
                // Instant n'a pas de parse(String, DateTimeFormatter) direct ; on passe par OffsetDateTime
                // si le pattern le permet, sinon on délègue à Instant.from.
                return java.time.Instant.from(fmt.parse(s));
            }
            throw new JsonbException("@JsonbDateFormat unsupported type: " + raw);
        });
    }

    // ===== Customization helpers (M4.4) =====

    private static boolean isJsonbTransient(RecordComponent c) {
        if (c.isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class)) return true;
        return c.getAccessor().isAnnotationPresent(jakarta.json.bind.annotation.JsonbTransient.class);
    }

    private String jsonbName(RecordComponent c) {
        var prop = c.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (prop != null && !prop.value().isEmpty()) return applyNaming(prop.value(), true);
        var accessorProp = c.getAccessor().getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (accessorProp != null && !accessorProp.value().isEmpty()) return applyNaming(accessorProp.value(), true);
        return applyNaming(c.getName(), false);
    }

    private String jsonbName(Field f) {
        var prop = f.getAnnotation(jakarta.json.bind.annotation.JsonbProperty.class);
        if (prop != null && !prop.value().isEmpty()) return applyNaming(prop.value(), true);
        return applyNaming(f.getName(), false);
    }

    // ===== Builtins =====

    /** Rejette les IDs de timezone à 3 lettres dépréciés (JDK : ZoneId.SHORT_IDS). */
    private static final java.util.Set<String> DEPRECATED_TZ_IDS = java.util.Set.of(
            "ACT", "AET", "AGT", "ART", "AST", "BET", "BST", "CAT", "CNT", "CST",
            "CTT", "EAT", "ECT", "EST", "HST", "IET", "IST", "JST", "MIT", "MST",
            "NET", "NST", "PLT", "PNT", "PRT", "PST", "SST", "VST");

    private static void rejectDeprecatedTimezoneId(String id) {
        if (DEPRECATED_TZ_IDS.contains(id)) {
            throw new JsonbException("Deprecated three-letter time zone ID: " + id);
        }
    }

    /** Parse une string en {@link Instant} en essayant plusieurs formats ISO. */
    private static Instant parseAsInstant(String s) {
        try { return ZonedDateTime.parse(s, DateTimeFormatter.ISO_ZONED_DATE_TIME).toInstant(); } catch (Exception ignored) {}
        try { return OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant(); } catch (Exception ignored) {}
        try { return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(java.time.ZoneId.of("UTC")).toInstant(); } catch (Exception ignored) {}
        try { return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(java.time.ZoneId.of("UTC")).toInstant(); } catch (Exception ignored) {}
        return Instant.parse(s);
    }

    /** Parse une string en {@link ZonedDateTime} en essayant plusieurs formats ISO. */
    private static ZonedDateTime parseAsZonedDateTime(String s) {
        try { return ZonedDateTime.parse(s, DateTimeFormatter.ISO_ZONED_DATE_TIME); } catch (Exception ignored) {}
        try { return OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toZonedDateTime(); } catch (Exception ignored) {}
        try {
            // Date avec offset uniquement : "1970-01-01+01:00"
            return java.time.OffsetDateTime.parse(s + "T00:00:00" + s.substring(s.length()-6, s.length()),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME).toZonedDateTime();
        } catch (Exception ignored) {}
        try {
            return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(java.time.ZoneId.of("UTC"));
        } catch (Exception ignored) {}
        return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(java.time.ZoneId.of("UTC"));
    }

    private static final class Builtins {

        static BindingReader lookup(Class<?> type) {
            // JSON-P types
            if (type == jakarta.json.JsonObject.class) return p -> {
                var e = p.next();
                if (e == JsonParser.Event.VALUE_NULL) return null;
                if (e != JsonParser.Event.START_OBJECT) throw new JsonbException("Expected JSON object, got " + e);
                return p.getObject();
            };
            if (type == jakarta.json.JsonArray.class) return p -> {
                var e = p.next();
                if (e == JsonParser.Event.VALUE_NULL) return null;
                if (e != JsonParser.Event.START_ARRAY) throw new JsonbException("Expected JSON array, got " + e);
                return p.getArray();
            };
            if (type == jakarta.json.JsonString.class) return p -> {
                var e = p.next();
                if (e == JsonParser.Event.VALUE_NULL) return null;
                return p.getValue();
            };
            if (type == jakarta.json.JsonNumber.class) return p -> {
                var e = p.next();
                if (e == JsonParser.Event.VALUE_NULL) return null;
                return p.getValue();
            };
            if (type == jakarta.json.JsonStructure.class || type == jakarta.json.JsonValue.class) return p -> {
                var e = p.next();
                if (e == JsonParser.Event.VALUE_NULL) return jakarta.json.JsonValue.NULL;
                return p.getValue();
            };
            if (type == String.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : p.getString(); };
            if (type == Integer.class || type == int.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : p.getInt(); };
            if (type == Long.class || type == long.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : p.getLong(); };
            if (type == Double.class || type == double.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : p.getBigDecimal().doubleValue(); };
            if (type == Float.class || type == float.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : p.getBigDecimal().floatValue(); };
            if (type == Short.class || type == short.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : (short) p.getInt(); };
            if (type == Byte.class || type == byte.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : (byte) p.getInt(); };
            if (type == Boolean.class || type == boolean.class) return p -> {
                var e = p.next();
                if (e == JsonParser.Event.VALUE_NULL) return null;
                if (e == JsonParser.Event.VALUE_TRUE) return Boolean.TRUE;
                if (e == JsonParser.Event.VALUE_FALSE) return Boolean.FALSE;
                throw new JsonbException("Expected boolean, got " + e);
            };
            if (type == Character.class || type == char.class) return p -> {
                var e = p.next();
                if (e == JsonParser.Event.VALUE_NULL) return null;
                String s = p.getString();
                return s.isEmpty() ? '\0' : s.charAt(0);
            };
            if (type == BigDecimal.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : p.getBigDecimal(); };
            if (type == BigInteger.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : p.getBigDecimal().toBigIntegerExact(); };
            if (type == UUID.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : UUID.fromString(p.getString()); };
            if (type == java.net.URI.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : java.net.URI.create(p.getString()); };
            if (type == java.net.URL.class) return p -> {
                var e = p.next();
                if (e == JsonParser.Event.VALUE_NULL) return null;
                try { return java.net.URI.create(p.getString()).toURL(); }
                catch (Exception ex) { throw new JsonbException("Invalid URL: " + ex.getMessage(), ex); }
            };
            if (type == java.util.OptionalInt.class) return p -> {
                var e = p.next();
                if (e == JsonParser.Event.VALUE_NULL) return java.util.OptionalInt.empty();
                return java.util.OptionalInt.of(p.getInt());
            };
            if (type == java.util.OptionalLong.class) return p -> {
                var e = p.next();
                if (e == JsonParser.Event.VALUE_NULL) return java.util.OptionalLong.empty();
                return java.util.OptionalLong.of(p.getLong());
            };
            if (type == java.util.OptionalDouble.class) return p -> {
                var e = p.next();
                if (e == JsonParser.Event.VALUE_NULL) return java.util.OptionalDouble.empty();
                return java.util.OptionalDouble.of(p.getBigDecimal().doubleValue());
            };
            if (type == Instant.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : Instant.parse(p.getString()); };
            if (type == LocalDate.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : LocalDate.parse(p.getString(), DateTimeFormatter.ISO_LOCAL_DATE); };
            if (type == LocalDateTime.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : LocalDateTime.parse(p.getString(), DateTimeFormatter.ISO_LOCAL_DATE_TIME); };
            if (type == OffsetDateTime.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : OffsetDateTime.parse(p.getString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME); };
            if (type == ZonedDateTime.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : ZonedDateTime.parse(p.getString(), DateTimeFormatter.ISO_ZONED_DATE_TIME); };
            if (type == java.time.LocalTime.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : java.time.LocalTime.parse(p.getString(), DateTimeFormatter.ISO_LOCAL_TIME); };
            if (type == java.time.OffsetTime.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : java.time.OffsetTime.parse(p.getString(), DateTimeFormatter.ISO_OFFSET_TIME); };
            if (type == java.time.Duration.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : java.time.Duration.parse(p.getString()); };
            if (type == java.time.Period.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : java.time.Period.parse(p.getString()); };
            if (type == java.time.MonthDay.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : java.time.MonthDay.parse(p.getString()); };
            if (type == java.time.YearMonth.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : java.time.YearMonth.parse(p.getString()); };
            if (type == java.time.Year.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : java.time.Year.parse(p.getString()); };
            if (type == java.time.ZoneOffset.class) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : java.time.ZoneOffset.of(p.getString()); };
            if (java.time.ZoneId.class.isAssignableFrom(type)) return p -> { var e = p.next(); return e == JsonParser.Event.VALUE_NULL ? null : java.time.ZoneId.of(p.getString()); };
            if (type == java.util.SimpleTimeZone.class) return p -> {
                var e = p.next();
                if (e == JsonParser.Event.VALUE_NULL) return null;
                String id = p.getString();
                rejectDeprecatedTimezoneId(id);
                var tz = java.util.TimeZone.getTimeZone(id);
                return new java.util.SimpleTimeZone(tz.getRawOffset(), id);
            };
            if (java.util.TimeZone.class.isAssignableFrom(type)) return p -> {
                var e = p.next();
                if (e == JsonParser.Event.VALUE_NULL) return null;
                String id = p.getString();
                rejectDeprecatedTimezoneId(id);
                return java.util.TimeZone.getTimeZone(id);
            };
            if (type == java.util.Date.class) return p -> {
                var e = p.next();
                if (e == JsonParser.Event.VALUE_NULL) return null;
                String s = p.getString();
                return java.util.Date.from(parseAsInstant(s));
            };
            if (java.util.GregorianCalendar.class.isAssignableFrom(type) || type == java.util.Calendar.class) return p -> {
                var e = p.next();
                if (e == JsonParser.Event.VALUE_NULL) return null;
                String s = p.getString();
                return java.util.GregorianCalendar.from(parseAsZonedDateTime(s));
            };
            return null;
        }
    }
}
