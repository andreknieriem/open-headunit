package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.utils.ProjectionMargins

internal data class GalDisplaySettings(val hideClock: Boolean, val hideBattery: Boolean, val hideSignal: Boolean)

/** Immutable visibility settings for discovery and subsequent UI updates in one session. */
internal class GalDisplayContract(private val settings: GalDisplaySettings, private val modern: Boolean) {
    /** Both discovery encodings must describe the same connection-time visibility settings. */
    fun applyDiscoveryVisibility(builder: Control.ServiceDiscoveryResponse.Builder) {
        builder.hideProjectedClock = settings.hideClock
        // SessionConfiguration uses clock/signal/battery bits; 0x08 is native media during VR.
        builder.sessionConfiguration = (if (settings.hideClock) 0x01 else 0) or
            (if (settings.hideSignal) 0x02 else 0) or
            (if (settings.hideBattery) 0x04 else 0)
    }

    private val elements: List<Media.UiConfig.UiElement> = buildList {
        if (modern && settings.hideClock) add(Media.UiConfig.UiElement.CLOCK)
        if (modern && settings.hideBattery) add(Media.UiConfig.UiElement.BATTERY_LEVEL)
        if (modern && settings.hideSignal) add(Media.UiConfig.UiElement.PHONE_SIGNAL)
    }

    fun initial(width: Int, height: Int): Media.UiConfig? =
        if (modern) margins(ProjectionMargins.left(width), ProjectionMargins.top(height),
            ProjectionMargins.right(width), ProjectionMargins.bottom(height)) else null

    fun margins(left: Int, top: Int, right: Int, bottom: Int): Media.UiConfig =
        Media.UiConfig.newBuilder()
            .setMargins(Media.Insets.newBuilder().setLeft(left).setTop(top).setRight(right).setBottom(bottom))
            .addAllUiElements(elements)
            .build()
    // AA18's display parser uses UiConfig instead of legacy margins as soon as field11 is present.
    // GAL>=4.3 updates replace the entire config, so every update must retain these elements.
    // Do not change theme during a session; the sender rejects a theme change in this path.
}
