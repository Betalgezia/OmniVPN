package com.betalgezia.omnivpn.data.model

data class Subscription(
    val id: Long = 0,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val lastUpdatedAt: Long? = null
)
