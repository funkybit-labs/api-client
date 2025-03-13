package xyz.funkybit.client.model

import kotlinx.serialization.Serializable

@Serializable
data class ApiError(
    val reason: ReasonCode,
    val message: String,
    val displayMessage: String = message,
)

@Serializable
data class ApiErrors(
    val errors: List<ApiError>,
)

@Serializable
enum class ReasonCode {
    OrderNotFound,
    WithdrawalNotFound,
    DepositNotFound,
    MarketNotFound,
    CoinNotFound,
    CoinNotReady,
    UserNotFound,
    BatchSizeExceeded,
    MaxPoolsLimitExceeded,
    LiquidityTransferError,
    SignatureNotValid,
    UnexpectedError,
    AuthenticationError,
    ProcessingError,
    InvalidInviteCode,
    RejectedBySequencer,
    ChainNotSupported,
    CaptchaRequired,
    MarketIsClosed,
    PoolNotFound,
    InvalidContractAddress,
}

data class ApiCallFailure(
    val httpCode: Int,
    val error: ApiError?,
)
