import javax.annotation.processing.Processor;

/**
 * Champollion annotation processor: generates a {@code <FQN>$$Binding} for
 * each type annotated with {@code @JsonbStatic}.
 */
module io.vidocq.champollion.codegen.apt {
    requires java.compiler;
    requires io.vidocq.champollion.jsonb;

    exports io.vidocq.champollion.codegen.apt;

    provides Processor with io.vidocq.champollion.codegen.apt.JsonbStaticProcessor;
}
