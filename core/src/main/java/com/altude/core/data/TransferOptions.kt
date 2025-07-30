package com.altude.core.data

import foundation.metaplex.rpc.Commitment

data class TransferOptions (
    override val account: String = "",
    override val toAddress: String,
    override val amount: Double,
    override val token: String,
    override val commitment: Commitment = Commitment.finalized
) : SendOption {

}