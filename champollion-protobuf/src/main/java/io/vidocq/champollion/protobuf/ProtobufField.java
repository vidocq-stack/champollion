package io.vidocq.champollion.protobuf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Métadonnée d'un record component participant à un message Protocol Buffers.
 *
 * <p>{@link #number()} est le {@code field_number} du wire format
 * (1..2^29-1, voir {@link WireFormat#MAX_FIELD_NUMBER}). {@link #type()} lève
 * l'ambiguïté entre encodages possibles d'un même Java type (ex. {@code int}
 * peut être INT32, UINT32, SINT32, FIXED32 ou SFIXED32).</p>
 *
 * <p>Pour les champs {@code repeated} (Java {@code List<X>}), {@link #packed()}
 * contrôle l'encodage (packed par défaut en proto3 pour les types scalaires
 * packables, voir {@link FieldType#packable()}).</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
public @interface ProtobufField {

    int number();

    FieldType type();

    /**
     * Encodage des {@code repeated <scalar>}. Ignoré pour les champs non répétés
     * ou non-packables. {@code true} par défaut (conforme à proto3 / Editions 2023).
     */
    boolean packed() default true;

    /**
     * {@code features.field_presence = EXPLICIT} (proto2 / Edition 2023 override) —
     * sérialise le champ même si sa valeur est égale au default proto3 (chaîne vide,
     * 0, false). {@code false} par défaut (proto3 IMPLICIT : omet les défauts).
     *
     * <p>{@code null} reste toujours omis : ce flag distingue {@code default} de
     * {@code absent}, pas l'inverse.</p>
     */
    boolean explicitPresence() default false;

    /**
     * Nom du groupe {@code oneof} si ce champ en fait partie. Plusieurs champs
     * avec la même valeur non-vide forment un oneof. Vide par défaut (pas dans
     * un oneof).
     *
     * <p>Effet : le parser JSON ({@code ProtobufJson.fromJson}) rejette
     * explicitement deux property keys du même groupe (spec proto3 JSON §oneof).
     * Le wire format n'est pas concerné — il autorise déjà à transporter
     * plusieurs champs d'un oneof, last-wins (spec proto3 §oneof).</p>
     *
     * <p>Les valeurs JSON {@code null} sont exclues du tracker (cohérent avec
     * "null = absent" §JSON canonical proto3).</p>
     */
    String oneofGroup() default "";
}
