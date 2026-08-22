package com.andrerinas.openheadunit.aap.protocol.messages

import android.content.Context
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapMessage
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.KeyCode
import com.andrerinas.openheadunit.aap.protocol.AudioConfigs
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.aap.protocol.proto.Sensors
import com.andrerinas.openheadunit.decoder.VideoDecoder
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import com.google.protobuf.Message

class ServiceDiscoveryResponse(private val context: Context)
    : AapMessage(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE, makeProto(context)) {

    companion object {
        private fun makeProto(context: Context): Message {
            val settings = App.provide(context).settings
            // Initialize HeadUnitScreenConfig with actual physical screen dimensions
            HeadUnitScreenConfig.init(context, context.resources.displayMetrics, settings)

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
                                        com.andrerinas.openheadunit.decoder.VideoDecoder.isBundledHevcDecoderAvailable()
                                    com.andrerinas.openheadunit.utils.Settings.SoftwareVideoDecoder.DEVICE_MEDIACODEC ->
                                        com.andrerinas.openheadunit.decoder.VideoDecoder.isHevcDecoderAvailable(includeSoftware = true)
                                }
                    val hevcAvailableForUserChoice =
                        com.andrerinas.openheadunit.decoder.VideoDecoder.isHevcSupported() || explicitSoftwareHevc
                    val hevcAvailableForHighResolution =
                        com.andrerinas.openheadunit.decoder.VideoDecoder.isHevcReliable() || explicitSoftwareHevc

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
                                com.andrerinas.openheadunit.decoder.VideoDecoder.isHevcReliable()) {
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

                    AppLog.i("[ServiceDiscovery] NegotiatedResolution is: ${HeadUnitScreenConfig.getNegotiatedWidth()}x${HeadUnitScreenConfig.getNegotiatedHeight()}")
                    logNegotiatedCodecCapability(effectiveCodec, settings)
                    AppLog.i("[ServiceDiscovery] Margins are: ${phoneWidthMargin}x${phoneHeightMargin}")

                    mediaSinkServiceBuilder.addVideoConfigs(Control.Service.MediaSinkService.VideoConfiguration.newBuilder().apply {
                        codecResolution = negotiatedResolution
                        frameRate = when (settings.fpsLimit) {
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

            services.add(video)

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

            val audioType = if (settings.useAacAudio) Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC else Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM

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

            if (settings.enableAudioSink) {
                if (!AapService.selfMode) {
                    val audio1 = Control.Service.newBuilder().also { service ->
                        service.id = Channel.ID_AU1
                        service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also {
                            it.availableType = audioType
                            it.audioType = Media.AudioStreamType.SPEECH
                            it.addAudioConfigs(AudioConfigs.get(Channel.ID_AU1))
                        }.build()
                    }.build()
                    services.add(audio1)
                }

                if (!AapService.selfMode) {
                    val audio0 = Control.Service.newBuilder().also { service ->
                        service.id = Channel.ID_AUD
                        service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also {
                            it.availableType = audioType
                            it.audioType = Media.AudioStreamType.MEDIA
                            it.addAudioConfigs(AudioConfigs.get(Channel.ID_AUD))
                        }.build()
                    }.build()
                    services.add(audio0)
                }
            } else {
                // Without this line a muted head unit is indistinguishable from a broken one. The
                // channels are never declared, so the phone never opens them, so nothing about the
                // silence appears anywhere in the log and every audio instrument reads zero. It
                // has already cost one test round. Named in the user's terms so a reporter can act
                // on it, the same way the Bluetooth service does below.
                AppLog.i("Audio sink is off in Settings. Skipping the media and speech audio " +
                        "channels - the phone will not send audio and this is not a fault")
            }

            // Microphone Service (Channel 7) - Always required for AA connection (Assistant)
            val mic = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_MIC
                service.mediaSourceService = Control.Service.MediaSourceService.newBuilder().also {
                    it.type = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
                    it.audioConfig = Media.AudioConfiguration.newBuilder().apply {
                        sampleRate = 16000
                        numberOfBits = 16
                        numberOfChannels = 1
                    }.build()
                }.build()
            }.build()
            services.add(mic)

            // Bluetooth Service
            if (settings.bluetoothAddress.isNotEmpty()) {
                val bluetooth = Control.Service.newBuilder().also { service ->
                    service.id = Channel.ID_BTH
                    service.bluetoothService = Control.Service.BluetoothService.newBuilder().also {
                        it.carAddress = settings.bluetoothAddress
                        it.addAllSupportedPairingMethods(
                                listOf(Control.BluetoothPairingMethod.A2DP,
                                        Control.BluetoothPairingMethod.HFP)
                        )
                    }.build()
                }.build()
                services.add(bluetooth)
            } else {
                AppLog.i("BT MAC Address is empty. Skip bluetooth service")
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

            return Control.ServiceDiscoveryResponse.newBuilder().apply {
                make = settings.vehicleMake
                model = settings.vehicleModel
                year = settings.vehicleYear
                vehicleId = settings.vehicleId
                headUnitModel = settings.headUnitModel
                headUnitMake = settings.headUnitMake
                headUnitSoftwareBuild = "1"
                headUnitSoftwareVersion = "0.1.0"
                driverPosition = if (settings.rightHandDrive) Control.DriverPosition.DRIVER_POSITION_RIGHT else Control.DriverPosition.DRIVER_POSITION_LEFT
                canPlayNativeMediaDuringVr = false
                hideProjectedClock = false
                setDisplayName(settings.vehicleDisplayName)

                setHeadunitInfo(com.andrerinas.openheadunit.aap.protocol.proto.Common.HeadUnitInfo.newBuilder().apply {
                    setHeadUnitMake(settings.headUnitMake)
                    setHeadUnitModel(settings.headUnitModel)
                    setMake(settings.vehicleMake)
                    setModel(settings.vehicleModel)
                    setYear(settings.vehicleYear)
                    setVehicleId(settings.vehicleId)
                    setHeadUnitSoftwareBuild("1")
                    setHeadUnitSoftwareVersion("0.1.0")
                }.build())

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
        private fun logNegotiatedCodecCapability(codec: Media.MediaCodecType, settings: com.andrerinas.openheadunit.utils.Settings) {
            val mime = when (codec) {
                Media.MediaCodecType.MEDIA_CODEC_VIDEO_H265 -> VideoDecoder.CodecType.H265.mimeType
                Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP -> VideoDecoder.CodecType.H264.mimeType
                else -> return
            }
            val width = HeadUnitScreenConfig.getNegotiatedWidth()
            val height = HeadUnitScreenConfig.getNegotiatedHeight()
            if (width <= 0 || height <= 0) return
            val capability = com.andrerinas.openheadunit.decoder.DecoderCapabilityReport
                .query(mime, width, height, settings.fpsLimit)
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

        private fun makeSensorType(type: Sensors.SensorType): Control.Service.SensorSourceService.Sensor {
            return Control.Service.SensorSourceService.Sensor.newBuilder()
                    .setType(type).build()
        }
    }
}
