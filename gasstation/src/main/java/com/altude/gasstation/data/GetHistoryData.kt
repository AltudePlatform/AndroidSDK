package com.altude.gasstation.data


data class GetHistoryData(
    val transactions: List<TransactionItem>
)

data class TransactionItem(
    val slot: Long,
    val blockTime: Long,
    val transaction: TransactionData,
    val meta: Meta
)

data class TransactionData(
    val signatures: List<String>,
    val message: Message
)

data class Message(
    val accountKeys: List<String>,
    val header: Header,
    val recentBlockhash: String,
    val instructions: List<Instruction>
)

data class Header(
    val numRequiredSignatures: Int,
    val numReadonlySignedAccounts: Int,
    val numReadonlyUnsignedAccounts: Int
)

data class Instruction(
    val programIdIndex: Int,
    val accounts: List<Int>,
    val data: String
)

data class Meta(
    val err: Any?, // null or object
    val fee: Long,
    val preBalances: List<Long>,
    val postBalances: List<Long>,
    val innerInstructions: List<InnerInstructionWrapper>,
    val preTokenBalances: List<TokenBalance>,
    val postTokenBalances: List<TokenBalance>,
    val logMessages: List<String>
)

data class InnerInstructionWrapper(
    val index: Int,
    val instructions: List<Instruction>
)

data class TokenBalance(
    val accountIndex: Int,
    val mint: String,
    val uiTokenAmount: UiTokenAmount
)

data class UiTokenAmount(
    val amount: String,
    val decimals: Int,
    val uiAmount: Double?,  // nullable
    val uiAmountString: String,
    val amountUlong: Long,
    val amountDecimal: Double,
    val amountDouble: Double
)
