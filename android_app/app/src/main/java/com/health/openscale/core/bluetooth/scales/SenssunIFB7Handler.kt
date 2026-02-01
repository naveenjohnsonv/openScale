/*
 * openScale
 * Copyright (C) 2025
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.health.openscale.core.bluetooth.scales

import android.bluetooth.le.ScanResult
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager

/**
 * Handler for Senssun IF_B7 scales (Broadcast/Advertising).
 * Rebranded as Moving Life BS 161, etc.
 *
 * Protocol:
 * Manufacturer ID: 0x8500
 * Byte Layout:
 * [0..8]  Header/MAC
 * [9]     Separator (0x01)
 * [10-11] Weight (Big Endian, /100)
 * [12-13] Impedance (Big Endian)
 * [14]    Status (0xA1 = Stable)
 */
class SenssunIFB7Handler : ScaleDeviceHandler() {

    companion object {
        private const val TAG = "SenssunIFB7Handler"
        private const val MANUFACTURER_ID = 0x8500
    }

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name?.uppercase() ?: ""
        // Matches "IF_B7" or similar variations
        if (name.startsWith("IF_B7")) {
            return DeviceSupport(
                displayName = "Senssun / Moving Life (IF_B7)",
                capabilities = setOf(
                    DeviceCapability.LIVE_WEIGHT_STREAM,
                    DeviceCapability.BODY_COMPOSITION
                ),
                implemented = setOf(
                    DeviceCapability.LIVE_WEIGHT_STREAM,
                    DeviceCapability.BODY_COMPOSITION
                ),
                linkMode = LinkMode.BROADCAST_ONLY
            )
        }
        return null
    }

    override fun onAdvertisement(scanResult: ScanResult, user: ScaleUser): BroadcastAction {
        val manufacturerData = scanResult.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)
            ?: return BroadcastAction.IGNORED

        // Minimum length check (15 bytes based on your logs)
        if (manufacturerData.size < 15) return BroadcastAction.IGNORED

        // Byte 14 is Status. 0xA1 indicates stable/valid.
        val status = manufacturerData[14].toInt() and 0xFF
        // We look for 0xA0 mask (0xA1 matches 0xA0)
        val isStable = (status and 0xA0) == 0xA0

        // Parse Weight: Bytes 10 & 11 (Big Endian)
        val weightRaw = ((manufacturerData[10].toInt() and 0xFF) shl 8) or (manufacturerData[11].toInt() and 0xFF)
        val weightKg = weightRaw / 100.0f

        // Parse Impedance: Bytes 12 & 13 (Big Endian)
        val impedanceRaw = ((manufacturerData[12].toInt() and 0xFF) shl 8) or (manufacturerData[13].toInt() and 0xFF)

        // Debug logging
        // LogManager.d(TAG, "IF_B7 Raw: W=$weightKg Imp=$impedanceRaw Stable=$isStable")

        // 1. If not stable, update the live view but keep scanning
        if (!isStable && weightKg > 0) {
            val liveMeasurement = ScaleMeasurement().apply { weight = weightKg }
            publish(liveMeasurement) // Updates the UI number
            return BroadcastAction.CONSUMED_KEEP_SCANNING
        }

        // 2. If stable, publish final result and STOP scanning
        if (isStable && weightKg > 0) {
            val finalMeasurement = ScaleMeasurement().apply {
                weight = weightKg
                if (impedanceRaw > 0) {
                    impedance = impedanceRaw.toFloat()
                }
            }
            publish(finalMeasurement)
            return BroadcastAction.CONSUMED_STOP
        }

        return BroadcastAction.IGNORED
    }
}
