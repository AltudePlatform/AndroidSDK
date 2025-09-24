package com.altude.gasstation.data

import com.altude.gasstation.data.ISendOption
import com.altude.gasstation.data.Token
import com.altude.gasstation.data.Commitment

data class SendOptions (
    override val account: String = "",
    override val toAddress: String,
    override val amount: Double,
    override val token: String = Token.KIN.mint(),
    override val commitment: Commitment = Commitment.finalized
) : ISendOption {

}