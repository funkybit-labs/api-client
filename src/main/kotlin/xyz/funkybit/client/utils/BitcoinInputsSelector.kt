package xyz.funkybit.client.utils

import xyz.funkybit.client.model.UnspentUtxo
import java.math.BigInteger

private typealias InputShuffleFn = (List<UnspentUtxo>) -> List<UnspentUtxo>

enum class SelectionStrategy {
    Single,
    RandomDraw,
}

class BitcoinInputsSelector(
    private val iterations: Int = 10,
    private val shuffleInputs: InputShuffleFn = { it.shuffled() },
) {
    fun selectInputs(
        amount: BigInteger,
        availableInputs: List<UnspentUtxo>,
        fee: BigInteger,
        strategy: SelectionStrategy = SelectionStrategy.RandomDraw,
    ): List<UnspentUtxo> {
        val totalAvailable = availableInputs.sumOf { it.amount }

        if (totalAvailable < amount + fee) {
            throw BitcoinInsufficientFundsException("Insufficient funds, needed ${amount + fee}, but only $totalAvailable BTC available")
        }

        if (strategy == SelectionStrategy.Single) {
            singleUtxo(amount, availableInputs, fee)?.let {
                return it.inputs
            }
        }

        val selectionCandidates =
            (1..iterations).mapNotNull {
                singleRandomDraw(amount, availableInputs, fee)
            }

        return selectionCandidates
            .minByOrNull { it.amountLocked }
            ?.inputs
            ?: throw BitcoinInsufficientFundsException(
                "Insufficient funds, needed ${amount + fee} including fee, but only $totalAvailable BTC available",
            )
    }

    private data class InputsSelectionCandidate(
        val inputs: List<UnspentUtxo>,
        val amountLocked: BigInteger,
    )

    // see https://murch.one/wp-content/uploads/2016/11/erhardt2016coinselection.pdf
    private fun singleRandomDraw(
        requestedAmount: BigInteger,
        availableInputs: List<UnspentUtxo>,
        fee: BigInteger,
    ): InputsSelectionCandidate? {
        val selectedInputs = mutableListOf<UnspentUtxo>()
        var selectedAmount = BigInteger.ZERO

        shuffleInputs(availableInputs).forEach { input ->
            selectedInputs.add(input)
            selectedAmount += input.amount

            if (selectedAmount >= requestedAmount + fee) {
                return InputsSelectionCandidate(selectedInputs, selectedAmount)
            }
        }

        return null
    }

    private fun singleUtxo(
        requestedAmount: BigInteger,
        availableInputs: List<UnspentUtxo>,
        fee: BigInteger,
    ): InputsSelectionCandidate? =
        availableInputs.sortedBy { it.amount }.firstOrNull { it.amount >= requestedAmount + fee }?.let {
            InputsSelectionCandidate(listOf(it), it.amount)
        }
}

open class BitcoinInsufficientFundsException(
    message: String,
) : Exception(message)
