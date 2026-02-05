/*
 * openScale
 * Copyright (C) 2026
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
import com.health.openscale.core.bluetooth.libs.MiScaleLib
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import java.util.Date

/**
 * Handler for HealthSense BS161 (advertised internally as "IF_B7").
 * Manufacturer: Senssun (0x0085)
 */
class HealthSenseBS161Handler : ScaleDeviceHandler() {

    companion object {
        private const val TAG = "HealthSenseBS161Handler"
        private const val ADVERTISED_NAME_PREFIX = "IF_B7"
        
        // Protocol Constants
        private const val HEADER_BYTE_0 = 0x02.toByte()
        private const val HEADER_BYTE_1 = 0x03.toByte()
        private const val HEADER_BYTE_2 = 0x11.toByte()
        
        // Status bits (Byte 14)
        private const val STATUS_STABLE_MASK = 0xA0 

        // Time to wait for impedance after weight stabilizes
        private const val IMPEDANCE_WAIT_TIMEOUT_MS = 4000L
    }

    private var firstStableTime: Long = 0

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name?.uppercase() ?: ""
        if (name.startsWith(ADVERTISED_NAME_PREFIX)) {
            return DeviceSupport(
                displayName = "HealthSense BS161",
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

        // Find the specific manufacturer data chunk matching our header
        var data: ByteArray? = null
        for (i in 0 until msd.size()) {
            val bytes = msd.valueAt(i)
            if (bytes != null && bytes.size >= 15 &&
                bytes[0] == HEADER_BYTE_0 &&
                bytes[1] == HEADER_BYTE_1 &&
                bytes[2] == HEADER_BYTE_2
            ) {
                data = bytes
                break
            }
        }

        if (data == null) {
            return BroadcastAction.IGNORED
        }

        return parseData(data, user)
    }

    private fun parseData(data: ByteArray, user: ScaleUser): BroadcastAction {
        // Parse Status
        val status = data[14].toInt() and 0xFF
        val isStable = (status and STATUS_STABLE_MASK) == STATUS_STABLE_MASK

        // Parse Weight (Bytes 10-11 Big Endian)
        val weightRaw = ((data[10].toInt() and 0xFF) shl 8) or (data[11].toInt() and 0xFF)
        val weightKg = weightRaw / 100.0f

        // Parse Impedance (Bytes 12-13 Big Endian)
        val impedanceRaw = ((data[12].toInt() and 0xFF) shl 8) or (data[13].toInt() and 0xFF)

        // 1. Unstable reading
        if (!isStable) {
            firstStableTime = 0
            // Optional: Publish live weight here if you want real-time feedback on UI
            return BroadcastAction.CONSUMED_KEEP_SCANNING
        }

        // 2. Stable reading
        // Check if we have impedance immediately
        if (impedanceRaw > 0) {
            LogManager.i(TAG, "Stable measurement captured with impedance: $impedanceRaw (Raw)")
            publishMeasurement(weightKg, impedanceRaw, user)
            return BroadcastAction.CONSUMED_STOP
        }

        // 3. Stable weight, but waiting for Impedance
        if (firstStableTime == 0L) {
            firstStableTime = System.currentTimeMillis()
            LogManager.d(TAG, "Weight stabilized ($weightKg kg). Waiting for impedance...")
        }

        val elapsed = System.currentTimeMillis() - firstStableTime

        if (elapsed > IMPEDANCE_WAIT_TIMEOUT_MS) {
            LogManager.w(TAG, "Impedance timeout (${elapsed}ms). Publishing weight only.")
            publishMeasurement(weightKg, 0, user)
            return BroadcastAction.CONSUMED_STOP
        }

        // Keep scanning to catch the packet with impedance
        return BroadcastAction.CONSUMED_KEEP_SCANNING
    }

    private fun publishMeasurement(weight: Float, impedanceRaw: Int, user: ScaleUser) {
        val measurement = ScaleMeasurement().apply {
            this.weight = weight
            this.dateTime = Date() // Explicitly set date
            this.userId = user.id  // Bind to current user
            
            if (impedanceRaw > 0) {
                // CORRECTION: Scale appears to send impedance in 0.1 Ohm units or raw ADC.
                // 2352 raw -> 235.2 Ohms. This brings body fat from ~46% down to ~27%.
                val adjustedImpedance = impedanceRaw / 10.0f 
                
                this.impedance = adjustedImpedance.toDouble()
                computeBodyComposition(this, user, adjustedImpedance)
            }
        }
        publish(measurement)
    }

    private fun computeBodyComposition(measurement: ScaleMeasurement, user: ScaleUser, impedance: Float) {
        // Use MiScaleLib as a more generic fallback for Chinese OEM chips
        val sexInt = if (user.gender == GenderType.MALE) 1 else 0
        val lib = MiScaleLib(sexInt, user.age, user.bodyHeight)

        try {
            measurement.fat = lib.getBodyFat(measurement.weight, impedance)
            measurement.water = lib.getWater(measurement.weight, impedance)
            measurement.muscle = lib.getMuscle(measurement.weight, impedance)
            measurement.bone = lib.getBoneMass(measurement.weight, impedance)
            measurement.visceralFat = lib.getVisceralFat(measurement.weight)
            measurement.lbm = lib.getLBM(measurement.weight, impedance)
        } catch (e: Exception) {
            LogManager.e(TAG, "Error calculating body composition", e)
        }
    }
}