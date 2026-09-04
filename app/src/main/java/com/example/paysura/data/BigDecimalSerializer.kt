package com.example.paysura.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal

object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): BigDecimal {
        // We might receive numbers as double or string in json, so decode as string or double?
        // In kotlinx-serialization, we can decodeDouble or decodeString based on the JSON. 
        // Wait, if it's sent as a number, we probably should decode it as Double or string?
        // Actually, JsonDecoder has decodeJsonElement. Let's just decodeString, or use Double for primitives.
        // Or decodeDouble().toString() to BigDecimal.
        return BigDecimal(decoder.decodeDouble().toString())
    }

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        // Output as number, so encodeDouble
        encoder.encodeDouble(value.toDouble())
    }
}
