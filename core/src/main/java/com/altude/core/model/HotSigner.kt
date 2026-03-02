package com.altude.core.model

import com.metaplex.signer.Signer
import foundation.metaplex.solanaeddsa.Keypair
import foundation.metaplex.solanaeddsa.SolanaEddsa
import foundation.metaplex.solanapublickeys.PublicKey

class HotSigner(private val keyPair: Keypair) : TransactionSigner {
    override val publicKey: PublicKey = keyPair.publicKey
    override suspend fun signMessage(message: ByteArray): ByteArray = SolanaEddsa.sign(message, keyPair)
}

class EmptySignature(override val publicKey: PublicKey) : Signer {
    override suspend fun signMessage(message: ByteArray): ByteArray = ByteArray(64){0}
}