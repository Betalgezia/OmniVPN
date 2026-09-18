package com.betalgezia.omnivpn.data.local

import androidx.room.TypeConverter
import com.betalgezia.omnivpn.data.model.Protocol

class Converters {
    @TypeConverter
    fun fromProtocol(value: Protocol): String = value.name

    @TypeConverter
    fun toProtocol(value: String): Protocol = Protocol.valueOf(value)
}
