package com.betalgezia.omnivpn.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.betalgezia.omnivpn.data.model.Protocol

@Entity(tableName = "nodes")
data class NodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val protocol: Protocol,
    val server: String,
    val port: Int,
    val uuid: String?,
    val password: String?,
    val privateKey: String?,
    val publicKey: String?,
    val preSharedKey: String?,
    val serverPublicKey: String?,
    val endpoint: String?,
    val rawConfig: String?,
    val awgJson: String?,
    val sourceId: Long?
)
