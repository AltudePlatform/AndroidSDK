package com.altude.provenance.data

/**
 * Commitment levels for on-chain confirmation.
 */
enum class AttestationOption { processed, confirmed, finalized }

typealias Commitment = AttestationOption
