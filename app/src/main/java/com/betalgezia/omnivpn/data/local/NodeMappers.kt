package com.betalgezia.omnivpn.data.local

import com.betalgezia.omnivpn.data.model.AwgParameters
import com.betalgezia.omnivpn.data.model.Node

fun NodeEntity.toDomain(): Node = Node(
    id = id, name = name, protocol = protocol, server = server, port = port,
    uuid = uuid, password = password, privateKey = privateKey, publicKey = publicKey,
    preSharedKey = preSharedKey, serverPublicKey = serverPublicKey, endpoint = endpoint,
    rawConfig = rawConfig, awg = awgJson?.let(AwgParametersCodec::decode)
)

fun Node.toEntity(): NodeEntity = NodeEntity(
    id = id, name = name, protocol = protocol, server = server, port = port,
    uuid = uuid, password = password, privateKey = privateKey, publicKey = publicKey,
    preSharedKey = preSharedKey, serverPublicKey = serverPublicKey, endpoint = endpoint,
    rawConfig = rawConfig, awgJson = awg?.let(AwgParametersCodec::encode)
)

private object AwgParametersCodec {
    fun encode(value: AwgParameters): String = org.json.JSONObject().apply {
        put("jc", value.jc).put("jmin", value.jmin).put("jmax", value.jmax)
        put("s1", value.s1).put("s2", value.s2).put("s3", value.s3).put("s4", value.s4)
        put("h1", value.h1).put("h2", value.h2).put("h3", value.h3).put("h4", value.h4)
        listOf("i1" to value.i1, "i2" to value.i2, "i3" to value.i3, "i4" to value.i4, "i5" to value.i5)
            .forEach { (k, v) -> if (!v.isNullOrBlank()) put(k, v) }
    }.toString()

    fun decode(value: String): AwgParameters {
        val j = org.json.JSONObject(value)
        return AwgParameters(
            jc = j.optInt("jc"), jmin = j.optInt("jmin"), jmax = j.optInt("jmax"),
            s1 = j.optInt("s1"), s2 = j.optInt("s2"), s3 = j.optInt("s3"), s4 = j.optInt("s4"),
            h1 = j.optLong("h1"), h2 = j.optLong("h2"), h3 = j.optLong("h3"), h4 = j.optLong("h4"),
            i1 = j.optString("i1").takeIf { it.isNotBlank() }, i2 = j.optString("i2").takeIf { it.isNotBlank() },
            i3 = j.optString("i3").takeIf { it.isNotBlank() }, i4 = j.optString("i4").takeIf { it.isNotBlank() },
            i5 = j.optString("i5").takeIf { it.isNotBlank() }
        )
    }
}