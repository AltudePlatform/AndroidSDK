package com.altude.core.model

import com.altude.core.helper.Mnemonic
import foundation.metaplex.solanaeddsa.Keypair
import foundation.metaplex.solanaeddsa.SolanaEddsa
import io.provenance.hdwallet.bip39.MnemonicWords

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.text.Normalizer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.collections.copyOfRange


import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.MnemonicException
import org.bouncycastle.crypto.DerivationFunction
import org.bouncycastle.crypto.DerivationParameters
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import kotlin.text.toHexString
// For Solana KeyPair (FROM foundation.metaplex.solanaeddsa WHICH IS PART OF/USED BY foundation.metaplex:solana)


object KeyPair {

    suspend fun generate(): Keypair{
        return SolanaEddsa.generateKeypair()
    }
    suspend fun solanaKeyPairFromPrivateKey(privateKeyByteArray: ByteArray): SolanaKeypair{
        val keypair = SolanaEddsa.createKeypairFromSecretKey(privateKeyByteArray.copyOfRange(0,32))
        return SolanaKeypair(keypair.publicKey,keypair.secretKey)
    }
    suspend fun solanaKeyPairFromMnemonic(mnemonic: String, passphrase: String = "") : SolanaKeypair{
        val seed = Mnemonic(mnemonic, passphrase).getKeyPair()
        return seed
    }
    private fun seedToKeyPair(seed: ByteArray): Pair<ByteArray, ByteArray> {
        val privateKey = Ed25519PrivateKeyParameters(seed.copyOfRange(0, 32), 0)
        val publicKey = privateKey.generatePublicKey().encoded
        val secretKey = privateKey.encoded // 32 bytes

        return Pair(publicKey, secretKey)
    }

    private fun mnemonicToSeed(mnemonic: String, passphrase: String = ""): ByteArray {
        val normalizedMnemonic = Normalizer.normalize(mnemonic, Normalizer.Form.NFKD)
        val normalizedSalt = Normalizer.normalize("mnemonic$passphrase", Normalizer.Form.NFKD)

        val keySpec = PBEKeySpec(
            normalizedMnemonic.toCharArray(),
            normalizedSalt.toByteArray(Charsets.UTF_8),
            2048,
            512
        )

        val secretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        return secretKeyFactory.generateSecret(keySpec).encoded
    }



    // Helper extension function (can be top-level or in a utility class)
    //fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }



//    fun mymnemonic(){
//        Bip39PhraseUseCase.mnemonic =
//        Bip39
//
//    }

}


