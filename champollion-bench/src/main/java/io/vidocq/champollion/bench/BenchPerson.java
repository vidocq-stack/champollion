package io.vidocq.champollion.bench;

import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.Message;
import io.vidocq.champollion.protobuf.ProtobufField;
import io.vidocq.champollion.protobuf.ProtobufMessage;
import io.vidocq.champollion.protobuf.ProtobufStatic;

import java.util.List;

/**
 * Record de bench pour {@link ProtobufBenchmark} — top-level pour que l'APT
 * {@code ProtobufStaticProcessor} génère ses sources dans
 * {@code io.vidocq.champollion.bench} sans collision de package
 * (un type nested produirait un sous-package {@code .ProtobufBenchmark} qui
 * clashe avec la classe parente).
 */
@ProtobufStatic
@ProtobufMessage("bench.BenchPerson")
public record BenchPerson(
        @ProtobufField(number = 1, type = FieldType.STRING) String name,
        @ProtobufField(number = 2, type = FieldType.INT32) int age,
        @ProtobufField(number = 3, type = FieldType.STRING) List<String> tags,
        @ProtobufField(number = 4, type = FieldType.BOOL) boolean active,
        @ProtobufField(number = 5, type = FieldType.INT64) long sequence
) implements Message {}
