package com.encryptedenergy.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EEConfigTest {

    @Test
    fun gatewayTokenDefaultsToProductionBaseUrl() {
        val config = EEConfig.GatewayToken(token = "ee_live_abc")
        assertEquals("ee_live_abc", config.token)
        assertEquals(EEConfig.DEFAULT_BASE_URL, config.baseUrl)
    }

    @Test
    fun selfMintDefaultsDeviceNameToNull() {
        // null means "read Build.MODEL at mint time", which keeps this config
        // type android-free (plan §3).
        val config = EEConfig.SelfMint()
        assertNull(config.deviceName)
        assertEquals(EEConfig.DEFAULT_BASE_URL, config.baseUrl)
    }

    @Test
    fun baseUrlIsOverridable() {
        val staging = "https://staging.encryptedenergy.com"
        assertEquals(staging, EEConfig.GatewayToken(token = "t", baseUrl = staging).baseUrl)
        assertEquals(staging, EEConfig.SelfMint(baseUrl = staging).baseUrl)
    }

    @Test
    fun defaultBaseUrlIsProduction() {
        assertEquals("https://encryptedenergy.com", EEConfig.DEFAULT_BASE_URL)
    }

    @Test
    fun bothVariantsAreTheSealedConfigType() {
        // The facade accepts either arm through the one sealed interface.
        val configs: List<EEConfig> = listOf(
            EEConfig.GatewayToken(token = "t"),
            EEConfig.SelfMint(deviceName = "Pixel 9"),
        )
        assertTrue(configs.all { it.baseUrl == EEConfig.DEFAULT_BASE_URL })
    }
}
