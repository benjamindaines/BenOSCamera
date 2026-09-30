package com.light.lightcamera

import android.content.Context
import android.os.Build
import android.util.Log

object BenOsUtils {
    private const val TAG = "BenOsUtils"

    /**
     * Checks whether the current operating system is strictly BenOS.
     * Explicitly blocks execution on LineageOS, standard Android / AOSP, and other custom ROMs.
     */
    fun isBenOs(context: Context): Boolean {
        // Step 1: Explicitly check and block LineageOS & CyanogenMod
        if (isLineageOs()) {
            Log.d(TAG, "Device rejected: LineageOS detected.")
            return false
        }

        // Step 2: Positive validation for BenOS
        // Check 2a: Standard Build Identifiers containing "BenOS"
        val display = Build.DISPLAY ?: ""
        val brand = Build.BRAND ?: ""
        val product = Build.PRODUCT ?: ""
        val fingerprint = Build.FINGERPRINT ?: ""

        if (display.contains("BenOS", ignoreCase = true) ||
            brand.contains("BenOS", ignoreCase = true) ||
            product.contains("BenOS", ignoreCase = true) ||
            fingerprint.contains("BenOS", ignoreCase = true)
        ) {
            return true
        }

        // Check 2b: System Properties via Reflection
        val buildDisplayProp = getSystemProperty("ro.build.display.id")
        if (buildDisplayProp.contains("BenOS", ignoreCase = true)) {
            return true
        }

        val benOsVersionProp = getSystemProperty("ro.benos.version")
        if (benOsVersionProp.isNotBlank() && benOsVersionProp != "unknown") {
            return true
        }

        val fotaVersionProp = getSystemProperty("ro.fota.version")
        if (fotaVersionProp.contains("BenOS", ignoreCase = true)) {
            return true
        }

        val fotaDeviceProp = getSystemProperty("ro.fota.device")
        if (fotaDeviceProp.contains("BenOS", ignoreCase = true)) {
            return true
        }

        // Check 2c: System Features declared by BenOS
        try {
            val pm = context.packageManager
            if (pm.hasSystemFeature("org.benos.feature") ||
                pm.hasSystemFeature("benos.hardware.camera") ||
                pm.hasSystemFeature("org.benos.hardware")
            ) {
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking system features", e)
        }

        // Default to false for standard Android / AOSP / other ROMs
        return false
    }

    /**
     * Helper to detect if the device is running LineageOS or CyanogenMod.
     */
    private fun isLineageOs(): Boolean {
        val display = Build.DISPLAY ?: ""
        val fingerprint = Build.FINGERPRINT ?: ""
        val host = Build.HOST ?: ""

        if (display.contains("lineage", ignoreCase = true) ||
            display.contains("cyanogenmod", ignoreCase = true) ||
            fingerprint.contains("lineage", ignoreCase = true) ||
            host.contains("lineage", ignoreCase = true)
        ) {
            return true
        }

        val lineageVersion = getSystemProperty("ro.lineage.version")
        if (lineageVersion.isNotBlank()) return true

        val lineageBuildVersion = getSystemProperty("ro.lineage.build.version")
        if (lineageBuildVersion.isNotBlank()) return true

        val lineageDevice = getSystemProperty("ro.lineage.device")
        if (lineageDevice.isNotBlank()) return true

        val cmVersion = getSystemProperty("ro.cm.version")
        if (cmVersion.isNotBlank()) return true

        return false
    }

    private fun getSystemProperty(key: String): String {
        return try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val getMethod = systemPropertiesClass.getMethod("get", String::class.java)
            (getMethod.invoke(null, key) as? String) ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}
