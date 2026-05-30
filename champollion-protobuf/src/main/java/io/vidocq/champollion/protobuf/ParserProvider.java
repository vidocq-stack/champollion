package io.vidocq.champollion.protobuf;

/**
 * SPI {@link java.util.ServiceLoader} to provide precompiled {@link Parser}
 * instances (APT static mode).
 *
 * <p>Each module that adds classes annotated {@link ProtobufStatic}
 * contributes an implementation of {@code ParserProvider} via the service file
 * {@code META-INF/services/io.vidocq.champollion.protobuf.ParserProvider}.</p>
 *
 * <p>{@link Protobuf#parser(Class)} consults providers in {@link ServiceLoader}
 * order. The first one that returns a non-null parser is used.
 * If none match, the M1.3 reflective runtime takes over.</p>
 */
public interface ParserProvider {

    /**
     * @return a {@link Parser} for {@code type}, or {@code null} if this
     * provider cannot handle it.
     */
    <T> Parser<T> parserFor(Class<T> type);
}
