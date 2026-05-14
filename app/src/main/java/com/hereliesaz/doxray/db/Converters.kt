package com.hereliesaz.doxray.db

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromFloatArray(value: FloatArray?): String? {
        return gson.toJson(value)
    }

    @TypeConverter
    fun toFloatArray(value: String?): FloatArray? {
        if (value == null) return null
        val listType = object : TypeToken<FloatArray>() {}.type
        return gson.fromJson(value, listType)
    }
}
