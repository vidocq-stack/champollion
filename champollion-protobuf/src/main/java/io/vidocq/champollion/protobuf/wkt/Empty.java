package io.vidocq.champollion.protobuf.wkt;

import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufMessage;

/**
 * {@code google.protobuf.Empty} — message sans champ. JSON canonical : {@code {}}.
 *
 * <p>Spec : <a href="https://protobuf.dev/reference/protobuf/google.protobuf/#empty">Empty</a>.</p>
 */
@ProtobufMessage("google.protobuf.Empty")
public record Empty() implements Message {
    public static final Empty INSTANCE = new Empty();
}
