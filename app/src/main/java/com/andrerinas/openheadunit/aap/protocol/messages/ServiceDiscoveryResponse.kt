package com.andrerinas.openheadunit.aap.protocol.messages

import android.content.Context
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapMessage
import com.andrerinas.openheadunit.aap.ConnectionConfigPolicy
import com.andrerinas.openheadunit.aap.NarrowBandProfilePolicy
import com.andrerinas.openheadunit.aap.VehicleIdentityPolicy
import com.andrerinas.openheadunit.aap.VehicleTypePolicy
import com.andrerinas.openheadunit.input.KeyCode
import com.andrerinas.openheadunit.aap.protocol.AudioConfigs
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.MicCaptureFormat
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.aap.protocol.proto.Sensors
import com.andrerinas.openheadunit.connection.wifi.direct.WifiBandCapability
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.DisplayTargets
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.decoder.video.AuxDisplayProfilePolicy
import com.andrerinas.openheadunit.secondscreen.SecondScreenHub
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import com.google.protobuf.Message

class ServiceDiscoveryResponse(private val context: Context)
    : AapMessage(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE, makeProto(context)) {

    companion object {
        /**
         * A second video sink for an auxiliary display, or null when there is nothing to announce.
         *
         * AUXILIARY by default: a cluster's content is chosen by the phone from its own settings,
         * and only an auxiliary display honours initial_content_keycode.
         */
        private fun auxVideoService(context: Context, settings: Settings): Control.Service? {
            val panel = SecondScreenHub.announce(context, settings) ?: return null
            val profile = AuxDisplayProfilePolicy.profileFor(panel.widthPx, panel.heightPx, panel.densityDpi)
            val role = settings.auxDisplayRole
            val keycode = if (AuxDisplayProfilePolicy.announcesContent(role)) {
                AuxDisplayProfilePolicy.contentKeycodeOrDefault(settings.auxDisplayContent)
            } else null
            AppLog.i("[ServiceDiscovery] Announcing an auxiliary display on ${Channel.name(Channel.ID_VID2)}: " +
                "${settings.auxOutput} ${panel.widthPx}x${panel.heightPx} as ${profile.resolution}, margins " +
                "${profile.widthMargin}x${profile.heightMargin}, density ${profile.density}, " +
                "role=$role, content ${keycode ?: "the phone's choice"}")
            return Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_VID2
                service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also { sink ->
                    // H.264 baseline, which is the only codec the protocol allows a video sink.
                    sink.availableType = Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
                    sink.audioType = Media.AudioStreamType.NONE
                    sink.displayId = 1
                    sink.displayType = AuxDisplayProfilePolicy.displayType(role)
                    keycode?.let { sink.initialContentKeycode = it }
                    sink.addVideoConfigs(Control.Service.MediaSinkService.VideoConfiguration.newBuilder().apply {
                        codecResolution = profile.resolution
                        frameRate = profile.frameRate
                        setDensity(profile.density)
                        setMarginWidth(profile.widthMargin)
                        setMarginHeight(profile.heightMargin)
                        setVideoCodecType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                    }.build())
                }.build()
            }.build()
        }

        private fun makeProto(context: Context): Message {
            val settings = App.provide(context).settings
            // Measure the display the projection will actually use. The geometry goes out once, in
            // this message, and cannot be renegotiated, so a reading taken from the built-in panel
            // while the picture lives on an external one is wrong for the whole session.
            val screenContext = DisplayTargets.contextFor(context, DisplayTargets.choose(context, settings).displayId)
            HeadUnitScreenConfig.init(screenContext, screenContext.resources.displayMetrics, settings)

            val services = mutableListOf<Control.Service>()

            val sensors = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_SEN
                service.sensorSourceService = Control.Service.SensorSourceService.newBuilder().also { sources ->
                    sources.addSensors(makeSensorType(Sensors.SensorType.DRIVING_STATUS))
                    if (settings.useGpsForNavigation) {
                        sources.addSensors(makeSensorType(Sensors.SensorType.LOCATION))
                    }

                    // Always announce Night sensor, as we control it via NightModeManager
                    sources.addSensors(makeSensorType(Sensors.SensorType.NIGHT))
                    AppLog.i("[ServiceDiscovery] Announcing NIGHT sensor support. Strategy: ${settings.nightMode}")

                }.build()
            }.build()

            services.add(sensors)

            val video = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_VID
                service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also { mediaSinkServiceBuilder ->
                    val explicitSoftwareHevc =
                        settings.videoCodec == VideoDecoder.CodecType.H265.settingsValue &&
                                settings.forceSoftwareDecoding &&
                                when (settings.softwareVideoDecoder) {
                                    com.andrerinas.openheadunit.utils.Settings.SoftwareVideoDecoder.BUNDLED_FFMPEG ->
                                        com.andrerinas.openheadunit.decoder.video.VideoDecoder.isBundledHevcDecoderAvailable()
                                    com.andrerinas.openheadunit.utils.Settings.SoftwareVideoDecoder.DEVICE_MEDIACODEC ->
                                        com.andrerinas.openheadunit.decoder.video.VideoDecoder.isHevcDecoderAvailable(includeSoftware = true)
                                }
                    val hevcAvailableForUserChoice =
                        com.andrerinas.openheadunit.decoder.video.VideoDecoder.isHevcSupported() || explicitSoftwareHevc
                    val hevcAvailableForHighResolution =
                        com.andrerinas.openheadunit.decoder.video.VideoDecoder.isHevcReliable() || explicitSoftwareHevc

                    val codecToRequest = when (settings.videoCodec) {
                        "H.265" -> if (hevcAvailableForUserChoice) {
                            Media.MediaCodecType.MEDIA_CODEC_VIDEO_H265
                        } else {
                            Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
                        }
                        "Auto" -> {
                            // Only use H.265 in Auto mode for 4K or if explicitly needed,
                            // otherwise prefer stable H.264
                            val negotiatedResolution = HeadUnitScreenConfig.negotiatedResolutionType
                            if (negotiatedResolution == Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._3840x2160 &&
                                com.andrerinas.openheadunit.decoder.video.VideoDecoder.isHevcReliable()) {
                                Media.MediaCodecType.MEDIA_CODEC_VIDEO_H265
                            } else {
                                Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
                            }
                        }
                        else -> Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
                    }

                    // Use HeadUnitScreenConfig for negotiated resolution and margins
                    val negotiatedResolution = HeadUnitScreenConfig.negotiatedResolutionType
                    val phoneWidthMargin = HeadUnitScreenConfig.getWidthMargin()
                    val phoneHeightMargin = HeadUnitScreenConfig.getHeightMargin()
                    // What goes on the wire, so a later metrics change can be seen to have left it.
                    HeadUnitScreenConfig.recordAnnouncedMargins(phoneWidthMargin, phoneHeightMargin)
                    // The pixel shape goes out here and nowhere else, so this is the canvas the
                    // rest of the session is committed to.
                    HeadUnitScreenConfig.recordAnnouncedCanvas()

                    // Enforce H.265 for 1440p resolution as required by Android Auto.
                    // Software HEVC is allowed only when the user explicitly selected it.
                    val effectiveCodec = if ((negotiatedResolution == Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2560x1440 ||
                        negotiatedResolution == Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1440x2560) &&
                        hevcAvailableForHighResolution) {
                        AppLog.i("Resolution is 1440p -> Enforcing H.265 codec")
                        Media.MediaCodecType.MEDIA_CODEC_VIDEO_H265
                    } else {
                        codecToRequest
                    }

                    mediaSinkServiceBuilder.availableType = effectiveCodec
                    mediaSinkServiceBuilder.audioType = Media.AudioStreamType.NONE
                    mediaSinkServiceBuilder.availableWhileInCall = true

                    // Decided once, above every line that reports it: the probe below must
                    // describe the profile actually being asked for, not the one in Settings.
                    val announcedFps = announcedFrameRate(context, settings)

                    AppLog.i("[ServiceDiscovery] NegotiatedResolution is: ${HeadUnitScreenConfig.getNegotiatedWidth()}x${HeadUnitScreenConfig.getNegotiatedHeight()}")
                    logNegotiatedCodecCapability(effectiveCodec, announcedFps)
                    logNarrowBandProfile(context, settings)
                    AppLog.i("[ServiceDiscovery] Margins are: ${phoneWidthMargin}x${phoneHeightMargin}")
                    AppLog.i("[ServiceDiscovery] PixelAspectRatioE4 is: ${HeadUnitScreenConfig.getPixelAspectRatioE4()} (10000 = square)")

                    mediaSinkServiceBuilder.addVideoConfigs(Control.Service.MediaSinkService.VideoConfiguration.newBuilder().apply {
                        codecResolution = negotiatedResolution
                        frameRate = when (announcedFps) {
                            30 -> Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30
                            else -> Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._60
                        }
                        setDensity(HeadUnitScreenConfig.getDensityDpi()) // Use actual densityDpi
                        setPixelAspectRatioE4(HeadUnitScreenConfig.getPixelAspectRatioE4())
                        setMarginWidth(phoneWidthMargin)
                        setMarginHeight(phoneHeightMargin)
                        setVideoCodecType(effectiveCodec)
                    }.build())
                }.build()
            }.build()

            // The auxiliary display, when the user asked for one and it is attached. It goes on its
            // own channel: a second config on the video channel is what the phone ends the session
            // over, with MULTIPLE_DISPLAY_CONFIGS.
            val auxVideo = auxVideoService(context, settings)
            if (auxVideo == null) {
                services.add(video)
            } else {
                // Declared only here, so a unit without a second display sends the bytes it always did.
                services.add(video.toBuilder().also { builder ->
                    builder.mediaSinkServiceBuilder.displayId = 0
                    builder.mediaSinkServiceBuilder.displayType = Control.DisplayType.DISPLAY_TYPE_MAIN
                }.build())
                services.add(auxVideo)
            }

            val input = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_INP
                service.inputSourceService = Control.Service.InputSourceService.newBuilder().also {
                    it.touchscreen = Control.Service.InputSourceService.TouchConfig.newBuilder().apply {
                        setWidth(HeadUnitScreenConfig.getNegotiatedWidth()) // Use negotiated width
                        setHeight(HeadUnitScreenConfig.getNegotiatedHeight()) // Use negotiated height
                    }.build()

                    if (settings.enableRotary) {
                        AppLog.i("[ServiceDiscovery] Announcing Rotary/Touchpad support")
                        it.touchpad = Control.Service.InputSourceService.TouchConfig.newBuilder().apply {
                            setWidth(HeadUnitScreenConfig.getNegotiatedWidth())
                            setHeight(HeadUnitScreenConfig.getNegotiatedHeight())
                        }.build()
                    }

                    it.addAllKeycodesSupported(KeyCode.supported)
                }.build()
            }.build()

            services.add(input)

            val audioType = if (announcesAac(context, settings)) Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC else Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM

            // Always add Audio2 (System Sounds) to keep connection alive
            val audio2 = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_AU2
                service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also {
                    it.availableType = audioType
                    it.audioType = Media.AudioStreamType.SYSTEM
                    it.addAudioConfigs(AudioConfigs.get(Channel.ID_AU2))
                }.build()
            }.build()
            services.add(audio2)

            // Asked of the session, not of SelfLauncherManager.isActive: that flag is set before
            // the launchers run and a failed launch left it set, so a Native AA session announced
            // system sounds only and had no audio at all. See AudioSinkAnnouncementPolicy.
            val isSelfModeSession = App.provide(context).commManager.isLoopbackSession
            if (AudioSinkAnnouncementPolicy.announcesMediaAndSpeech(settings.enableAudioSink, isSelfModeSession)) {
                val audio1 = Control.Service.newBuilder().also { service ->
                    service.id = Channel.ID_AU1
                    service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also {
                        it.availableType = audioType
                        it.audioType = Media.AudioStreamType.SPEECH
                        it.addAudioConfigs(AudioConfigs.get(Channel.ID_AU1))
                    }.build()
                }.build()
                services.add(audio1)

                val audio0 = Control.Service.newBuilder().also { service ->
                    service.id = Channel.ID_AUD
                    service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also {
                        it.availableType = audioType
                        it.audioType = Media.AudioStreamType.MEDIA
                        it.addAudioConfigs(AudioConfigs.get(Channel.ID_AUD))
                    }.build()
                }.build()
                services.add(audio0)
            } else if (!settings.enableAudioSink) {
                // Without this line a muted head unit is indistinguishable from a broken one. The
                // channels are never declared, so the phone never opens them, so nothing about the
                // silence appears anywhere in the log and every audio instrument reads zero. It
                // has already cost one test round. Named in the user's terms so a reporter can act
                // on it, the same way the Bluetooth service does below.
                AppLog.i("Audio sink is off in Settings. Skipping the media and speech audio " +
                        "channels - the phone will not send audio and this is not a fault")
            } else {
                AppLog.i("Self Mode is projecting this device to itself, so the media and speech " +
                        "audio channels are skipped - this is not a fault")
            }

            // Microphone Service (Channel 7), announced only when this head unit will record.
            // Advertising it and then declining every request leaves the assistant with nothing:
            // the phone chooses its recorder once at projection start, and takes its own only for a
            // motorcycle that offers it no microphone. Omitting it is safe only because we claim a
            // motorcycle - the phone aborts connection setup with "No audio/mic" for any other
            // type, so the two go together and neither works alone.
            // The format comes from MicCaptureFormat so the announcement and the capture cannot
            // drift again, and it is never anything but 16 kHz: the phone validates this config and
            // rejects everything outside {16000, 48000} Hz, 16 bits, 1 or 2 channels. 48 kHz is in
            // that set for the media channel, not for this one - AudioConfiguration is shared by
            // every audio service and the validator accepts the union.
            if (settings.useHeadUnitMicrophone) {
                val mic = Control.Service.newBuilder().also { service ->
                    service.id = Channel.ID_MIC
                    service.mediaSourceService = Control.Service.MediaSourceService.newBuilder().also {
                        it.type = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
                        it.audioConfig = Media.AudioConfiguration.newBuilder().apply {
                            sampleRate = MicCaptureFormat.SAMPLE_RATE_HZ
                            numberOfBits = MicCaptureFormat.BITS
                            numberOfChannels = MicCaptureFormat.CHANNELS
                        }.build()
                    }.build()
                }.build()
                services.add(mic)
            } else {
                AppLog.i("Head unit microphone is off in Settings. Skipping the microphone " +
                        "service - the phone is told this head unit cannot record, no voice " +
                        "request will arrive here, and this is not a fault. This needs the phone " +
                        "to keep our motorcycle claim, which Android 10 and older do not, so a " +
                        "session that connects once and then stops on an older phone may be this")
            }

            // Bluetooth Service
            if (settings.bluetoothAddress.isNotEmpty()) {
                val bluetooth = Control.Service.newBuilder().also { service ->
                    service.id = Channel.ID_BTH
                    service.bluetoothService = Control.Service.BluetoothService.newBuilder().also {
                        it.carAddress = settings.bluetoothAddress
                        // Pairing methods, not profiles: the old A2DP/HFP names on these two
                        // values said otherwise. The values are unchanged, and numeric
                        // comparison plus PIN is what Android's own stack offers a phone.
                        it.addAllSupportedPairingMethods(
                                listOf(Control.BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON,
                                        Control.BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN)
                        )
                    }.build()
                }.build()
                services.add(bluetooth)
            } else {
                // What the omission costs, in the user's terms. Android Auto keeps telephony
                // disabled until a hands-free link is up, and this is the message that tells the
                // phone where to connect one - so a blank field is why calls stay on the phone.
                AppLog.i("BT MAC Address is empty, so no Bluetooth service is announced. The phone " +
                    "is not told where to connect hands-free, and Android Auto keeps phone calls " +
                    "on the phone until it is")
            }

            val mediaPlaybackStatus = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_MPB
                service.mediaPlaybackService = Control.Service.MediaPlaybackStatusService.newBuilder().build()
            }.build()
            services.add(mediaPlaybackStatus)

            // Navigation Status Service — head unit receives turn-by-turn data from any AA nav app
            val navigationStatus = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_NAV
                service.navigationStatusService = Control.Service.NavigationStatusService.newBuilder()
                    .setMinimumIntervalMs(1000)
                    .setType(Control.Service.NavigationStatusService.ClusterType.ImageCodesOnly)
                    .build()
            }.build()
            services.add(navigationStatus)

            val sessionConfig = (if (settings.hideClock) 0x01 else 0) or
                (if (settings.hidePhoneSignal) 0x02 else 0) or
                (if (settings.hideBatteryLevel) 0x04 else 0)
            // 0x08 is "CAN_PLAY_NATIVE_MEDIA_DURING_VR"

            // Every type gets its own announced id. The phone looks its stored record up by
            // make/model/year and a hash of this id, and on a hit it stamps the stored vehicle type
            // over the one we declare. A different id misses that lookup, so our claim survives;
            // reusing one would make a changed type arrive as the old one.
            val vehicleType = VehicleTypePolicy.vehicleType(
                settings.vehicleType, settings.useHeadUnitMicrophone)
            val announcedVehicleId = VehicleIdentityPolicy.vehicleId(settings.vehicleId, vehicleType)

            return Control.ServiceDiscoveryResponse.newBuilder().apply {
                make = settings.vehicleMake
                model = settings.vehicleModel // fun fact: AA checks internally if it ends with "truck"?!
                year = settings.vehicleYear
                vehicleId = announcedVehicleId
                headUnitModel = settings.headUnitModel
                headUnitMake = settings.headUnitMake
                headUnitSoftwareBuild = "1"
                headUnitSoftwareVersion = "0.1.0"
                driverPosition = if (settings.rightHandDrive) Control.DriverPosition.DRIVER_POSITION_RIGHT else Control.DriverPosition.DRIVER_POSITION_LEFT
                canPlayNativeMediaDuringVr = false
                hideProjectedClock = settings.hideClock
                sessionConfiguration = sessionConfig
                setDisplayName(settings.vehicleDisplayName)

                setHeadunitInfo(com.andrerinas.openheadunit.aap.protocol.proto.Common.HeadUnitInfo.newBuilder().apply {
                    setHeadUnitMake(settings.headUnitMake)
                    setHeadUnitModel(settings.headUnitModel)
                    setMake(settings.vehicleMake)
                    setModel(settings.vehicleModel)
                    setYear(settings.vehicleYear)
                    setVehicleId(announcedVehicleId)
                    setHeadUnitSoftwareBuild("1")
                    setHeadUnitSoftwareVersion("0.1.0")
                    // The phone stores this per head unit and reads it in a dozen places, and an
                    // absent field reads as a car. A motorcycle is the only claim it answers by
                    // recording with its own microphone, so the microphone setting overrides the
                    // user's choice here.
                    setVehicleType(vehicleType)
                }.build())

                ConnectionConfigPolicy.announce(settings.announceConnectionConfiguration)?.let {
                    setConnectionConfiguration(it)
                    AppLog.i("[ServiceDiscovery] Asking for ping timeout ${it.pingConfiguration.timeoutMs}ms " +
                            "and ${it.wirelessTcpConfiguration.socketReceiveBufferSize}B socket buffers")
                }

                addAllServices(services)
            }.build()
        }

        /**
         * Records whether a decoder on this device claims it can carry the profile we are about to
         * ask the phone for. Diagnostic only; nothing acts on the answer.
         *
         * This is the one place the codec is decided (the 1440p rule above overrides the user's
         * choice), and nothing used to ask the decoder anything before making it - so a reporter
         * log could never say whether the forced profile fit the hardware.
         *
         * A `sustains=false` WARN here is not by itself grounds to step the profile down: it has
         * been seen from a unit whose throughput proved the decoder was keeping up, with the
         * artifacts coming off the wire instead. Revisit the rule only when a log shows the decoder
         * itself failing behind one of these lines.
         */
        private fun logNegotiatedCodecCapability(codec: Media.MediaCodecType, fps: Int) {
            val mime = when (codec) {
                Media.MediaCodecType.MEDIA_CODEC_VIDEO_H265 -> VideoDecoder.CodecType.H265.mimeType
                Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP -> VideoDecoder.CodecType.H264.mimeType
                else -> return
            }
            val width = HeadUnitScreenConfig.getNegotiatedWidth()
            val height = HeadUnitScreenConfig.getNegotiatedHeight()
            if (width <= 0 || height <= 0) return
            val capability = com.andrerinas.openheadunit.decoder.video.DecoderCapabilityReport
                .query(mime, width, height, fps)
            if (capability == null) {
                AppLog.i("[ServiceDiscovery] No decoder capability available for $mime at ${width}x$height")
                return
            }
            if (capability.adequate) {
                AppLog.i("[ServiceDiscovery] Negotiating a profile this device claims to carry: $capability")
            } else {
                AppLog.w(
                    "[ServiceDiscovery] Negotiating a profile no decoder here claims to carry: $capability. " +
                        "Frames shed under load and the artifacts that follow are the expected consequence."
                )
            }
        }

        /**
         * The frame rate to announce: the user's, lowered on a radio with no 5 GHz band.
         *
         * Never throws. A band read that fails leaves the setting alone, because lowering somebody's
         * picture on a question the platform would not answer is the one outcome worth avoiding.
         */
        private fun announcedFrameRate(
            context: Context,
            settings: com.andrerinas.openheadunit.utils.Settings
        ): Int = try {
            NarrowBandProfilePolicy.cappedFrameRate(
                fpsLimit = settings.fpsLimit,
                supports5Ghz = WifiBandCapability.supports5Ghz(context),
                wirelessSession = App.provide(context).commManager.isWirelessSession,
                capEnabled = settings.narrowBandProfileCap,
                sessionFrequencyMhz = WifiBandCapability.sessionFrequencyMhz(),
                bandUnreadable = WifiBandCapability.bandUnreadable(),
                linkProvedTooSlow = settings.videoProfileStarvationCap,
            )
        } catch (e: Exception) {
            AppLog.d("[ServiceDiscovery] could not evaluate the band cap: ${e.message}")
            settings.fpsLimit
        }

        /**
         * The audio codec to announce: the user's AAC choice, or AAC from the band cap. Never
         * throws, for the same reason as [announcedFrameRate]. The decoder does not read this; it
         * takes the codec from the phone's Media Sink Setup, which cannot disagree with the wire.
         */
        private fun announcesAac(
            context: Context,
            settings: com.andrerinas.openheadunit.utils.Settings
        ): Boolean = try {
            val aac = NarrowBandProfilePolicy.useAac(
                userChoice = settings.useAacAudio,
                supports5Ghz = WifiBandCapability.supports5Ghz(context),
                wirelessSession = App.provide(context).commManager.isWirelessSession,
                capEnabled = settings.narrowBandProfileCap,
                sessionFrequencyMhz = WifiBandCapability.sessionFrequencyMhz(),
                bandUnreadable = WifiBandCapability.bandUnreadable(),
                linkProvedTooSlow = settings.videoProfileStarvationCap,
            )
            if (aac && !settings.useAacAudio) {
                AppLog.i("[ServiceDiscovery] AAC audio announced by the 2.4 GHz cap (Use AAC Audio is off)")
            }
            aac
        } catch (e: Exception) {
            AppLog.d("[ServiceDiscovery] could not evaluate the band audio codec: ${e.message}")
            settings.useAacAudio
        }

        /**
         * Names the one case where the band and the profile we are about to ask for are known to be
         * a bad pair, and says what was done about it. Says nothing on every other unit.
         *
         * Here rather than in `WifiDirectManager` because this is where the frame rate is decided,
         * and the two halves of the advice have to be read together to mean anything.
         */
        private fun logNarrowBandProfile(context: Context, settings: com.andrerinas.openheadunit.utils.Settings) {
            val advice = try {
                NarrowBandProfilePolicy.advice(
                    supports5Ghz = WifiBandCapability.supports5Ghz(context),
                    fpsLimit = settings.fpsLimit,
                    wirelessSession = App.provide(context).commManager.isWirelessSession,
                    capEnabled = settings.narrowBandProfileCap,
                    sessionFrequencyMhz = WifiBandCapability.sessionFrequencyMhz(),
                    bandUnreadable = WifiBandCapability.bandUnreadable(),
                    linkProvedTooSlow = settings.videoProfileStarvationCap,
                )
            } catch (e: Exception) {
                // Service discovery must not fail over a diagnostic. A missing line is a missing
                // line; a thrown one costs the session.
                AppLog.d("[ServiceDiscovery] could not evaluate the band advice: ${e.message}")
                null
            }
            advice?.let { AppLog.w("[ServiceDiscovery] $it") }
        }

        private fun makeSensorType(type: Sensors.SensorType): Control.Service.SensorSourceService.Sensor {
            return Control.Service.SensorSourceService.Sensor.newBuilder()
                    .setType(type).build()
        }
    }
}
