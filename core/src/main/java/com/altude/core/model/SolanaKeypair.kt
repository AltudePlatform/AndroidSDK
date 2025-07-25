package com.altude.core.model

import foundation.metaplex.solanaeddsa.Keypair
import foundation.metaplex.solanaeddsa.SolanaEddsa
import foundation.metaplex.solanapublickeys.PublicKey

class SolanaKeypair (
    override val publicKey: PublicKey,
    override val secretKey: ByteArray
) : Keypair
