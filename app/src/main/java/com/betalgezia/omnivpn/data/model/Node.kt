package com.betalgezia.omnivpn.data.model

data class Node(
    val id: Long = 0,
    val name: String,
    val protocol: Protocol,
    val server: String,
    val port: Int,
    val uuid: String? = null,
    val password: String? = null,
    val privateKey: String? = null,
    val publicKey: String? = null,
    val preSharedKey: String? = null,
    val serverPublicKey: String? = null,
    val endpoint: String? = null,
    val rawConfig: String? = null,
    val awg: AwgParameters? = null
)

data class AwgParameters(
    val jc: Int = 0,
    val jmin: Int = 0,
    val jmax: Int = 0,
    val s1: Int = 0,
    val s2: Int = 0,
    val s3: Int = 0,
    val s4: Int = 0,
    val h1: Long = 0,
    val h2: Long = 0,
    val h3: Long = 0,
    val h4: Long = 0,
    val i1: String? = null,
    val i2: String? = null,
    val i3: String? = null,
    val i4: String? = null,
    val i5: String? = null
)
