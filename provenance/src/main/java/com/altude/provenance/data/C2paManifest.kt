package com.altude.provenance.data

import androidx.exifinterface.media.ExifInterface
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * A lightweight C2PA-style content credential manifest (POC).
 *
 * Mirrors the C2PA "claim" structure:
 * https://c2pa.org/specifications/specifications/2.0/specs/C2PA_Specification.html
 *
 * No external C2PA library required — uses only [MessageDigest] + kotlinx.serialization.
 *
 * The [manifestHash] is the value stored on-chain via `Provenance.attestImageHash`.
 * The full [toJson] claim is sent to the backend for off-chain verification.
 *
 * How it works:
 * ```
 * imageFile bytes → SHA-256 → assetHash
 * claim JSON (without manifestHash) → SHA-256 → manifestHash  ← stored on-chain
 * ```
 *
 * Usage:
 * ```kotlin
 * val manifest = C2paManifest.build(
 *     filePath  = "/data/user/0/com.example/files/photo.png",
 *     mimeType  = "image/png",
 *     producer  = walletAddress
 * )
 * val payload = ImageHashPayload.fromManifest(manifest)
 * val result  = Provenance.attestImageHash(payload)
 * ```
 */
@Serializable
data class C2paManifest(
    /** C2PA claim type — always "c2pa.hash.data" for this POC. */
    val claimType: String = "c2pa.hash.data",
    /** SHA-256 hex of the raw image file bytes. */
    val assetHash: String,
    /** MIME type of the asset (e.g. "image/png"). */
    val mimeType: String,
    /** Original filename, derived from the file path. */
    val filename: String = "",
    /** Who produced/captured this asset (e.g. wallet address or app name). */
    val producer: String = "",
    /** Software agent that created this manifest. */
    val softwareAgent: String = "altude-provenance-sdk",
    /** Unix epoch (seconds) when the manifest was created. */
    val timestamp: Long = System.currentTimeMillis() / 1000,
    /**
     * SHA-256 hex of the canonical manifest JSON (without this field).
     * This is the value stored on-chain — a tamper-evident hash of the full claim.
     */
    val manifestHash: String = "",
    /**
     * Solana Attestation PDA (Base58) returned after a successful on-chain attestation.
     * Stored here so a verifier reading only the sidecar/embedded manifest can call
     * [Provenance.verifyOnChain] without any backend or extra state.
     * Empty string when the attestation has not yet been submitted (offline queue).
     */
    val attestationId: String = ""
) {
    companion object {

        private val canonicalJson = Json {
            encodeDefaults = true
            explicitNulls  = false
        }

        // Used specifically for computing manifestHash; omits default-valued fields
        private val hashingJson = Json {
            encodeDefaults = false
            explicitNulls  = false
        }

        /**
         * Builds a [C2paManifest] from a file path.
         *
         * Steps:
         * 1. Read file bytes from [filePath]
         * 2. SHA-256 the raw bytes → [assetHash]
         * 3. Build canonical claim JSON (without [manifestHash])
         * 4. SHA-256 the claim JSON → [manifestHash] ← stored on-chain
         *
         * @param filePath  Absolute path to the image file.
         * @param mimeType  MIME type e.g. `"image/png"`, `"image/jpeg"`.
         * @param producer  Wallet address or producer identifier.
         * @param softwareAgent SDK identifier (default: `"altude-provenance-sdk"`).
         * @throws IllegalArgumentException if the file does not exist or cannot be read.
         */
        fun build(
            filePath: String,
            mimeType: String       = "image/png",
            producer: String       = "",
            softwareAgent: String  = "altude-provenance-sdk"
        ): C2paManifest {
            val file = File(filePath)
            require(file.exists()) { "C2PA: file not found at $filePath" }
            require(file.canRead()) { "C2PA: cannot read file at $filePath" }

            val imageBytes = file.readBytes()

            // Step 1 — hash the raw image file bytes
            val assetHash = sha256Hex(imageBytes)

            // Step 2 — build the draft claim without manifestHash
            val draft = C2paManifest(
                assetHash     = assetHash,
                mimeType      = mimeType,
                filename      = file.name,
                producer      = producer,
                softwareAgent = softwareAgent,
                timestamp     = System.currentTimeMillis() / 1000
            )

            // Step 3 — hash the canonical claim JSON (without manifestHash) → tamper-evident on-chain value
            val claimJson    = hashingJson.encodeToString(draft)
            val manifestHash = sha256Hex(claimJson.toByteArray(Charsets.UTF_8))

            return draft.copy(manifestHash = manifestHash)
        }

        /**
         * Builds a [C2paManifest] from raw image bytes (e.g. from a camera capture buffer).
         * Use [build] when the image is already saved to disk.
         *
         * @param imageBytes  Raw bytes of the image.
         * @param mimeType    MIME type e.g. `"image/png"`.
         * @param filename    Optional filename for the claim.
         * @param producer    Wallet address or producer identifier.
         */
        fun buildFromBytes(
            imageBytes: ByteArray,
            mimeType: String      = "image/png",
            filename: String      = "",
            producer: String      = "",
            softwareAgent: String = "altude-provenance-sdk"
        ): C2paManifest {
            val assetHash = sha256Hex(imageBytes)
            val draft = C2paManifest(
                assetHash     = assetHash,
                mimeType      = mimeType,
                filename      = filename,
                producer      = producer,
                softwareAgent = softwareAgent,
                timestamp     = System.currentTimeMillis() / 1000
            )
            val claimJson    = hashingJson.encodeToString(draft)
            val manifestHash = sha256Hex(claimJson.toByteArray(Charsets.UTF_8))
            return draft.copy(manifestHash = manifestHash)
        }

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Returns the canonical JSON of this manifest for backend storage / verification. */
    fun toJson(): String = canonicalJson.encodeToString(this)

    /**
     * Saves a `.c2pa.json` sidecar file named `{filename}.c2pa.json`.
     *
     * @param directory Directory to write into (e.g. `context.filesDir`).
     * @param json      JSON string to write. Defaults to [toJson] (manifest hashes only).
     *                  Pass `certificate.toJson()` to include the full ED25519 signature
     *                  so the file is self-contained for offline verification.
     * @return The written [File].
     */
    fun saveTo(directory: File, json: String = toJson()): File {
        directory.mkdirs()
        val stem = if (filename.isNotBlank()) filename else manifestHash.take(16)
        val out  = File(directory, "$stem.c2pa.json")
        out.writeText(json, Charsets.UTF_8)
        return out
    }

    /**
     * Embeds a C2PA manifest JSON directly into the image file metadata.
     *
     * - **JPEG** → written to XMP metadata via `ExifInterface` (`TAG_XMP`).
     *   The client only needs the image — no sidecar file required.
     * - **PNG**  → injected as a `tEXt` chunk with keyword `C2PA` before `IEND`.
     * - **Other formats** → throws [UnsupportedOperationException]; use [saveTo] instead.
     *
     * The file at [imageFile] is modified in-place.
     *
     * ```kotlin
     * // Embed manifest hashes only (default)
     * result.manifest.embedInto(File(filePath))
     *
     * // Embed full certificate with ED25519 signature (offline-verifiable)
     * result.manifest.embedInto(File(filePath), result.certificate!!.toJson())
     * ```
     *
     * @param imageFile The image file to embed into (must be JPEG or PNG).
     * @param json      JSON string to embed. Defaults to [toJson] (manifest hashes only).
     *                  Pass `certificate.toJson()` to include the full ED25519 signature.
     * @return The modified [imageFile] for chaining.
     */
    fun embedInto(imageFile: File, json: String = toJson()): File {
        require(imageFile.exists()) { "C2PA embed: file not found at ${imageFile.absolutePath}" }
        return when {
            mimeType.contains("jpeg", ignoreCase = true) ||
            mimeType.contains("jpg",  ignoreCase = true) ||
            imageFile.extension.lowercase() in listOf("jpg", "jpeg") -> embedJpeg(imageFile, json)

            mimeType.contains("png",  ignoreCase = true) ||
            imageFile.extension.lowercase() == "png" -> embedPng(imageFile, json)

            else -> throw UnsupportedOperationException(
                "C2PA embed: unsupported format '${imageFile.extension}'. Use saveTo() for a sidecar file."
            )
        }
    }

    // ── Embed helpers ─────────────────────────────────────────────────────────

    private fun embedJpeg(imageFile: File, json: String): File {
        // ExifInterface writes XMP in-place — standard Adobe/C2PA approach for JPEG
        val xmp = """
            <?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:c2pa="https://c2pa.org/ns/c2pa/">
                  <c2pa:manifest>${json.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</c2pa:manifest>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>
        """.trimIndent()
        ExifInterface(imageFile.absolutePath).apply {
            setAttribute(ExifInterface.TAG_XMP, xmp)
            saveAttributes()
        }
        return imageFile
    }

    private fun embedPng(imageFile: File, json: String): File {
        // Strip any existing tEXt chunk with keyword "C2PA", then inject a fresh one
        // before the IEND chunk, making embedding idempotent across retries/resubmits.
        val original = imageFile.readBytes()

        // Walk all PNG chunks and drop every tEXt chunk whose keyword is "C2PA"
        val stripped = ByteArrayOutputStream()
        stripped.write(original, 0, 8) // PNG signature
        var pos = 8
        while (pos + 12 <= original.size) {
            val length   = bytesToInt(original, pos)
            val typeStr  = String(original, pos + 4, 4, Charsets.US_ASCII)
            val chunkEnd = pos + 12 + length // 4 length + 4 type + data + 4 crc

            if (chunkEnd > original.size) break // truncated / corrupted chunk

            if (typeStr == "tEXt" && length >= 5) {
                // Keyword is the bytes before the first null separator
                val dataStart = pos + 8
                val nullIdx   = original.indexOf(0x00.toByte(), dataStart, dataStart + length)
                val keyword   = if (nullIdx >= 0) String(original, dataStart, nullIdx - dataStart, Charsets.ISO_8859_1) else ""
                if (keyword == "C2PA") {
                    pos = chunkEnd
                    continue
                }
            }
            stripped.write(original, pos, chunkEnd - pos)
            pos = chunkEnd
        }
        val strippedBytes = stripped.toByteArray()

        // Build a new tEXt chunk
        val keyword   = "C2PA"
        val text      = json.toByteArray(Charsets.UTF_8)
        val nullByte  = byteArrayOf(0x00)
        val chunkData = keyword.toByteArray(Charsets.UTF_8) + nullByte + text
        val chunkType = "tEXt".toByteArray(Charsets.UTF_8)
        val crcInput  = chunkType + chunkData
        val crc       = java.util.zip.CRC32().also { it.update(crcInput) }.value

        val chunk = ByteArrayOutputStream().apply {
            write(intToBytes(chunkData.size))   // length (4 bytes, big-endian)
            write(chunkType)                    // type  (4 bytes)
            write(chunkData)                    // data
            write(intToBytes(crc.toInt()))      // CRC   (4 bytes, big-endian)
        }.toByteArray()

        // Insert new chunk before IEND (last 12 bytes of a valid PNG)
        val iendOffset = strippedBytes.size - 12
        val patched = strippedBytes.copyOfRange(0, iendOffset) +
                      chunk +
                      strippedBytes.copyOfRange(iendOffset, strippedBytes.size)

        imageFile.writeBytes(patched)
        return imageFile
    }

    /** Finds the first occurrence of [byte] in [array] within [fromIndex]..[toIndex). */
    private fun ByteArray.indexOf(byte: Byte, fromIndex: Int, toIndex: Int): Int {
        for (i in fromIndex until minOf(toIndex, size)) if (this[i] == byte) return i
        return -1
    }

    private fun bytesToInt(array: ByteArray, offset: Int): Int =
        ((array[offset].toInt() and 0xFF) shl 24) or
        ((array[offset + 1].toInt() and 0xFF) shl 16) or
        ((array[offset + 2].toInt() and 0xFF) shl 8) or
        (array[offset + 3].toInt() and 0xFF)

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value shr 24 and 0xFF).toByte(),
        (value shr 16 and 0xFF).toByte(),
        (value shr  8 and 0xFF).toByte(),
        (value        and 0xFF).toByte()
    )
}

