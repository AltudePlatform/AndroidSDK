package com.altude.gasstation.data

data class ComputeOptions(
    val computeUnitLimit: Int = 400_000,
    val computeUnitPriceMicroLamports: Long? = null,
    val heapFrameBytes: Int? = null
)
