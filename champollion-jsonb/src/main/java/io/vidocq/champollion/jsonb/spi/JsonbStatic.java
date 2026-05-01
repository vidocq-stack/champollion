package io.vidocq.champollion.jsonb.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marque un type pour la génération d'un {@link JsonbBinding} à la compilation
 * par {@code champollion-codegen-apt}.
 *
 * <p>Le binding généré sera enregistré comme service {@link JsonbBinding} via
 * un fichier {@code META-INF/services/io.vidocq.champollion.jsonb.spi.JsonbBinding}
 * et donc utilisé en lookup-first par {@code ChampollionJsonb}, sans aucune
 * réflexion à l'exécution.</p>
 *
 * <p>Rétention {@link RetentionPolicy#CLASS} : préservée dans le bytecode pour
 * permettre un éventuel scan post-compile par {@code champollion-codegen-maven-plugin}
 * sur les types annotés transitivement, sans nécessiter d'avoir le code source.</p>
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface JsonbStatic {
}
