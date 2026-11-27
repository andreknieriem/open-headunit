package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue
import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue.Companion.dict

/**
 * Reads the SETUP request of the control channel and builds its answer. The session SETUP opens the
 * timing, event and keep-alive ports. A streams SETUP opens one data port per screen (type 110) and
 * audio (type 100 or 101) stream. Ports come from the caller, so nothing here binds a socket.
 * Follows xcertplay airplay/AirPlaySession.kt `handleSetup` and LIVI (https://github.com/f-io/LIVI) `stack/setup.rs`.
 */
object SetupNegotiation {
    class Session(
        val name: String,
        val deviceId: String,
        val model: String,
        val macAddress: String,
        /** 0 when the phone names no usable timing port. */
        val peerTimingPort: Int,
        val wantsKeepAlive: Boolean,
        val offeredFeatures: List<String>,
    )

    sealed class Stream {
        /** [connectionId] is [StreamKeys.connectionIdText], null when the phone sent none. */
        class Screen(val connectionId: String?) : Stream()

        class Audio(
            /** [AirPlayAudioRouting.STREAM_TYPE_AUDIO] or [AirPlayAudioRouting.STREAM_TYPE_ALT_AUDIO], echoed in the answer. */
            val type: Int,
            val audioType: String,
            val formatBits: Long,
            val connectionId: String?,
            /** The id as sent, echoed in the answer. */
            val rawConnectionId: PlistValue,
            val latencyMs: Int,
        ) : Stream()

        /** A type stage 2 does not serve. It stays out of the answer. */
        class Unsupported(val type: Long) : Stream()
    }

    sealed class Request {
        class Streams(val streams: List<Stream>) : Request()
        class SessionSetup(val session: Session) : Request()
    }

    /** Null when the body is no dictionary. A body with a `streams` list is a streams SETUP. */
    fun parse(body: PlistValue): Request? {
        if (body !is PlistValue.Dict) return null
        val streams = body["streams"]?.asItems
        if (streams != null) return Request.Streams(streams.map(::stream))
        return Request.SessionSetup(
            Session(
                name = body["name"]?.asText ?: "",
                deviceId = body["deviceID"]?.asText ?: "",
                model = body["model"]?.asText ?: "",
                macAddress = (body["macAddress"]?.asText ?: "").lowercase(),
                peerTimingPort = port(body["timingPort"]),
                wantsKeepAlive = truthy(body["keepAliveLowPower"]),
                offeredFeatures = body["features"]?.asItems?.mapNotNull { it.asText } ?: emptyList(),
            ),
        )
    }

    /**
     * The offered features we accept. Only `viewAreas`, and only when /info describes a view area.
     * `iAPChannel`, `altScreen` and `hevc` stay off: stage 2 serves none of them.
     */
    fun enabledFeatures(offered: List<String>, describesViewArea: Boolean): List<String> =
        offered.filter { it == FEATURE_VIEW_AREAS && describesViewArea }.distinct()

    /** The answer to a session SETUP. [keepAlivePort] is null when the phone did not ask for one. */
    fun sessionResponse(timingPort: Int, eventPort: Int, keepAlivePort: Int?, enabledFeatures: List<String>): PlistValue.Dict {
        val entries = mutableListOf<Pair<String, PlistValue>>(
            "timingPort" to PlistValue.Integer(timingPort.toLong()),
            "eventPort" to PlistValue.Integer(eventPort.toLong()),
        )
        if (keepAlivePort != null) entries += "keepAlivePort" to PlistValue.Integer(keepAlivePort.toLong())
        entries += "enabledFeatures" to PlistValue.Items(enabledFeatures.map { PlistValue.Text(it) })
        return PlistValue.Dict(entries)
    }

    fun screenEntry(type: Long, dataPort: Int): PlistValue.Dict =
        dict("type" to PlistValue.Integer(type), "dataPort" to PlistValue.Integer(dataPort.toLong()))

    fun audioEntry(stream: Stream.Audio, dataPort: Int, controlPort: Int): PlistValue.Dict = dict(
        "type" to PlistValue.Integer(stream.type.toLong()),
        "dataPort" to PlistValue.Integer(dataPort.toLong()),
        "controlPort" to PlistValue.Integer(controlPort.toLong()),
        "streamConnectionID" to stream.rawConnectionId,
    )

    fun streamsResponse(entries: List<PlistValue>): PlistValue.Dict = dict("streams" to PlistValue.Items(entries))

    private const val FEATURE_VIEW_AREAS = "viewAreas"

    private fun stream(value: PlistValue): Stream {
        val dict = value as? PlistValue.Dict ?: return Stream.Unsupported(-1)
        val type = dict["type"]?.asLong ?: return Stream.Unsupported(-1)
        val connection = dict["streamConnectionID"]
        return when (type) {
            InfoPlist.STREAM_TYPE_MAIN_SCREEN -> Stream.Screen(StreamKeys.connectionIdText(connection))
            AirPlayAudioRouting.STREAM_TYPE_AUDIO.toLong(), AirPlayAudioRouting.STREAM_TYPE_ALT_AUDIO.toLong() -> Stream.Audio(
                type = type.toInt(),
                audioType = dict["audioType"]?.asText ?: "media",
                formatBits = dict["audioFormat"]?.asLong ?: 0L,
                connectionId = StreamKeys.connectionIdText(connection),
                rawConnectionId = connection ?: PlistValue.Integer(0),
                latencyMs = (dict["audioLatencyMs"]?.asLong ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            )
            else -> Stream.Unsupported(type)
        }
    }

    private fun port(value: PlistValue?): Int {
        val n = value?.asLong ?: return 0
        return if (n in 1..65535) n.toInt() else 0
    }

    private fun truthy(value: PlistValue?): Boolean = when (value) {
        null -> false
        is PlistValue.Bool -> value.value
        is PlistValue.Integer -> value.value != 0L
        is PlistValue.Real -> value.value != 0.0 && !value.value.isNaN()
        is PlistValue.Text -> value.value.isNotEmpty()
        else -> true
    }
}
