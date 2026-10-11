package com.betalgezia.omnivpn.data.model

enum class Protocol {
    VLESS,
    TROJAN,
    HYSTERIA2,
    AMNEZIAWG,
    WARP,
    // Stored in Room by name (see Converters), so adding values needs no migration.
    SHADOWSOCKS,
    VMESS,
    TUIC
}
