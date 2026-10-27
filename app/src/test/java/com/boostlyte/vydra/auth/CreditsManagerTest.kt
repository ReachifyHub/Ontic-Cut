package com.boostlyte.vydra.auth

import com.boostlyte.vydra.utils.AppUrls
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CreditsManagerTest {

    private lateinit var testContext: TestContext

    @Before
    fun setUp() {
        testContext = TestContext()
        CreditsManager.invalidateCache(testContext)
    }

    @Test
    fun testExtractRemainingCredits_exactBackendSchema() {
        // Live backend uses credits_granted and credits_used columns
        val row = JsonObject().apply {
            addProperty("user_id", "09930fef-2df4-4a25-9d52-520f925b4fa6")
            addProperty("credits_granted", 60)
            addProperty("credits_used", 15)
            addProperty("status", "active")
        }

        val remaining = CreditsManager.extractRemainingCredits(row)
        assertEquals(45, remaining)
    }

    @Test
    fun testExtractRemainingCredits_fullyConsumed() {
        val row = JsonObject().apply {
            addProperty("credits_granted", 30)
            addProperty("credits_used", 30)
        }

        val remaining = CreditsManager.extractRemainingCredits(row)
        assertEquals(0, remaining)
    }

    @Test
    fun testExtractRemainingCredits_overConsumedCoercesToZero() {
        val row = JsonObject().apply {
            addProperty("credits_granted", 10)
            addProperty("credits_used", 25)
        }

        val remaining = CreditsManager.extractRemainingCredits(row)
        assertEquals(0, remaining)
    }

    @Test
    fun testExtractRemainingCredits_fallbackColumns() {
        val row1 = JsonObject().apply { addProperty("credits_remaining", 25) }
        assertEquals(25, CreditsManager.extractRemainingCredits(row1))

        val row2 = JsonObject().apply { addProperty("remaining_minutes", 12) }
        assertEquals(12, CreditsManager.extractRemainingCredits(row2))

        val row3 = JsonObject().apply { addProperty("credits", 8) }
        assertEquals(8, CreditsManager.extractRemainingCredits(row3))

        val row4 = JsonObject().apply { addProperty("minutes", 40) }
        assertEquals(40, CreditsManager.extractRemainingCredits(row4))

        val rowEmpty = JsonObject()
        assertEquals(0, CreditsManager.extractRemainingCredits(rowEmpty))
    }

    @Test
    fun testCachedCreditsStorageAndInvalidation() {
        assertEquals(0, CreditsManager.getCachedCredits(testContext))

        CreditsManager.setCachedCredits(testContext, 55)
        assertEquals(55, CreditsManager.getCachedCredits(testContext))

        CreditsManager.invalidateCache(testContext)
        assertEquals(0, CreditsManager.getCachedCredits(testContext))
    }

    @Test
    fun testWhatsAppUrlsAndPurchaseUriFormatting() {
        assertEquals("https://wa.me/2348157683854", AppUrls.WHATSAPP_PURCHASE_URL)
        assertEquals("https://chat.whatsapp.com/L5LJK0HMcbLI9zS3CX2S38", AppUrls.WHATSAPP_COMMUNITY_URL)

        val urlWithEmail = AppUrls.buildWhatsAppPurchaseUrl("editor@example.com")
        assertTrue(urlWithEmail.startsWith("https://wa.me/2348157683854?text="))
        assertTrue(urlWithEmail.contains("editor") && urlWithEmail.contains("example.com"))

        val urlWithoutEmail = AppUrls.buildWhatsAppPurchaseUrl(null)
        assertTrue(urlWithoutEmail.startsWith("https://wa.me/2348157683854?text="))
        assertTrue(urlWithoutEmail.contains("Vydra"))
    }
}
