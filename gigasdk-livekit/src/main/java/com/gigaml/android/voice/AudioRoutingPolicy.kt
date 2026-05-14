package com.gigaml.android.voice

import android.media.AudioDeviceInfo

internal object AudioRoutingPolicy {
    private val externalOutputDeviceTypes = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
    )

    fun prefersBuiltInSpeaker(outputDeviceTypes: Iterable<Int>): Boolean =
        outputDeviceTypes.none(::isExternalOutputDeviceType)

    fun isExternalOutputDeviceType(deviceType: Int): Boolean =
        deviceType in externalOutputDeviceTypes
}
