package com.altude.core.data

import com.altude.core.model.Token
import foundation.metaplex.rpc.Commitment

data class SendOptions (
    override val account: String = "",
    override val toAddress: String,
    override val amount: Double,
    override val token: String = Token.KIN.mint(),
    override val commitment: Commitment = Commitment.finalized
) : ISendOption {

}