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
import com.health.openscale.core.bluetooth.libs.TrisaBodyAnalyzeLib
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager

/**
 * Handler for Senssun IF_B7 scales (Broadcast/Advertising).
 * Rebranded as Moving Life BS 161, etc.
 *
 * Protocol:
 * Manufacturer ID: 0x8500 (Often parsed as 0x0085 by Android due to Endianness)
 * Byte Layout:
 * [0..2]  Header (02 03 11)
 * [3..8]  MAC Address
 * [9]     Separator (0x01)
 * [10-11] Weight (Big Endian, /100)
 * [12-13] Impedance (Big Endian)
 * [14]    Status (0xA1 = Stable)
 */

class SenssunIFB7Handler : ScaleDeviceHandler() {

    private val TAG = "SenssunIFB7Handler"
    
    private val IMPEDANCE_WAIT_TIMEOUT_MS = 3000L
    private var firstStableTimestamp: Long = 0L

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name?.uppercase() ?: ""
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

        // Header: 02 03 11
        for (i in 0 until msd.size()) {
            val bytes = msd.valueAt(i)
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

        // Byte 14: Status (0xA1 = Stable)
        val status = manufacturerData[14].toInt() and 0xFF
        val isStable = (status and 0xA0) == 0xA0

        // Bytes 10-11: Weight
        val weightRaw = ((manufacturerData[10].toInt() and 0xFF) shl 8) or (manufacturerData[11].toInt() and 0xFF)
        val weightKg = weightRaw / 100.0f

        // Bytes 12-13: Impedance
        val impedanceRaw = ((manufacturerData[12].toInt() and 0xFF) shl 8) or (manufacturerData[13].toInt() and 0xFF)

        // 1. Unstable: Reset timer, update live UI (optional), keep scanning
        if (!isStable) {
            firstStableTimestamp = 0L
            if (weightKg > 0) {
                 // Uncomment to see live numbers updating, but do NOT call publish() here
                 // to avoid saving unstable values to DB.
                 // LogManager.d(TAG, "Live: $weightKg kg")
            }
            return BroadcastAction.CONSUMED_KEEP_SCANNING
        }

        // 2. Stable + Valid Impedance: Success immediately
        if (impedanceRaw > 0) {
            publishFinalMeasurement(weightKg, impedanceRaw, user)
            LogManager.i(TAG, "Success: Weight + Impedance found.")
            return BroadcastAction.CONSUMED_STOP
        }

        // 3. Stable but No Impedance (0): Wait for it...
        if (firstStableTimestamp == 0L) {
            firstStableTimestamp = System.currentTimeMillis()
        }

        val elapsed = System.currentTimeMillis() - firstStableTimestamp

        if (elapsed > IMPEDANCE_WAIT_TIMEOUT_MS) {
            // Timed out waiting for impedance (User wearing socks?) -> Save Weight Only
            publishFinalMeasurement(weightKg, 0, user)
            LogManager.w(TAG, "Timeout waiting for impedance. Saving Weight only.")
            return BroadcastAction.CONSUMED_STOP
        }

        LogManager.d(TAG, "Stable weight ($weightKg) waiting for impedance... (${elapsed}ms)")
        return BroadcastAction.CONSUMED_KEEP_SCANNING
    }

    private fun publishFinalMeasurement(weight: Float, impedance: Int, user: ScaleUser) {
        val m = ScaleMeasurement().apply {
            this.weight = weight
            
            if (impedance > 0) {
                this.impedance = impedance.toDouble()
                
                // Calculate Body Composition using Trisa lib
                val sexInt = if (user.gender.isMale()) 1 else 0
                val lib = TrisaBodyAnalyzeLib(sexInt, user.age, user.bodyHeight)
                val impFloat = impedance.toFloat()

                fat = lib.getFat(weight, impFloat)
                water = lib.getWater(weight, impFloat)
                muscle = lib.getMuscle(weight, impFloat)
                bone = lib.getBone(weight, impFloat)
                
                // Simple LBM calculation
                val fatMass = weight * (fat / 100.0f)
                lbm = weight - fatMass
            }
        }
        publish(m)
    }
}
