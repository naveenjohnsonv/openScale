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
import com.health.openscale.core.bluetooth.libs.TrisaBodyAnalyzeLib
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager

/**
 * Handler for HealthSense BS161 (advertised internally as "IF_B7").
 *
 * Protocol (Broadcast/Advertising Manufacturer Data):
 * Manufacturer ID: 0x0085 (Senssun)
 *
 * Byte Layout:
 * [0..2]   Header (02 03 11)
 * [3..8]   MAC Address
 * [9]      Separator (01)
 * [10-11]  Weight (Big Endian, scaled by 100)
 * [12-13]  Impedance (Big Endian, Ohms)
 * [14]     Status Flags
 *
 * Status Logic:
 * - 0x01: Stabilizing (Measuring)
 * - 0xA1: Stable (Locked)
 *
 * Timing Note:
 * The scale broadcasts the "Stable" flag (0xA1) immediately when weight locks,
 * but the Impedance value remains 0 for approximately 2.7s - 3.2s while it calculates.
 * We must keep scanning after the stable weight is received to capture the body composition data.
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
        // We mask with 0xA0 to detect the "final/stable" state regardless of other bit flags
        private const val STATUS_STABLE_MASK = 0xA0 

        // Time to wait for impedance after weight stabilizes (Logs show ~3.2s max)
        private const val IMPEDANCE_WAIT_TIMEOUT_MS = 4000L
    }

    private var firstStableTime: Long = 0

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.uppercase()
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
        // We scan all entries because the Manufacturer ID (0x0085) might vary or be parsed as the key
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
            // Logic to publish live updates could go here if needed
            return BroadcastAction.CONSUMED_KEEP_SCANNING
        }

        // 2. Stable reading
        // Check if we have impedance immediately (unlikely on this model, but possible)
        if (impedanceRaw > 0) {
            LogManager.i(TAG, "Stable measurement captured with impedance: $impedanceRaw Ohm")
            publishMeasurement(weightKg, impedanceRaw, user)
            return BroadcastAction.CONSUMED_STOP
        }

        // 3. Stable weight, but waiting for Impedance (Status A1, Imp 0)
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

    private fun publishMeasurement(weight: Float, impedance: Int, user: ScaleUser) {
        val measurement = ScaleMeasurement().apply {
            this.weight = weight
            
            if (impedance > 0) {
                this.impedance = impedance.toDouble()
                computeBodyComposition(this, user, impedance)
            }
        }
        publish(measurement)
    }

    private fun computeBodyComposition(measurement: ScaleMeasurement, user: ScaleUser, impedance: Int) {
        // TrisaBodyAnalyzeLib requires: sex (1=male, 0=female), age, height(cm)
        val sexInt = if (user.gender.isMale()) 1 else 0
        val lib = TrisaBodyAnalyzeLib(sexInt, user.age, user.bodyHeight)
        val impFloat = impedance.toFloat()

        measurement.fat = lib.getFat(measurement.weight, impFloat)
        measurement.water = lib.getWater(measurement.weight, impFloat)
        measurement.muscle = lib.getMuscle(measurement.weight, impFloat)
        measurement.bone = lib.getBone(measurement.weight, impFloat)
        
        // Derive LBM (Lean Body Mass) from Fat%
        if (measurement.fat > 0) {
            val fatMass = measurement.weight * (measurement.fat / 100.0f)
            measurement.lbm = measurement.weight - fatMass
        }
    }
}