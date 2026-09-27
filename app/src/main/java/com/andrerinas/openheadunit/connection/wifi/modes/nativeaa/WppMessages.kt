package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
import com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStability

/**
 * Builds the WPP messages this head unit sends.
 *
 * The same bytes go out whether the exchange runs over RFCOMM or over TCP, so the two transports
 * share this rather than each building their own: a field that differs between them would be a
 * defect no log shows, because the phone answers a malformed message with silence.
 *
 * Pure, so the shapes that the phone silently discards can be asserted in a unit test.
 */
object WppMessages {

    /** The software build and version we report. Constants, not settings: nothing reads them back. */
    private const val SOFTWARE_BUILD = "1"
    private const val SOFTWARE_VERSION = "0.1.0"

    /** Android Auto's own rule for the body type, the same one ServiceDiscoveryResponse notes. */

    /**
     * The address to advertise when our own is not yet known. This message goes out before the
     * credentials resolve, and "0.0.0.0" is the phone's documented "dial the gateway" form. On a
     * network we host - P2P group owner or SoftAP - the gateway is us, so it stays correct even
     * when the address changes underneath it.
     */
    const val GATEWAY_ADDRESS = "0.0.0.0"

    /**
     * Where the phone can reach us over TCP. Built through [endpoint] so the blank-address
     * substitution happens in one place and the log line names the address that actually went out.
     */
    data class WppEndpoint(val ip: String, val port: Int)

    fun endpoint(ip: String, port: Int): WppEndpoint =
        WppEndpoint(ip.ifBlank { GATEWAY_ADDRESS }, port)

    /**
     * Type 4. Declares our protocol version and who we are, and with [endpoint] where the phone can
     * reach us over TCP on a later connection.
     *
     * The phone stores that endpoint against our Bluetooth address and dials it instead of running
     * the RFCOMM handshake again, so a null [endpoint] is the difference between a reconnect that
     * needs nothing on the phone and one that cannot happen at all. WppEndpointPolicy decides.
     *
     * [channelType] is the bands our access point can offer; null when the radio could not be read.
     */
    fun versionRequest(
        carInfo: Wireless.WppCarInfo,
        endpoint: WppEndpoint?,
        channelType: Wireless.WifiChannelType? = null
    ): Wireless.WifiVersionRequest =
        Wireless.WifiVersionRequest.newBuilder()
            .setMajor(WppHandshakeSession.WPP_VERSION_MAJOR)
            .setMinor(WppHandshakeSession.WPP_VERSION_MINOR)
            .setCarInfo(carInfo)
            .also { builder ->
                if (channelType != null) builder.supportedWifiChannelType = channelType
                if (endpoint != null) {
                    builder.setWppInfo(
                        Wireless.WifiProjectionProtocolInfo.newBuilder()
                            .setIpAddress(endpoint.ip)
                            .setPort(endpoint.port)
                            .build()
                    )
                }
            }
            .build()

    /**
     * Who we tell the phone we are. The same identity ServiceDiscoveryResponse announces, so the
     * two cannot drift.
     *
     * Not decorative: at protocol 4.1 the phone accepts the TCP endpoint only for a make on its own
     * allowlist. We clear that by announcing 4.2, and this stays accurate regardless.
     */
    fun carInfo(
        vehicleMake: String,
        vehicleModel: String,
        vehicleYear: String,
        vehicleId: String,
        headUnitMake: String,
        headUnitModel: String
    ): Wireless.WppCarInfo =
        Wireless.WppCarInfo.newBuilder()
            .setMake(vehicleMake)
            .setModel(vehicleModel)
            .setModelYear(vehicleYear)
            .setVehicleId(vehicleId)
            .setHeadUnitMake(headUnitMake)
            .setHeadUnitModel(headUnitModel)
            .setHeadUnitSoftwareBuild(SOFTWARE_BUILD)
            .setHeadUnitSoftwareVersion(SOFTWARE_VERSION)
            .build()

    /**
     * Type 10, refusing a dial and withdrawing the endpoint behind it.
     *
     * The reason is fixed and there is no parameter, because only two values are sendable at all:
     * the phone throws on UNKNOWN whatever the transport, and on either reason over RFCOMM. This
     * one says the setup the phone is dialling with is no longer valid, which is what a stale
     * endpoint is.
     */
    fun connectionRejection(): Wireless.WifiConnectionRejection =
        Wireless.WifiConnectionRejection.newBuilder()
            .setReason(Wireless.ConnectionRejectionReason.CONNECTION_REJECTION_REASON_INVALID_SETUP_TOKEN)
            .build()

    /** Type 1. Where to open the projection session once the phone is on our network. */
    fun startRequest(ip: String, port: Int): Wireless.WifiStartRequest =
        Wireless.WifiStartRequest.newBuilder()
            .setIpAddress(ip)
            .setPort(port)
            .setStatus(0)
            .build()

    /**
     * Type 3, the credentials.
     *
     * All five fields go out every time, including an empty [bssid] where we have no real address:
     * the schema the other implementations use marks bssid, security_mode and access_point_type
     * `required`, and aa-proxy-rs sets an empty string on the one path where it has no MAC rather
     * than dropping the field. Omitting it risks a strict parser rejecting the whole message, which
     * would surface as silence rather than as the specific refusal an empty one produces.
     *
     * [identity] picks the access-point type through [WppEndpointPolicy.keepsNetwork]: STATIC only
     * where the phone can safely keep this network, DYNAMIC otherwise, because a stored BSSID that
     * moves is one Android pins below Gearhead and only the user can clear.
     */
    fun infoResponse(
        ssid: String,
        key: String,
        bssid: String?,
        identity: GroupIdentityStability
    ): Wireless.WifiInfoResponse =
        Wireless.WifiInfoResponse.newBuilder()
            .setSsid(ssid)
            .setKey(key)
            .setSecurityMode(Wireless.SecurityMode.WPA2_PERSONAL)
            .setAccessPointType(
                if (WppEndpointPolicy.keepsNetwork(identity)) Wireless.AccessPointType.STATIC
                else Wireless.AccessPointType.DYNAMIC
            )
            .setBssid(bssid.orEmpty())
            .build()
}
