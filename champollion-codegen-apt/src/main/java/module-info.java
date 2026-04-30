import javax.annotation.processing.Processor;

module io.vidocq.champollion.codegen.apt {
    requires java.compiler;
    requires io.vidocq.champollion.api;

    exports io.vidocq.champollion.codegen.apt;

    provides Processor with io.vidocq.champollion.codegen.apt.JsonbStaticProcessor;
}
