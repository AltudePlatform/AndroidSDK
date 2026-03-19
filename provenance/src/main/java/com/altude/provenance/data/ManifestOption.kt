package com.altude.provenance.data

/**
 * Controls how the C2PA manifest is stored after a successful attestation.
 *
 * Pass as the `manifestOption` parameter to [com.altude.provenance.Provenance.attestImageHash]
 * or [com.altude.provenance.Provenance.attestBatch].
 *
 * ```kotlin
 * // Default — sidecar .c2pa.json file next to the image
 * Provenance.attestImageHash(payload, ManifestOption.SidecarFile)
 *
 * // Embed the manifest directly into the image file (JPEG XMP / PNG tEXt)
 * Provenance.attestImageHash(payload, ManifestOption.EmbedInImage(sourceFilePath))
 *
 * // Save sidecar AND embed in image
 * Provenance.attestImageHash(payload, ManifestOption.Both(sourceFilePath))
 *
 * // Skip saving entirely (manifest still returned in ProvenanceResult.manifest)
 * Provenance.attestImageHash(payload, ManifestOption.None)
 * ```
 */
sealed class ManifestOption {

    /**
     * Save manifest as a `.c2pa.json` sidecar file in
     * `filesDir/provenance_manifests/{filename}.c2pa.json`.
     *
     * The client receives the image + the sidecar file.
     * Use this when you display/share images alongside a downloadable manifest.
     */
    object SidecarFile : ManifestOption()

    /**
     * Embed the manifest JSON directly into the image file:
     * - **JPEG** → written to XMP metadata (`TAG_XMP`) via `ExifInterface`
     * - **PNG**  → written as a `tEXt` chunk with keyword `C2PA`
     * - **Other formats** → falls back to [SidecarFile] silently
     *
     * The image file at [sourceFilePath] is modified in-place.
     * The client only needs the single image file — no sidecar needed.
     *
     * @param sourceFilePath Absolute path to the original image file to embed into.
     */
    data class EmbedInImage(val sourceFilePath: String) : ManifestOption()

    /**
     * Save sidecar `.c2pa.json` file AND embed manifest into the image.
     * Gives the client both options for verification.
     *
     * @param sourceFilePath Absolute path to the original image file to embed into.
     */
    data class Both(val sourceFilePath: String) : ManifestOption()

    /**
     * Do not save the manifest to disk.
     * The manifest is still available in [com.altude.provenance.data.ProvenanceResult.manifest]
     * for in-memory use.
     */
    object None : ManifestOption()
}

