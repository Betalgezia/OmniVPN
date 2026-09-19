package com.betalgezia.omnivpn.vpn

import android.util.Base64

internal object Base64Compat {
    fun decode(value: String): ByteArray {
        return runCatching {
            Base64.decode(value, Base64.DEFAULT or Base64.NO_WRAP)
        }.getOrElse {
            runCatching {
                Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            }.getOrElse {
                runCatching {
                    java.util.Base64.getDecoder().decode(value)
                }.getOrElse {
                    java.util.Base64.getUrlDecoder().decode(value)
                }
            }
        }
    }

    fun decodeUrl(value: String): ByteArray {
        return runCatching {
            Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrElse {
            java.util.Base64.getUrlDecoder().decode(value)
        }
    }

    fun encode(value: ByteArray): String {
        return runCatching {
            Base64.encodeToString(value, Base64.NO_WRAP)
        }.getOrElse {
            java.util.Base64.getEncoder().withoutPadding().encodeToString(value)
        }
    }
}
