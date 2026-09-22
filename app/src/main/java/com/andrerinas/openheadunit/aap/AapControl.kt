package com.andrerinas.openheadunit.aap

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.andrerinas.openheadunit.aap.protocol.AudioConfigs
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.messages.DrivingStatusEvent
import com.andrerinas.openheadunit.aap.protocol.messages.LocationUpdateEvent
import com.andrerinas.openheadunit.aap.protocol.messages.MicrophoneResponse
import com.andrerinas.openheadunit.aap.protocol.messages.ServiceDiscoveryResponse
import com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent
import com.andrerinas.openheadunit.aap.protocol.proto.Common
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Input
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.aap.protocol.proto.Sensors
import com.andrerinas.openheadunit.decoder.audio.MicRecorder
import com.andrerinas.openheadunit.decoder.audio.MicrophonePolicy
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.location.LocationHolder
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.Utils

interface AapControl {
    fun execute(message: AapMessage): Int
}

internal class AapControlMedia(
    private val aapTransport: AapTransport,
    private val micRecorder: MicRecorder,
    private val aapAudio: AapAudio): AapControl {

    private var lastNativeFocusRequestTime = 0L
    private var nativeFocusRequestCount = 0

    override fun execute(message: AapMessage): Int {

        when (message.type) {
            Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE -> {
                val setupRequest = message.parse(Media.MediaSetupRequest.newBuilder()).build()
                return mediaSinkSetupRequest(setupRequest, message.channel)
            }
            Media.MsgType.MEDIA_MESSAGE_START_VALUE -> {
                val startRequest = message.parse(Media.Start.newBuilder()).build()
                return mediaStartRequest(startRequest, message.channel)
            }
            Media.MsgType.MEDIA_MESSAGE_STOP_VALUE -> return mediaSinkStopRequest(message.channel)
            Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST_VALUE -> {
                val focusRequest = message.parse(Media.VideoFocusRequestNotification.newBuilder()).build()
                AppLog.i("RX: Video Focus Request - mode: %s, reason: %s, channel: %s",
                    focusRequest.mode, focusRequest.reason, Channel.name(message.channel))

                val answer = SecondaryVideoFocusPolicy.onFocusRequest(message.channel, focusRequest.mode)
                if (answer == SecondaryVideoFocusPolicy.Answer.AUX_RELEASED) {
                    AppLog.i("The phone released the second display's video. The main session continues.")
                } else if (answer == SecondaryVideoFocusPolicy.Answer.GRANT_ON_CHANNEL) {
                    AppLog.i("Granting video focus on %s", Channel.name(message.channel))
                    aapTransport.send(VideoFocusEvent(gain = true, unsolicited = false, channel = message.channel))
                } else if (answer == SecondaryVideoFocusPolicy.Answer.RUN_EXIT_ACTION) {
                    AppLog.i("Video Focus NATIVE received. User clicked Exit in Android Auto.")
                    val ctx = aapTransport.context
                    val settings = App.provide(ctx).settings
                    when (settings.aaExitAction) {
                        Settings.ExitAction.OEM_LAUNCHER -> {
                            AppLog.i("ExitAction: Minimizing projection to OEM Launcher")
                            AapProjectionActivity.minimizeToHome(ctx)
                        }
                        Settings.ExitAction.APP_HOME -> {
                            AppLog.i("ExitAction: Returning to App Home")
                            AapProjectionActivity.returnToAppHome(ctx)
                        }
                        Settings.ExitAction.DISCONNECT -> {
                            AppLog.i("ExitAction: Disconnecting projection session")
                            aapTransport.wasUserExit = true
                            aapTransport.stop()
                        }
                    }
                }
                return 0
            }
            Media.MsgType.MEDIA_MESSAGE_MICROPHONE_REQUEST_VALUE -> {
                val micRequest = message.parse(Media.MicrophoneRequest.newBuilder()).build()
                return micRequest(micRequest)
            }
            Media.MsgType.MEDIA_MESSAGE_UPDATE_UI_CONFIG_REPLY_VALUE -> {
                AppLog.i("RX: Update UI Config Reply received. Acknowledging UI Config change.")
                aapTransport.onUpdateUiConfigReplyReceived?.invoke()
                return 0
            }
            Media.MsgType.MEDIA_MESSAGE_ACK_VALUE -> {
                // The phone flow-controls this stream and nothing here has ever counted its acks,
                // so a window we ran past would have looked like silence. Counted, not acted on.
                if (message.channel == Channel.ID_MIC) aapTransport.onMicAck()
                return 0
            }
            else -> AppLog.e("Unsupported Media message type: ${message.type}")
        }
        return 0
    }

    private fun mediaStartRequest(request: Media.Start, channel: Int): Int {
        AppLog.i("Media Start Request %s: session=%d, config_index=%d", Channel.name(channel), request.sessionId, request.configurationIndex)

        aapTransport.setSessionId(channel, request.sessionId)
        aapTransport.noteAudioSinkStarted(channel)
        return 0
    }

    private fun mediaSinkSetupRequest(request: Media.MediaSetupRequest, channel: Int): Int {

        AppLog.i("Media Sink Setup Request: %d on channel %s", request.type, Channel.name(channel))

        val maxUnacked = maxUnackedFor(channel)
        val configResponse = Media.Config.newBuilder().apply {
            status = Media.Config.ConfigStatus.STATUS_READY
            this.maxUnacked = maxUnacked

            addConfigurationIndices(0)
        }.build()
        AppLog.i("Config response: %s (maxUnacked=%d)", configResponse, maxUnacked)
        val msg = AapMessage(channel, Media.MsgType.MEDIA_MESSAGE_CONFIG_VALUE, configResponse)
        aapTransport.send(msg)

        if (channel == Channel.ID_VID) {
            aapTransport.gainVideoFocus()
        } else if (SecondaryVideoFocusPolicy.grantsFocusAfterSetup(channel)) {
            // The phone streams to a second sink only once it holds focus on that sink's channel.
            AppLog.i("Granting video focus on %s after its setup", Channel.name(channel))
            aapTransport.send(VideoFocusEvent(gain = true, unsolicited = false, channel = channel))
        }

        // Pushing AudioFocusNotification
        if (Channel.isAudio(channel)) {
            aapAudio.noteSinkCodec(channel, request.type)
            aapAudio.precreateAudioTrack(channel)
            val focusNotification = Control.AudioFocusNotification.newBuilder()
                .setFocusState(Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN)
                .setUnsolicited(true)
                .build()
            aapTransport.send(AapMessage(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_AUDIO_FOCUS_NOTIFICATION_VALUE, focusNotification))
        }

        return 0
    }

    private fun maxUnackedFor(channel: Int): Int {
        if (Channel.isVideo(channel)) {
            val softwareHevc =
                aapTransport.settings.videoCodec == VideoDecoder.CodecType.H265.settingsValue &&
                        aapTransport.settings.forceSoftwareDecoding &&
                        aapTransport.settings.softwareVideoDecoder == Settings.SoftwareVideoDecoder.BUNDLED_FFMPEG
            if (softwareHevc) {
                // Keep the phone closer to decoder pace. A large wireless window lets video
                // backlog turn into visible input lag when 2K HEVC is decoded in software.
                return if (aapTransport.isWireless) 6 else 8
            }
            // Left wide for hardware decode, deliberately: a keyframe fragments into a dozen or
            // more messages, so narrowing this stalls the phone mid-keyframe and caps throughput at
            // window/RTT. The phone does not hold to it either - one told 12 ran our backlog to 120
            // - so the bound that works is the decoder discarding frames it is behind on.
            return if (aapTransport.isWireless) 12 else 16
        }

        // Audio still benefits from a wider jitter window, especially on wireless.
        return if (aapTransport.isWireless) 30 else 16
    }

    private fun mediaSinkStopRequest(channel: Int): Int {
        AppLog.i("Media Sink Stop Request: " + Channel.name(channel))
        if (Channel.isAudio(channel)) {
            // Before the sink goes: this silence is one the phone asked for.
            aapTransport.noteAudioSinkStopped(channel)
            aapAudio.stopAudio(channel)
        } else if (channel == Channel.ID_MIC) {
            // The mic is a source, so Channel.isAudio deliberately excludes it - widening that
            // predicate would make this head unit claim audio focus and precreate an AudioTrack for
            // a microphone. A stop here is still the phone saying it wants no more PCM, and until
            // now the recorder kept running and kept sending.
            micRecorder.stop()
            aapTransport.onMicSessionEnded()
        } else if (channel == Channel.ID_VID) {
            if (aapTransport.ignoreNextStopRequest) {
                AppLog.i("Video Sink Stopped -> Ignored (Forced Keyframe Request)")
                aapTransport.ignoreNextStopRequest = false
                return 0
            }
            AppLog.i("Video Sink Stopped -> Normal background/transition behavior")
        } else if (channel == Channel.ID_VID2) {
            AppLog.i(if (aapTransport.consumeAuxCycleStop()) "Auxiliary Video Sink Stopped -> Ignored (Forced Keyframe Request)"
                else "Auxiliary Video Sink Stopped -> the main session continues")
        }
        return 0
    }

    /**
     * Open or close the microphone, and say so.
     *
     * At INFO because the request's own toString is the only place anyone will ever see what
     * Android Auto asks for - anc_enabled, ec_enabled and the flow-control window - none of which
     * this head unit has ever recorded, let alone honoured.
     */
    private fun micRequest(micRequest: Media.MicrophoneRequest): Int {
        AppLog.i("Mic request: %s", micRequest)

        val status = if (micRequest.open) {
            when (MicrophonePolicy.declineReason(
                    aapTransport.settings.useHeadUnitMicrophone, micRecorder.isAvailable)) {
                MicrophonePolicy.Decline.USER_SETTING -> {
                    // Named in the user's terms, the same way the audio-sink skip is, so a silent
                    // assistant reads as a setting rather than as a fault.
                    AppLog.i("Mic request: the head unit microphone is off in Settings. Declining " +
                        "and sending nothing, so a Bluetooth headset keeps this microphone. The " +
                        "service is not announced either, so a request arriving here means the " +
                        "phone kept an older record of this head unit")
                    Common.MessageStatus.STATUS_INTERNAL_ERROR_VALUE
                }
                MicrophonePolicy.Decline.NO_MICROPHONE -> {
                    AppLog.w("Mic request: this device has no usable microphone capture; declining")
                    Common.MessageStatus.STATUS_INTERNAL_ERROR_VALUE
                }
                MicrophonePolicy.Decline.NONE -> {
                    val result = micRecorder.start()
                    if (result != 0) {
                        AppLog.w("Mic request: capture did not start (code $result); telling the " +
                            "phone so rather than leaving it waiting on a stream that will never arrive")
                        Common.MessageStatus.STATUS_INTERNAL_ERROR_VALUE
                    } else {
                        Common.MessageStatus.STATUS_SUCCESS_VALUE
                    }
                }
            }
        } else {
            micRecorder.stop()
            // The session boundary the uplink report is measured over. Without it the line only
            // appears at disconnect, long after the assistant session it describes.
            aapTransport.onMicSessionEnded()
            Common.MessageStatus.STATUS_SUCCESS_VALUE
        }

        aapTransport.send(MicrophoneResponse(status, aapTransport.getSessionId(Channel.ID_MIC)))
        return 0
    }

    companion object {
        /** Time window for counting consecutive VIDEO_FOCUS_NATIVE requests. */
        private const val NATIVE_FOCUS_DEBOUNCE_MS = 5000L
        /** Number of NATIVE focus requests within the debounce window before stopping. */
        private const val MAX_NATIVE_FOCUS_RETRIES = 3
    }
}

internal class AapControlTouch(private val aapTransport: AapTransport): AapControl {

    override fun execute(message: AapMessage): Int {

        when (message.type) {
            Input.MsgType.BINDINGREQUEST_VALUE -> {
                val request = message.parse(Input.KeyBindingRequest.newBuilder()).build()
                return inputBinding(request, message.channel)
            }
            else -> AppLog.e("Unsupported Input message type: ${message.type}")
        }
        return 0
    }

    private fun inputBinding(request: Input.KeyBindingRequest, channel: Int): Int {
        aapTransport.send(AapMessage(channel, Input.MsgType.BINDINGRESPONSE_VALUE, Input.BindingResponse.newBuilder()
                .setStatus(Common.MessageStatus.STATUS_SUCCESS)
                .build()))
        return 0
    }

}

internal class AapControlSensor(
        private val aapTransport: AapTransport,
        private val context: Context,
        private val settings: Settings): AapControl {

    override fun execute(message: AapMessage): Int {
        when (message.type) {
            Sensors.SensorsMsgType.SENSOR_STARTREQUEST_VALUE -> {
                val request = message.parse(Sensors.SensorRequest.newBuilder()).build()
                return sensorStartRequest(request, message.channel)
            }
            else -> AppLog.e("Unsupported Sensor message type: ${message.type}")
        }
        return 0
    }

    private fun sensorStartRequest(request: Sensors.SensorRequest, channel: Int): Int {
        AppLog.i("Sensor Start Request sensor: %s, minUpdatePeriod: %d", request.type.name, request.minUpdatePeriod)

        val msg = AapMessage(channel, Sensors.SensorsMsgType.SENSOR_STARTRESPONSE_VALUE, Sensors.SensorResponse.newBuilder()
                .setStatus(Common.MessageStatus.STATUS_SUCCESS)
                .build())
        AppLog.i(msg.toString())

        aapTransport.send(msg)
        aapTransport.startSensor(request.type.number)

        if (request.type == Sensors.SensorType.NIGHT) {
            AppLog.i("Night sensor requested. Triggering immediate update.")
            val intent = Intent(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE)
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
        }

        if (request.type == Sensors.SensorType.LOCATION && settings.useGpsForNavigation) {
            // The phone only accepts LOCATION events once this request has been answered, which
            // can land well after TransportStarted (CommManager's own post-handshake flush can
            // race this and lose). This is the actual earliest point sending can succeed.
            //
            // currentGpsFix, not currentLocation: only this head unit's own GPS may be sent as the
            // car's position. A unit with no antenna attached passes every gate here and never
            // locks, and the broader accessor would hand it a network fix to send instead.
            val fix = LocationHolder.currentGpsFix(context)
            if (fix != null) {
                val sentOnWire = aapTransport.send(LocationUpdateEvent(fix))
                AppLog.i("LOCATION sensor requested. Sending current fix immediately. sentOnWire=$sentOnWire")
            } else {
                AppLog.i("LOCATION sensor requested. No recent GPS fix to prime with.")
            }
        }
        return 0
    }
}

internal class AapControlService(
        private val aapTransport: AapTransport,
        private val aapAudio: AapAudio,
        private val settings: Settings,
        private val context: Context): AapControl {

    override fun execute(message: AapMessage): Int {

        when (message.type) {
            Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_REQUEST_VALUE -> {
                val request = message.parse(Control.ServiceDiscoveryRequest.newBuilder()).build()
                return serviceDiscoveryRequest(request)
            }
            Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE -> {
                val pingRequest = message.parse(Control.PingRequest.newBuilder()).build()
                return pingRequest(pingRequest, message.channel)
            }
            Control.ControlMsgType.MESSAGE_NAV_FOCUS_REQUEST_VALUE -> {
                val navigationFocusRequest = message.parse(Control.NavFocusRequestNotification.newBuilder()).build()
                return navigationFocusRequest(navigationFocusRequest, message.channel)
            }
            Control.ControlMsgType.MESSAGE_BYEBYE_REQUEST_VALUE -> {
                val shutdownRequest = message.parse(Control.ByeByeRequest.newBuilder()).build()
                return byebyeRequest(shutdownRequest, message.channel)
            }
            Control.ControlMsgType.MESSAGE_BYEBYE_RESPONSE_VALUE -> {
                AppLog.i("Byebye Response received")
                return -1
            }
            Control.ControlMsgType.MESSAGE_VOICE_SESSION_NOTIFICATION_VALUE -> {
                val voiceRequest = message.parse(Control.VoiceSessionNotification.newBuilder()).build()
                return voiceSessionNotification(voiceRequest)
            }
            Control.ControlMsgType.MESSAGE_AUDIO_FOCUS_REQUEST_VALUE -> {
                val audioFocusRequest = message.parse(Control.AudioFocusRequestNotification.newBuilder()).build()
                return audioFocusRequest(audioFocusRequest, message.channel)
            }
            Control.ControlMsgType.MESSAGE_CHANNEL_CLOSE_NOTIFICATION_VALUE -> {
                AppLog.i("RX: Channel Close Notification on chan ${message.channel}")
                return 0
            }
            else -> AppLog.e("Unsupported Control message type: ${message.type}")
        }
        return 0
    }


    private fun serviceDiscoveryRequest(request: Control.ServiceDiscoveryRequest): Int {
        AppLog.i("Service Discovery Request: %s", request.phoneName)

        val msg = ServiceDiscoveryResponse(context)
        aapTransport.send(msg)
        return 0
    }

    private fun pingRequest(request: Control.PingRequest, channel: Int): Int {
        val response = Control.PingResponse.newBuilder()
                .setTimestamp(System.nanoTime())
                .build()

        val msg = AapMessage(channel, Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE, response)
        aapTransport.send(msg)
        return 0
    }

    private fun navigationFocusRequest(request: Control.NavFocusRequestNotification, channel: Int): Int {
        AppLog.i("Navigation Focus Request: %s", request.focusType)

        val response = Control.NavFocusNotification.newBuilder()
                .setFocusType(Control.NavFocusType.NAV_FOCUS_PROJECTED)
                .build()

        val msg = AapMessage(channel, Control.ControlMsgType.MESSAGE_NAV_FOCUS_NOTIFICATION_VALUE, response)
        AppLog.i(msg.toString())

        aapTransport.send(msg)
        return 0
    }

    private fun byebyeRequest(request: Control.ByeByeRequest, channel: Int): Int {
        AppLog.i("!!! RECEIVED BYEBYE REQUEST FROM PHONE !!! Reason: ${request.reason}")

        val msg = AapMessage(channel, Control.ControlMsgType.MESSAGE_BYEBYE_RESPONSE_VALUE, Control.ByeByeResponse.newBuilder().build())
        AppLog.i("Sending BYEYERESPONSE")
        aapTransport.send(msg)
        Utils.ms_sleep(500)
        AppLog.i("Calling aapTransport.quit(clean=true)")
        aapTransport.quit(clean = true)
        return -1
    }

    private fun voiceSessionNotification(request: Control.VoiceSessionNotification): Int {
        if (request.status == Control.VoiceSessionNotification.VoiceSessionStatus.VOICE_STATUS_START) {
            AppLog.i("Voice Session Notification: START")
            aapTransport.isAssistantActive = true
        } else if (request.status == Control.VoiceSessionNotification.VoiceSessionStatus.VOICE_STATUS_STOP) {
            AppLog.i("Voice Session Notification: STOP")
            aapTransport.isAssistantActive = false
        }
        return 0
    }

    private fun audioFocusRequest(notification: Control.AudioFocusRequestNotification, channel: Int): Int {
        AppLog.i("Audio Focus Request: ${notification.request}")

        // Always respond with the mapped focus state to AA — never deny.
        // the phone must always believe the headunit
        // has audio focus, otherwise it keeps audio output on the phone itself.
        val mappedState = focusResponse[notification.request]
        if (mappedState != null) {
            val response = Control.AudioFocusNotification.newBuilder()
                .setFocusState(mappedState)
                .build()
            AppLog.i("Sending immediate AudioFocusNotification: $mappedState (always-grant)")
            aapTransport.send(AapMessage(channel, Control.ControlMsgType.MESSAGE_AUDIO_FOCUS_NOTIFICATION_VALUE, response))

            // Sync MediaSession
            val isGain = mappedState == Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN
            aapTransport.onAudioFocusStateChanged?.invoke(isGain)
        }

        // Best-effort: request system audio focus to duck other apps on the headunit.
        // The result is intentionally ignored for the protocol response above, which has already
        // been sent — only the system-level grab is in question here, never the always-grant reply.
        if (settings.enableAudioSink) {
            if (settings.staticAudioFocus) {
                AppLog.i("Static Audio Focus active - skipping dynamic system focus request to prevent routing loss")
            } else {
                // Gated at the call site, not inside requestFocusChange: that function is also the
                // static path's permanent grab from CommManager, where the answer is the opposite.
                val isRelease = notification.request.number ==
                        Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE_VALUE
                if (aapAudio.shouldHonourProtocolFocusRequest(isRelease)) {
                    aapAudio.requestFocusChange(AudioConfigs.stream(channel, settings), notification.request.number, AudioManager.OnAudioFocusChangeListener {
                        AppLog.i("System audio focus changed: $it ${systemFocusName[it]}")
                    })
                }
            }
        } else {
            AppLog.i("Audio Sink disabled - skipping system audio focus request for channel ${Channel.name(channel)}")
        }

        return 0
    }

    companion object {
        private val systemFocusName = mapOf(
                AudioManager.AUDIOFOCUS_GAIN to "AUDIOFOCUS_GAIN",
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT to "AUDIOFOCUS_GAIN_TRANSIENT",
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE to "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE",
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK to "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK",
                AudioManager.AUDIOFOCUS_LOSS to "AUDIOFOCUS_LOSS",
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT to "AUDIOFOCUS_LOSS_TRANSIENT",
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK to "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK",
                AudioManager.AUDIOFOCUS_NONE to "AUDIOFOCUS_NONE"
        )

        private val focusResponse = mapOf(
            Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE to Control.AudioFocusNotification.AudioFocusStateType.STATE_LOSS,
            Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN to Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN,
            Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT to Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN_TRANSIENT,
            Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_MAY_DUCK to Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN_TRANSIENT_GUIDANCE_ONLY
        )
    }
}

internal class AapControlGateway(
        private val aapTransport: AapTransport,
        private val serviceControl: AapControl,
        private val mediaControl: AapControl,
        private val touchControl: AapControl,
        private val sensorControl: AapControl): AapControl {

    constructor(aapTransport: AapTransport,
                micRecorder: MicRecorder,
                aapAudio: AapAudio,
                settings: Settings,
                context: Context) : this(
            aapTransport,
            AapControlService(aapTransport, aapAudio, settings, context),
            AapControlMedia(aapTransport, micRecorder, aapAudio),
            AapControlTouch(aapTransport),
            AapControlSensor(aapTransport, context, settings))

    override fun execute(message: AapMessage): Int {
        if (message.type == 7) {
            val request = message.parse(Control.ChannelOpenRequest.newBuilder()).build()
            return channelOpenRequest(request, message.channel)
        }

        when (message.channel) {
            Channel.ID_CTR -> return serviceControl.execute(message)
            Channel.ID_INP -> return touchControl.execute(message)
            Channel.ID_SEN -> return sensorControl.execute(message)
            Channel.ID_VID, Channel.ID_VID2, Channel.ID_AUD, Channel.ID_AU1, Channel.ID_AU2, Channel.ID_MIC ->
                return mediaControl.execute(message)
        }
        return 0
    }

    private fun channelOpenRequest(request: Control.ChannelOpenRequest, channel: Int): Int {
        val msg = AapMessage(channel, Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_RESPONSE_VALUE, Control.ChannelOpenResponse.newBuilder()
                .setStatus(Common.MessageStatus.STATUS_SUCCESS)
                .build())
        aapTransport.send(msg)

        if (channel == Channel.ID_SEN) {
            aapTransport.send(DrivingStatusEvent(Sensors.SensorBatch.DrivingStatusData.Status.UNRESTRICTED))
        }
        return 0
    }
}
