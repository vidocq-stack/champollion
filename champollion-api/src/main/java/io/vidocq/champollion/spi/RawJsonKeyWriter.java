package io.vidocq.champollion.spi;

/**
 * SPI optionnelle implémentée par les {@link jakarta.json.stream.JsonGenerator}
 * Champollion pour permettre aux outils de codegen statique de bypasser l'escape
 * RFC 8259 §7 sur les noms de propriétés connus à la compilation.
 *
 * <p>Le contrat : {@link #writeKeyRaw(String)} accepte une chaîne déjà entourée
 * de guillemets et déjà escape, et l'écrit telle quelle dans le flux, en gérant
 * la virgule de séparation et le {@code :} comme {@link
 * jakarta.json.stream.JsonGenerator#writeKey(String)}.</p>
 *
 * <p>Usage typique côté binding statique généré :</p>
 * <pre>{@code
 * private static final String K_x = "\"x\"";  // pré-encoded à la compile
 *
 * public void write(JsonGenerator g, Coord v) {
 *     g.writeStartObject();
 *     if (g instanceof RawJsonKeyWriter r) {
 *         r.writeKeyRaw(K_x);
 *         g.write(v.x());
 *     } else {
 *         g.write("x", v.x());
 *     }
 *     g.writeEnd();
 * }
 * }</pre>
 */
public interface RawJsonKeyWriter {

    /**
     * Écrit un nom de propriété pré-encodé dans le flux.
     *
     * @param preQuotedKey la chaîne {@code "<name>"} avec guillemets et escape
     *                     RFC 8259 §7 déjà appliqué
     */
    void writeKeyRaw(String preQuotedKey);
}
