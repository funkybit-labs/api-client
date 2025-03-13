package xyz.funkybit.client.utils

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

fun String.toFundamentalUnits(decimals: Int): BigInteger =
    this
        .replace("_", "")
        .toBigDecimal()
        .movePointRight(decimals)
        .toBigInteger()

fun String.toFundamentalUnits(decimals: UByte): BigInteger = this.toFundamentalUnits(decimals.toInt())

fun BigDecimal.toFundamentalUnits(decimals: Int): BigInteger = this.movePointRight(decimals).toBigInteger()

fun BigDecimal.toFundamentalUnits(decimals: UByte): BigInteger = this.toFundamentalUnits(decimals.toInt())

fun BigInteger.fromFundamentalUnits(decimals: Int): BigDecimal = BigDecimal(this).movePointLeft(decimals)

fun BigInteger.fromFundamentalUnits(decimals: UByte): BigDecimal = BigDecimal(this).movePointLeft(decimals.toInt())

fun BigDecimal.setScale(decimals: UByte): BigDecimal = this.setScale(decimals.toInt())

fun BigDecimal.setScale(
    decimals: UByte,
    roundingMode: RoundingMode,
): BigDecimal = this.setScale(decimals.toInt(), roundingMode)
