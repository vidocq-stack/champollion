package io.vidocq.champollion.jsonb.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Résolveur d'instance pour les beans JSON-B (Adapter / Serializer / Deserializer)
 * conformément à la spec Jakarta JSON-B 3.0 §5 :
 *
 * <blockquote>If the CDI container is available, the JSON Binding implementation
 * MUST resolve adapters, serializers and deserializers as CDI beans before
 * falling back to no-arg instantiation.</blockquote>
 *
 * <p>Le lookup CDI est fait par réflexion via {@link MethodHandle} (résolus une
 * seule fois au chargement de la classe) afin de <strong>ne pas créer de
 * dépendance runtime hard</strong> sur {@code jakarta.enterprise.cdi-api}. Si CDI
 * n'est pas sur le classpath, ou si {@code CDI.current()} jette
 * {@link IllegalStateException} (pas de container actif), on retombe sur
 * l'instanciation sans-arg via le constructeur déclaré.</p>
 */
final class CdiResolver {

    private static final MethodHandle CDI_CURRENT;
    private static final MethodHandle CDI_SELECT;
    private static final MethodHandle INSTANCE_GET;
    private static final MethodHandle INSTANCE_IS_UNSATISFIED;
    private static final MethodHandle INSTANCE_IS_AMBIGUOUS;
    private static final boolean CDI_AVAILABLE;
    private static final java.lang.annotation.Annotation[] EMPTY_QUALIFIERS = new java.lang.annotation.Annotation[0];

    static {
        MethodHandle current = null, select = null, get = null,
                isUnsatisfied = null, isAmbiguous = null;
        boolean available = false;
        try {
            Class<?> cdiCls = Class.forName("jakarta.enterprise.inject.spi.CDI");
            Class<?> instCls = Class.forName("jakarta.enterprise.inject.Instance");
            var lookup = MethodHandles.publicLookup();
            current = lookup.findStatic(cdiCls, "current", MethodType.methodType(cdiCls));
            select = lookup.findVirtual(cdiCls, "select",
                    MethodType.methodType(instCls, Class.class, java.lang.annotation.Annotation[].class));
            get = lookup.findVirtual(instCls, "get", MethodType.methodType(Object.class));
            isUnsatisfied = lookup.findVirtual(instCls, "isUnsatisfied", MethodType.methodType(boolean.class));
            isAmbiguous = lookup.findVirtual(instCls, "isAmbiguous", MethodType.methodType(boolean.class));
            available = true;
        } catch (Throwable ignore) {
            // CDI absent du classpath — fallback newInstance sera systématique.
        }
        CDI_CURRENT = current;
        CDI_SELECT = select;
        INSTANCE_GET = get;
        INSTANCE_IS_UNSATISFIED = isUnsatisfied;
        INSTANCE_IS_AMBIGUOUS = isAmbiguous;
        CDI_AVAILABLE = available;
    }

    private CdiResolver() {}

    /**
     * Résout une instance de {@code beanClass} via CDI si disponible, sinon via
     * le constructeur sans-arg.
     */
    @SuppressWarnings("unchecked")
    static <T> T resolve(Class<T> beanClass) throws ReflectiveOperationException {
        // Pré-check static : ne tenter CDI que si la classe est explicitement
        // un managed bean (annotée @*Scoped, @Singleton, ou ayant @Inject).
        // Sans ce filtre, un container CDI avec bean-discovery-mode=all peut
        // produire des instances "synthétiques" pour des classes ordinaires
        // (Deserializer/Adapter state-free du TCK) et altérer l'état du parser
        // dans les chemins critiques.
        if (CDI_AVAILABLE && isLikelyManagedBean(beanClass)) {
            try {
                Object cdi = CDI_CURRENT.invoke();
                Object inst = CDI_SELECT.invoke(cdi, beanClass, EMPTY_QUALIFIERS);
                boolean unsatisfied = (boolean) INSTANCE_IS_UNSATISFIED.invoke(inst);
                boolean ambiguous = (boolean) INSTANCE_IS_AMBIGUOUS.invoke(inst);
                if (!unsatisfied && !ambiguous) {
                    Object bean = INSTANCE_GET.invoke(inst);
                    if (bean != null) return (T) bean;
                }
            } catch (Throwable t) {
                // Container non démarré → fallback newInstance.
            }
        }
        return newInstanceFallback(beanClass);
    }

    /**
     * Heuristique conservatrice : la classe est un managed bean si elle porte
     * une annotation {@code jakarta.enterprise.context.*Scoped},
     * {@code jakarta.inject.Singleton}, ou si l'un de ses membres porte
     * {@code jakarta.inject.Inject} (field, constructor, ou setter).
     */
    private static boolean isLikelyManagedBean(Class<?> cls) {
        for (var ann : cls.getAnnotations()) {
            String n = ann.annotationType().getName();
            if (n.startsWith("jakarta.enterprise.context.") && n.endsWith("Scoped")) return true;
            if ("jakarta.inject.Singleton".equals(n)) return true;
        }
        for (var f : cls.getDeclaredFields()) {
            for (var ann : f.getAnnotations()) {
                if ("jakarta.inject.Inject".equals(ann.annotationType().getName())) return true;
            }
        }
        for (var ctor : cls.getDeclaredConstructors()) {
            for (var ann : ctor.getAnnotations()) {
                if ("jakarta.inject.Inject".equals(ann.annotationType().getName())) return true;
            }
        }
        for (var m : cls.getDeclaredMethods()) {
            for (var ann : m.getAnnotations()) {
                if ("jakarta.inject.Inject".equals(ann.annotationType().getName())) return true;
            }
        }
        return false;
    }

    private static <T> T newInstanceFallback(Class<T> beanClass) throws ReflectiveOperationException {
        var ctor = beanClass.getDeclaredConstructor();
        try { ctor.setAccessible(true); } catch (Exception ignore) {}
        return ctor.newInstance();
    }
}
