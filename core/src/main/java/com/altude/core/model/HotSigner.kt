package com.altude.core.model

import com.metaplex.signer.Signer
import foundation.metaplex.solanaeddsa.Keypair
import foundation.metaplex.solanaeddsa.SolanaEddsa
import foundation.metaplex.solanapublickeys.PublicKey

class HotSigner(private val keyPair: Keypair) : Signer {
    override val publicKey: PublicKey = keyPair.publicKey
    override suspend fun signMessage(message: ByteArray): ByteArray = SolanaEddsa.sign(message, keyPair)
}

class EmptySignature(publicKey: PublicKey) : Signer {
    override val publicKey: PublicKey = publicKey
    override suspend fun signMessage(message: ByteArray): ByteArray = ByteArray(64){0}
}