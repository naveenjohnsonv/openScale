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
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import java.util.Date

/**
 * Handler for HealthSense BS161 (Advertised as "IF_B7").
 *
 * Protocol:
 * Manufacturer Specific Data containing Header: 02 03 11
 *
 * Data Layout (Manufacturer Data):
 * [0..2]   Header (02 03 11)
 * [3..8]   MAC Address
 * [9]      Type (01 = Weight Data)
 * [10-11]  Weight (Big Endian, /100 for kg)
 * [12-13]  Impedance (Big Endian, Ohms)
 * [14]     Status/Seq
 *
 * Status Byte (14):
 * - 0x01: Measuring/Unstable
 * - 0xA1: Stable/Locked
 *
 * Behavior:
 * The scale sends 0xA1 (Stable) with Impedance=0 immediately upon weight lock.
 * It sends 0xA1 with Impedance=Value approx 1.5 - 3 seconds later.
 */
class HealthSenseBS161Handler : ScaleDeviceHandler() {

    companion object {
        private const val TAG = "HealthSenseBS161Handler"
        private const val ADVERTISED_NAME_PREFIX = "IF_B7"

        // The fixed header bytes in the Manufacturer Specific Data
        private const val HEADER_BYTE_0 = 0x02.toByte()
        private const val HEADER_BYTE_1 = 0x03.toByte()
        private const val HEADER_BYTE_2 = 0x11.toByte()

        // Status 0xA1 indicates stable. We mask to check the high nibble (0xA0)
        private const val STATUS_STABLE_MASK = 0xA0

        // Wait up to 4 seconds for impedance after weight stabilizes
        private const val IMPEDANCE_WAIT_TIMEOUT_MS = 4000L
    }

    private var firstStableTime: Long = 0

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // Safe null check on device name
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

        // Iterate through all manufacturer data to find the one matching our protocol header.
        // The ID is often dynamic or not standard, so we look for the payload signature.
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
        // 1. Parse Values
        // Weight: Bytes 10-11 Big Endian
        val weightRaw = ((data[10].toInt() and 0xFF) shl 8) or (data[11].toInt() and 0xFF)
        val weightKg = weightRaw / 100.0f

        // Impedance: Bytes 12-13 Big Endian
        val impedanceRaw = ((data[12].toInt() and 0xFF) shl 8) or (data[13].toInt() and 0xFF)

        // Status: Byte 14
        val status = data[14].toInt() and 0xFF
        val isStable = (status and STATUS_STABLE_MASK) == STATUS_STABLE_MASK

        // 2. Logic Flow
        
        // Case A: Scale is measuring (Unstable)
        if (!isStable) {
            firstStableTime = 0
            // We could publish live updates here if desired, but usually we wait for stable
            return BroadcastAction.CONSUMED_KEEP_SCANNING
        }

        // Case B: Scale is Stable AND has Impedance
        if (impedanceRaw > 0) {
            LogManager.i(TAG, "Stable measurement with Impedance: $impedanceRaw Ohm")
            publishMeasurement(weightKg, impedanceRaw, user)
            return BroadcastAction.CONSUMED_STOP
        }

        // Case C: Scale is Stable but Impedance is 0 (Calculating...)
        if (firstStableTime == 0L) {
            firstStableTime = System.currentTimeMillis()
            LogManager.d(TAG, "Weight stabilized ($weightKg kg). Waiting for impedance...")
        }

        val elapsed = System.currentTimeMillis() - firstStableTime

        // Case D: Timeout waiting for impedance
        if (elapsed > IMPEDANCE_WAIT_TIMEOUT_MS) {
            LogManager.w(TAG, "Impedance timeout (${elapsed}ms). Publishing weight only.")
            publishMeasurement(weightKg, 0, user)
            firstStableTime = 0 // Reset to prevent rapid re-publishing
            return BroadcastAction.CONSUMED_STOP
        }

        // Still waiting for impedance packet...
        return BroadcastAction.CONSUMED_KEEP_SCANNING
    }

    private fun publishMeasurement(weight: Float, impedanceRaw: Int, user: ScaleUser) {
        val measurement = ScaleMeasurement().apply {
            this.weight = weight
            this.userId = user.id
            this.dateTime = Date() // Record the time of processing
            
            if (impedanceRaw > 0) {
                this.impedance = impedanceRaw.toDouble()
                computeBodyComposition(this, user, impedanceRaw.toFloat())
            }
        }
        publish(measurement)
    }

    private fun computeBodyComposition(measurement: ScaleMeasurement, user: ScaleUser, impedance: Float) {
        // MiScaleLib (Standard BIA) is used here. 
        // It effectively uses the H^2/R model which aligns with the raw data provided.
        // Gender: 1 = Male, 0 = Female
        val sexInt = if (user.gender.isMale()) 1 else 0
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
