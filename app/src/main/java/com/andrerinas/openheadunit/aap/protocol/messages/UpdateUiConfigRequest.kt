package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.aap.AapMessage
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media

/**
 * Message sent on the Video channel (Channel 3) to update the headunit's UI config
 * (margins, insets, theme) without a full session reconnect.
 * The active GAL contract supplies the whole config so visibility survives a margin update.
 */
class UpdateUiConfigRequest(uiConfig: Media.UiConfig) : AapMessage(
    Channel.ID_VID,
    Media.MsgType.MEDIA_MESSAGE_UPDATE_UI_CONFIG_REQUEST_VALUE,
    Media.UpdateUiConfigRequest.newBuilder().setUiConfig(uiConfig).build()
)

