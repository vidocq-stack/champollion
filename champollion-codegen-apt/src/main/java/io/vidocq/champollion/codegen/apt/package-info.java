/**
 * Processeur d'annotations {@code @JsonbStatic} : pour chaque type cible,
 * génère {@code <FQN>$$Binding} (writer + reader) et un fournisseur ServiceLoader
 * pour {@code BindingFactoryProvider}.
 */
package io.vidocq.champollion.codegen.apt;
