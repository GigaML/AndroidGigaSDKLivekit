package com.gigaml.android.voice

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRoutingPolicyTest {
    @Test
    fun builtInOutputsKeepSpeakerRoutingEnabled() {
        assertTrue(
            AudioRoutingPolicy.prefersBuiltInSpeaker(
                listOf(
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                ),
            ),
        )
    }

    @Test
    fun wiredAndBluetoothOutputsDisableForcedSpeakerRouting() {
        assertFalse(
            AudioRoutingPolicy.prefersBuiltInSpeaker(
                listOf(
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                ),
            ),
        )
        assertFalse(
            AudioRoutingPolicy.prefersBuiltInSpeaker(
                listOf(
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                ),
            ),
        )
        assertFalse(
            AudioRoutingPolicy.prefersBuiltInSpeaker(
                listOf(
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                ),
            ),
        )
    }

    @Test
    fun usbAndAccessibilityOutputsDisableForcedSpeakerRouting() {
        assertFalse(
            AudioRoutingPolicy.prefersBuiltInSpeaker(
                listOf(
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                ),
            ),
        )
        assertFalse(
            AudioRoutingPolicy.prefersBuiltInSpeaker(
                listOf(
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    AudioDeviceInfo.TYPE_HEARING_AID,
                ),
            ),
        )
    }
}
