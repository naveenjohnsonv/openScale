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
 * Manufacturer ID: 0x8500 (Often parsed as 0x0085 by Android due to Endianness)
 * Byte Layout (relative to data start):
 * [0..2]  Header (02 03 11)
 * [3..8]  MAC Address
 * [9]     Separator (0x01)
 * [10-11] Weight (Big Endian, /100)
 * [12-13] Impedance (Big Endian)
 * [14]    Status (0xA1 = Stable)
 */
class SenssunIFB7Handler : ScaleDeviceHandler() {

    companion object {
        private const val TAG = "SenssunIFB7Handler"
    }

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name?.uppercase() ?: ""
        // Matches "IF_B7"
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
        val scanRecord = scanResult.scanRecord ?: return BroadcastAction.IGNORED
        val msd = scanRecord.manufacturerSpecificData ?: return BroadcastAction.IGNORED

        var manufacturerData: ByteArray? = null

        // iterate through all manufacturer data to find the one with our header
        // This solves the 0x8500 vs 0x0085 endianness issue
        for (i in 0 until msd.size()) {
            val bytes = msd.valueAt(i)
            // Check for the Fixed Header: 02 03 11
            if (bytes != null && bytes.size >= 15 &&
                bytes[0] == 0x02.toByte() &&
                bytes[1] == 0x03.toByte() &&
                bytes[2] == 0x11.toByte()) {
                manufacturerData = bytes
                break
            }
        }

        if (manufacturerData == null) {
            return BroadcastAction.IGNORED
        }

        // Byte 14 is Status. 0xA1 indicates stable/valid.
        // We mask with 0xA0 to catch both 0xA1 and similar stable states
        val status = manufacturerData[14].toInt() and 0xFF
        val isStable = (status and 0xA0) == 0xA0

        // Parse Weight: Bytes 10 & 11 (Big Endian)
        val weightRaw = ((manufacturerData[10].toInt() and 0xFF) shl 8) or (manufacturerData[11].toInt() and 0xFF)
        val weightKg = weightRaw / 100.0f

        // Parse Impedance: Bytes 12 & 13 (Big Endian)
        val impedanceRaw = ((manufacturerData[12].toInt() and 0xFF) shl 8) or (manufacturerData[13].toInt() and 0xFF)

        // Debug logging to verify data reception in Logcat
        // LogManager.d(TAG, "Parsing: W=$weightKg kg, Imp=$impedanceRaw, Stable=$isStable")

        // 1. If not stable, update the live view but keep scanning
        if (!isStable && weightKg > 0) {
            val liveMeasurement = ScaleMeasurement().apply { weight = weightKg }
            publish(liveMeasurement) 
            return BroadcastAction.CONSUMED_KEEP_SCANNING
        }

        // 2. If stable, publish final result and STOP scanning
        if (isStable && weightKg > 0) {
            val finalMeasurement = ScaleMeasurement().apply {
                weight = weightKg
                if (impedanceRaw > 0) {
                    impedance = impedanceRaw.toDouble() 
                }
            }
            publish(finalMeasurement)
            return BroadcastAction.CONSUMED_STOP
        }

        return BroadcastAction.IGNORED
    }
}
