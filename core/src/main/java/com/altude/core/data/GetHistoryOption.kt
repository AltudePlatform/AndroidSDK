package com.altude.core.data

data class GetHistoryOption (
    val account: String,
    val limit: Int,
    val offset: Int
)