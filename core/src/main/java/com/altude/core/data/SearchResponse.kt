package com.altude.core.data


data class TokenInfo(
    val id: String,
    val name: String,
    val symbol: String,
    val icon: String,
    val decimals: Int,
    val twitter: String?,
    val telegram: String?,
    val website: String?,
    val dev: String?,
    val circSupply: Double?,
    val totalSupply: Double?,
    val tokenProgram: String?,
    val launchpad: String?,
    val partnerConfig: String?,
    val graduatedPool: String?,
    val graduatedAt: String?,
    val holderCount: Int?,
    val fdv: Double?,
    val mcap: Double?,
    val usdPrice: Double?,
    val priceBlockId: Int?,
    val liquidity: Double?,
    val stats5m: TokenStats?,
    val stats1h: TokenStats?,
    val stats6h: TokenStats?,
    val stats24h: TokenStats?,
    val firstPool: FirstPool?,
    val audit: Audit?,
    val organicScore: Double?,
    val organicScoreLabel: String?,
    val isVerified: Boolean,
    val cexes: List<String>?,
    val tags: List<String>?,
    val updatedAt: String?
)

data class TokenStats(
    val priceChange: Double?,
    val holderChange: Double?,
    val liquidityChange: Double?,
    val volumeChange: Double?,
    val buyVolume: Double?,
    val sellVolume: Double?,
    val buyOrganicVolume: Double?,
    val sellOrganicVolume: Double?,
    val numBuys: Int?,
    val numSells: Int?,
    val numTraders: Int?,
    val numOrganicBuyers: Int?,
    val numNetBuyers: Int?
)

data class FirstPool(
    val id: String?,
    val createdAt: String?
)

data class Audit(
    val isSus: Boolean,
    val mintAuthorityDisabled: Boolean,
    val freezeAuthorityDisabled: Boolean,
    val topHoldersPercentage: Double?,
    val devBalancePercentage: Double?,
    val devMigrations: Int?
)
