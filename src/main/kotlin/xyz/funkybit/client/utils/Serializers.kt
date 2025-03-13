package xyz.funkybit.client.utils

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.BigInteger

// kotlin does not have default serializers for BigDecimal/BigInteger type
// Adding serializers and type aliases for convenience
typealias BigDecimalJson =
    @Serializable(with = BigDecimalSerializer::class)
    BigDecimal

typealias BigIntegerJson =
    @Serializable(with = BigIntegerSerializer::class)
    BigInteger

object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("java.math.BigDecimal", PrimitiveKind.DOUBLE)

    /**
     * If decoding JSON uses [JsonDecoder.decodeJsonElement] to get the raw content,
     * otherwise decodes using [Decoder.decodeString].
     */
    override fun deserialize(decoder: Decoder): BigDecimal =
        when (decoder) {
            is JsonDecoder ->
                decoder
                    .decodeJsonElement()
                    .jsonPrimitive.content
                    .toBigDecimal()
            else -> decoder.decodeString().toBigDecimal()
        }

    /**
     * [value] is encoded using [Encoder.encodeString].
     */
    override fun serialize(
        encoder: Encoder,
        value: BigDecimal,
    ) = encoder.encodeString(value.toPlainString())
}

object BigIntegerSerializer : KSerializer<BigInteger> {
    override val descriptor = PrimitiveSerialDescriptor("java.math.BigInteger", PrimitiveKind.LONG)

    /**
     * If decoding JSON uses [JsonDecoder.decodeJsonElement] to get the raw content,
     * otherwise decodes using [Decoder.decodeString].
     */
    override fun deserialize(decoder: Decoder): BigInteger =
        when (decoder) {
            is JsonDecoder ->
                decoder
                    .decodeJsonElement()
                    .jsonPrimitive.content
                    .toBigInteger()
            else -> decoder.decodeString().toBigInteger()
        }

    /**
     * [value] is encoded using [Encoder.encodeString].
     */
    override fun serialize(
        encoder: Encoder,
        value: BigInteger,
    ) = encoder.encodeString(value.toString())
}
