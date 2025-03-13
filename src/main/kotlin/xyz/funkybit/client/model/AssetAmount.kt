package xyz.funkybit.client.model

import xyz.funkybit.client.utils.fromFundamentalUnits
import xyz.funkybit.client.utils.toFundamentalUnits
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

data class AssetAmount(
    val symbol: SymbolInfo,
    val amount: BigDecimal,
) {
    constructor(symbol: SymbolInfo, amount: String) : this(symbol, BigDecimal(amount).setScale(symbol.decimals.toInt()))
    constructor(
        symbol: SymbolInfo,
        amount: BigInteger,
    ) : this(symbol, amount.fromFundamentalUnits(symbol.decimals).setScale(symbol.decimals.toInt()))

    val inFundamentalUnits: BigInteger =
        amount.toFundamentalUnits(symbol.decimals)

    operator fun plus(other: AssetAmount): AssetAmount {
        require(symbol.name == other.symbol.name) { "Both amounts must be of same asset" }
        return AssetAmount(symbol, amount + other.amount)
    }

    operator fun minus(other: AssetAmount): AssetAmount {
        require(symbol.name == other.symbol.name) { "Both amounts must be of same asset" }
        return AssetAmount(symbol, amount - other.amount)
    }

    operator fun times(other: BigDecimal): AssetAmount =
        AssetAmount(symbol, (amount * other).setScale(symbol.decimals.toInt(), RoundingMode.FLOOR))

    operator fun div(other: BigDecimal) =
        AssetAmount(
            symbol,
            (amount / other).setScale(symbol.decimals.toInt(), RoundingMode.FLOOR),
        )
}

fun Iterable<AssetAmount>.sum(): AssetAmount = reduce { acc, next -> acc + next }

fun BigDecimal.ofAsset(symbol: SymbolInfo): AssetAmount = AssetAmount(symbol, this.setScale(symbol.decimals.toInt()))
