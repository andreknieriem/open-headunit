package com.andrerinas.openheadunit.connection.carplaynative.usb

/**
 * Which USB devices are an iPhone, whether an attach may open one, and how the CarPlay mode request is read.
 * Ported from CarLink (https://github.com/F-e-n-y-x/CarLink) `detect/PhoneClassifier.kt` and LIVI (https://github.com/f-io/LIVI), crate iap2-usbmux.
 */
object IphoneUsbPolicy {
    const val APPLE_VENDOR_ID = 0x05AC
    const val API_FLOOR = 21

    /** The unit has the CarPlay configuration once the iPhone shows this many configurations. */
    const val CARPLAY_CONFIGURATION_COUNT = 6

    /** Vendor control IN that asks the iPhone for CarPlay mode, and the one that reads its current mode. */
    const val MODE_REQUEST_TYPE = 0xC0
    const val MODE_REQUEST = 0x52
    const val MODE_REQUEST_INDEX = 4
    const val MODE_REQUEST_LENGTH = 1
    const val MODE_READ_REQUEST = 0x45
    const val MODE_READ_LENGTH = 4

    private const val PID_IPAD = 0x12AB
    private const val PID_IPOD = 0x12AA

    /** The order is the order of the checks. The first row that holds is the answer. */
    enum class Attach {
        NotAnIphone, Disabled, BelowApiFloor, NoTunnel, NoSigner,
        AlreadyLive, UserExited, RefusedAaLive, AskPermission, RequestMode, Open,
    }

    /** An iPhone is an Apple device that is no iPad or iPod: by product string, else by the product ids those use. */
    fun isIphone(vendorId: Int, productId: Int, productName: String?): Boolean {
        if (vendorId != APPLE_VENDOR_ID) return false
        if (productName != null) return !productName.contains("iPad", ignoreCase = true) && !productName.contains("iPod", ignoreCase = true)
        return productId != PID_IPAD && productId != PID_IPOD
    }

    fun attach(
        isIphone: Boolean,
        enabled: Boolean,
        sdk: Int,
        tunAvailable: Boolean,
        signerAvailable: Boolean,
        sessionLive: Boolean,
        userExited: Boolean,
        aaSessionLive: Boolean,
        hasPermission: Boolean,
        configurationCount: Int,
    ): Attach = when {
        !isIphone -> Attach.NotAnIphone
        !enabled -> Attach.Disabled
        sdk < API_FLOOR -> Attach.BelowApiFloor
        !tunAvailable -> Attach.NoTunnel
        !signerAvailable -> Attach.NoSigner
        sessionLive -> Attach.AlreadyLive
        userExited -> Attach.UserExited
        aaSessionLive -> Attach.RefusedAaLive
        !hasPermission -> Attach.AskPermission
        needsModeRequest(configurationCount) -> Attach.RequestMode
        else -> Attach.Open
    }

    fun needsModeRequest(configurationCount: Int): Boolean = configurationCount < CARPLAY_CONFIGURATION_COUNT

    /** The iPhone accepts with one byte. Any other count, -1 for a stalled request included, is a refusal. */
    fun modeAnswerIsRefusal(transferred: Int): Boolean = transferred != MODE_REQUEST_LENGTH

    fun belowFloorLine(sdk: Int): String = "CarPlayNative: iPhone attached; native CarPlay needs API $API_FLOOR, this unit is $sdk"

    fun refusalLine(modeBytes: ByteArray): String =
        "CarPlayNative: the iPhone refused wired CarPlay mode (mode ${modeBytes.joinToString("") { "%02x".format(it) }})"
}
