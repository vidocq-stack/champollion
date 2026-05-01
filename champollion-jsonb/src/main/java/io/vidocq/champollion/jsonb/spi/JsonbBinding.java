package io.vidocq.champollion.jsonb.spi;

import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;

/**
 * Binding statique d'un type Java vers / depuis JSON. Une instance par type cible.
 *
 * <p>Les bindings sont produits soit :</p>
 * <ul>
 *   <li>par l'APT {@code champollion-codegen-apt} pour les classes annotées
 *       {@code @JsonbStatic} ou scannées par {@code champollion-codegen-maven-plugin} ;</li>
 *   <li>à la main, pour des cas spécifiques (ex : adaptation d'un type tiers
 *       qu'on ne peut pas annoter ni recompiler).</li>
 * </ul>
 *
 * <p>Les implémentations sont découvertes via {@link java.util.ServiceLoader} sur
 * cette interface et utilisées en lookup-first par {@code ChampollionJsonb}, avec
 * fallback automatique vers le runtime introspectif quand aucun binding statique
 * n'est disponible pour un type donné.</p>
 *
 * <p><b>Contrats sur {@link #write} :</b> à l'appel, le {@link JsonGenerator} est
 * positionné juste avant l'émission de la valeur. L'implémentation doit émettre
 * exactement <em>une</em> valeur JSON complète (objet, tableau, scalaire) et rendre
 * la main au générateur dans un état correct.</p>
 *
 * <p><b>Contrats sur {@link #read} :</b> à l'appel, le {@link JsonParser} est
 * positionné juste avant l'événement de tête de la valeur. L'implémentation doit
 * appeler {@link JsonParser#next()} elle-même pour consommer la valeur, et rendre
 * la main après avoir consommé exactement une valeur JSON complète.</p>
 *
 * @param <T> le type Java cible
 */
public interface JsonbBinding<T> {

    /** Le type Java pour lequel ce binding est canonique. */
    Class<T> type();

    /** Sérialise {@code value} dans {@code generator}. */
    void write(JsonGenerator generator, T value);

    /** Désérialise une valeur depuis {@code parser}. */
    T read(JsonParser parser);
}
