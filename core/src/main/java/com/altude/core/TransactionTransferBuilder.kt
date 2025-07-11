package com.altude.core

import android.util.Base64
import com.altude.core.model.MintLayout
import com.altude.core.model.SendOptions
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.Message
import com.solana.transaction.Transaction
import com.solana.transaction.TransactionInstruction
import diglol.crypto.Ed25519
import diglol.crypto.KeyPair
import foundation.metaplex.rpc.RPC
import foundation.metaplex.solanaeddsa.Keypair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import foundation.metaplex.solanapublickeys.PublicKey
import org.bouncycastle.crypto.signers.Ed25519Signer


object TransactionTransferBuilder {

    suspend fun TransferTokenTransaction(
        option: SendOptions

    ): String = withContext(Dispatchers.IO) {
        val rpc = RPC("https://cold-holy-dinghy.solana-devnet.quiknode.pro/8cc52fd5faf72bedbc72d9448fba5640cd728ace/")
        val feePayerPubKeypair = SolanaPublicKey.from("chenGqdufWByiUyxqg7xEhUVMqF3aS9sxYLSzDNmwqu")
        val privateKeyBytes = byteArrayOf(
            235.toByte(), 144.toByte(), 12, 215.toByte(), 112, 178.toByte(), 249.toByte(), 227.toByte(), 180.toByte(), 112, 121, 214.toByte(), 13, 190.toByte(), 158.toByte(), 91,
            208.toByte(), 118, 253.toByte(), 192.toByte(), 48, 6, 252.toByte(), 37, 111, 169.toByte(), 209.toByte(), 238.toByte(), 174.toByte(), 78, 210.toByte(), 184.toByte(),
            9, 37, 75, 1, 98, 80, 44, 48, 119, 25, 193.toByte(), 156.toByte(), 161.toByte(), 185.toByte(), 250.toByte(), 119,
            160.toByte(), 54, 62, 93, 4, 130.toByte(), 200.toByte(), 226.toByte(), 100, 255.toByte(), 215.toByte(), 170.toByte(), 26, 226.toByte(), 213.toByte(), 28
        );
        val feePayer = KeyPair(feePayerPubKeypair.bytes, privateKeyBytes)


        // 🔹 Fetch decimals from the token mint
        val decimals = getTokenDecimals(rpc, option.mint)

        // 🔹 Create transfer instruction with proper decimals
        val instruction = createSplTokenTransfer(
            sourceAta = option.source,
            destinationAta = option.destination,
            feePayer = feePayer.toString(),
            mint = option.mint,
            amount = option.amount,
            decimals = decimals
        )

        // 🔹 Build message
        val message = Message.Builder()
            .addInstruction(instruction)
            .build()

        // 🔹 Serialize and return Base64 string
        val sign = SignTransaction(feePayer.publicKey,message)



        Base64.encodeToString(sign.serialize(), Base64.NO_WRAP)
    }

    fun createSplTokenTransfer(
        sourceAta: String,
        destinationAta: String,
        feePayer: String,
        mint: String,
        amount: Double,
        decimals: Int
    ): TransactionInstruction {
        val data = byteArrayOf(
            12, // transferChecked instruction index
            amount.toULong().toByte(), // amount in lamports
            decimals.toByte()
        )

        return TransactionInstruction(
            programId = SolanaPublicKey.from("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"),
            accounts = listOf(
                AccountMeta(SolanaPublicKey.from(sourceAta), isSigner = false, isWritable = true),
                AccountMeta(SolanaPublicKey.from(mint), isSigner = false, isWritable = false),
                AccountMeta(SolanaPublicKey.from(destinationAta), isSigner = false, isWritable = true),
                AccountMeta(SolanaPublicKey.from(feePayer), isSigner = true, isWritable = false),
            ),
            data = data
        )
    }
    fun Long.toLittleEndian(size: Int): ByteArray {
        return ByteArray(size) { i ->
            ((this shr (8 * i)) and 0xFF).toByte()
        }
    }
    suspend fun getTokenDecimals(
        rpc: RPC,
        mintAddress: String
    ): Int = withContext(Dispatchers.IO) {
        val pubkey = PublicKey(mintAddress)
        val accountInfo = rpc.getAccountInfo<MintLayout>(
            pubkey,
            null,
            MintLayout.serializer()
        )

        val decimals = accountInfo?.data?.decimals?.toInt()
            ?: throw Exception("Failed to get decimals from token mint layout")

        decimals
    }

    suspend fun SignTransaction(
        privateKeyBytes: ByteArray,
        message: Message
    ) : Transaction {

        val keyPair = Ed25519.generateKeyPair(privateKeyBytes)
        val signer = object : Ed25519Signer() {
            val publicKey: ByteArray get() = keyPair.publicKey
            suspend fun signPayload(payload: ByteArray): ByteArray = Ed25519.sign(keyPair, payload)
        }
        val signature = signer.signPayload(message.serialize());
        // Build Transaction
        val transaction = Transaction(listOf(signature), message)
        return  transaction;
    }
}

