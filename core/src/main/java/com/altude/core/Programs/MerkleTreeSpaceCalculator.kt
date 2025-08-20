package com.altude.core.Programs

import kotlin.math.pow

object MerkleTreeSpaceCalculator {
    private const val PUBLIC_KEY_SIZE = 32
    private const val U8_SIZE = 1
    private const val U32_SIZE = 4
    private const val U64_SIZE = 8

    // Equivalent of getConcurrentMerkleTreeHeaderDataSerializer()
    private fun concurrentMerkleTreeHeaderSize(): Int {
          // from mpl-core header layout
        return  U8_SIZE +      // version
                U32_SIZE +     // maxBufferSize
                U32_SIZE +     // maxDepth
                PUBLIC_KEY_SIZE + // authority
                U64_SIZE +     // creationSlot
                PUBLIC_KEY_SIZE   // padding pubkey
    }

    // Equivalent of getConcurrentMerkleTreeSerializer(maxDepth, maxBufferSize)
    private fun concurrentMerkleTreeSize(maxDepth: Int, maxBufferSize: Int): Int {
        // Each leaf node = 32 bytes (hash)
        val nodeCount = 2.0.pow(maxDepth).toInt() - 1
        val leafCount = 2.0.pow(maxDepth).toInt()
        val bufferSize = maxBufferSize * 32
        return nodeCount * 32 + bufferSize + leafCount * 32
    }

    // Canopy size calculation
    private fun canopySize(maxDepth: Int, canopyDepth: Int): Int {
        val fullTreeNodes = 2.0.pow(maxDepth).toInt() - 1
        val canopyNodes = 2.0.pow(maxDepth - canopyDepth).toInt() - 1
        val numNodes = fullTreeNodes - canopyNodes
        return numNodes * PUBLIC_KEY_SIZE
    }

    // Final: Equivalent to getMerkleTreeAccountDataV1Serializer
    fun getMerkleTreeAccountSpace(maxDepth: Int, maxBufferSize: Int, canopyDepth: Int = 0): Int {
        val discriminator = U8_SIZE
        val header = concurrentMerkleTreeHeaderSize()
        val tree = concurrentMerkleTreeSize(maxDepth, maxBufferSize)
        val canopy = canopySize(maxDepth, canopyDepth)

        return discriminator + header + tree + canopy
    }
}
