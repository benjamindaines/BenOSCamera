package com.light.lightcamera

import android.content.Context
import android.os.Build
import android.util.Log

object BenOsUtils {
    private const val TAG = "BenOsUtils"

    /**
     * Checks whether the current operating system is BenOS.
     * Evaluates Build properties, system properties via reflection, and system features.
     */
    fun isBenOs(context: Context): Boolean {
        // 1. Check Standard Build Identifiers
        val display = Build.DISPLAY ?: ""
        val brand = Build.BRAND ?: ""
        val product = Build.PRODUCT ?: ""
        val device = Build.DEVICE ?: ""
        val fingerprint = Build.FINGERPRINT ?: ""

        if (display.contains("BenOS", ignoreCase = true) ||
            brand.contains("BenOS", ignoreCase = true) ||
            product.contains("BenOS", ignoreCase = true) ||
            device.contains("zinwa_q25", ignoreCase = true) ||
            fingerprint.contains("BenOS", ignoreCase = true)
        ) {
            return true
        }

        // 2. Check System Properties via Reflection
        val benOsVersionProp = getSystemProperty("ro.benos.version")
        if (benOsVersionProp.isNotBlank() && (benOsVersionProp != "unknown")) {
            return true
        }

        val buildDisplayProp = getSystemProperty("ro.build.display.id")
        if (buildDisplayProp.contains("BenOS", ignoreCase = true)) {
            return true
        }

        val productBrandProp = getSystemProperty("ro.product.brand")
        if (productBrandProp.contains("BenOS", ignoreCase = true)) {
            return true
        }

        // 3. Check System Features
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
