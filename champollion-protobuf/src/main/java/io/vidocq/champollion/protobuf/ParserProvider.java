package io.vidocq.champollion.protobuf;

/**
 * SPI {@link java.util.ServiceLoader} pour fournir des {@link Parser}
 * pré-compilés (mode statique APT).
 *
 * <p>Chaque module qui ajoute des classes annotées {@link ProtobufStatic}
 * contribue un implémentation de {@code ParserProvider} via le service file
 * {@code META-INF/services/io.vidocq.champollion.protobuf.ParserProvider}.</p>
 *
 * <p>{@link Protobuf#parser(Class)} consulte les providers dans l'ordre du
 * ServiceLoader. Le premier qui retourne un parser non-null est utilisé.
 * Si aucun ne matche, le runtime reflectif M1.3 prend le relais.</p>
 */
public interface ParserProvider {

    /**
     * @return un {@link Parser} pour {@code type}, ou {@code null} si ce
     * provider ne sait pas le gérer.
     */
    <T> Parser<T> parserFor(Class<T> type);
}
