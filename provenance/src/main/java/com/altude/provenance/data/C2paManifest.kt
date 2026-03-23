package com.altude.provenance.data

import android.graphics.BitmapFactory
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
    val manifestHash: String = ""
) {
    companion object {

        private val canonicalJson = Json {
            encodeDefaults = true
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

            // Step 3 — hash the canonical claim JSON → tamper-evident on-chain value
            val claimJson    = canonicalJson.encodeToString(draft)
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
            val claimJson    = canonicalJson.encodeToString(draft)
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
     * Saves the manifest JSON as a sidecar `.c2pa.json` file.
     * Named `{filename}.c2pa.json` (e.g. `photo.png.c2pa.json`).
     *
     * @param directory Directory to write into (e.g. `context.filesDir`).
     * @return The written [File].
     */
    fun saveTo(directory: File): File {
        directory.mkdirs()
        val stem = if (filename.isNotBlank()) filename else manifestHash.take(16)
        val out  = File(directory, "$stem.c2pa.json")
        out.writeText(toJson(), Charsets.UTF_8)
        return out
    }

    /**
     * Embeds the manifest JSON directly into the image file metadata.
     *
     * - **JPEG** → written to XMP metadata via `ExifInterface` (`TAG_XMP`).
     *   The client only needs the image — no sidecar file required.
     * - **PNG**  → injected as a `tEXt` chunk with keyword `C2PA` before `IEND`.
     * - **Other formats** → throws [UnsupportedOperationException]; use [saveTo] instead.
     *
     * The file at [imageFile] is modified in-place.
     *
     * ```kotlin
     * result.manifest.embedInto(File(filePath))
     * // imageFile now contains the C2PA manifest in its metadata
     * ```
     *
     * @param imageFile The image file to embed into (must be JPEG or PNG).
     * @return The modified [imageFile] for chaining.
     */
    fun embedInto(imageFile: File): File {
        require(imageFile.exists()) { "C2PA embed: file not found at ${imageFile.absolutePath}" }
        val json = toJson()
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
                  <c2pa:manifest>${json.replace("<", "&lt;").replace(">", "&gt;")}</c2pa:manifest>
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
        // Inject a tEXt chunk with keyword "C2PA" before the IEND chunk
        val original  = imageFile.readBytes()
        val keyword   = "C2PA"
        val text      = json.toByteArray(Charsets.UTF_8)
        val nullByte  = byteArrayOf(0x00)
        val chunkData = keyword.toByteArray(Charsets.UTF_8) + nullByte + text

        // CRC covers chunk type + chunk data
        val chunkType = "tEXt".toByteArray(Charsets.UTF_8)
        val crcInput  = chunkType + chunkData
        val crc       = java.util.zip.CRC32().also { it.update(crcInput) }.value

        val chunk = ByteArrayOutputStream().apply {
            write(intToBytes(chunkData.size))   // length (4 bytes, big-endian)
            write(chunkType)                    // type  (4 bytes)
            write(chunkData)                    // data
            write(intToBytes(crc.toInt()))      // CRC   (4 bytes, big-endian)
        }.toByteArray()

        // Find IEND chunk offset (last 12 bytes of a valid PNG)
        val iendOffset = original.size - 12
        val patched = original.copyOfRange(0, iendOffset) +
                      chunk +
                      original.copyOfRange(iendOffset, original.size)

        imageFile.writeBytes(patched)
        return imageFile
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value shr 24 and 0xFF).toByte(),
        (value shr 16 and 0xFF).toByte(),
        (value shr  8 and 0xFF).toByte(),
        (value        and 0xFF).toByte()
    )
}

