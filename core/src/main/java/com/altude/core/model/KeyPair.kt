package com.altude.core.model

import foundation.metaplex.solanaeddsa.Keypair
import foundation.metaplex.solanaeddsa.SolanaEddsa
import foundation.metaplex.solanapublickeys.PublicKey
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters



object KeyPair {
    suspend fun generate(): Keypair{
        return SolanaEddsa.generateKeypair()
    }
    suspend fun solanaKeyPairFromPrivateKey(privateKeyByteArray: ByteArray): SolanaKeypair{
        val keypair = SolanaEddsa.createKeypairFromSecretKey(privateKeyByteArray)
        return SolanaKeypair(keypair.publicKey,keypair.secretKey)
    }
}