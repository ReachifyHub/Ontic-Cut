package com.boostlyte.vydra

import com.boostlyte.vydra.utils.UpdateChecker
import com.boostlyte.vydra.utils.UpdateChecker.UpdateInfo
import com.boostlyte.vydra.utils.UpdateChecker.UpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun parseManifest_readsMinimumVersionCodeAndFields() {
        val json = """
            {
              "latestVersionCode": 10,
              "latestVersionName": "v1.2.0",
              "minimumVersionCode": 8,
              "apkUrl": "https://vydra-editor.netlify.app/Vydra-v1.2.0.apk",
              "changelog": ["Faster export", "Bug fixes"]
            }
        """.trimIndent()

        val info = UpdateChecker.parseManifest(json)
        assertNotNull(info)
        assertEquals(10, info?.latestVersionCode)
        assertEquals("v1.2.0", info?.latestVersionName)
        assertEquals(8, info?.minimumVersionCode)
        assertEquals("https://vydra-editor.netlify.app/Vydra-v1.2.0.apk", info?.apkUrl)
        assertEquals(listOf("Faster export", "Bug fixes"), info?.changelog)
    }

    @Test
    fun parseManifest_supportsFallbackMinVersionAliasesAndMissingMin() {
        val minAliasJson = """
            {
              "latestVersionCode": 9,
              "latestVersionName": "v1.1.0",
              "minVersionCode": 9,
              "apkUrl": "https://vydra-editor.netlify.app/app.apk",
              "changelog": []
            }
        """.trimIndent()
        val aliasInfo = UpdateChecker.parseManifest(minAliasJson)
        assertEquals(9, aliasInfo?.minimumVersionCode)

        val snakeCaseJson = """
            {
              "latestVersionCode": 9,
              "minimum_version_code": "7"
            }
        """.trimIndent()
        val snakeInfo = UpdateChecker.parseManifest(snakeCaseJson)
        assertEquals(7, snakeInfo?.minimumVersionCode)

        val noMinJson = """
            {
              "latestVersionCode": 7,
              "latestVersionName": "v1.0.1"
            }
        """.trimIndent()
        val noMinInfo = UpdateChecker.parseManifest(noMinJson)
        assertEquals(0, noMinInfo?.minimumVersionCode)
    }

    @Test
    fun parseManifest_returnsNullForInvalidOrBlankJson() {
        assertNull(UpdateChecker.parseManifest(""))
        assertNull(UpdateChecker.parseManifest("   "))
        assertNull(UpdateChecker.parseManifest("{not valid json"))
    }

    @Test
    fun evaluateUpdate_returnsNoUpdateWhenCurrentMatchesOrExceedsLatest() {
        val infoSame = UpdateInfo(
            latestVersionCode = 6,
            latestVersionName = "v1.0.0",
            changelog = emptyList(),
            apkUrl = "https://example.com/app.apk",
            minimumVersionCode = 6
        )
        assertEquals(UpdateStatus.NO_UPDATE, UpdateChecker.evaluateUpdate(infoSame, currentVersionCode = 6))

        val infoOlderRemote = infoSame.copy(latestVersionCode = 5, minimumVersionCode = 5)
        assertEquals(UpdateStatus.NO_UPDATE, UpdateChecker.evaluateUpdate(infoOlderRemote, currentVersionCode = 6))
    }

    @Test
    fun evaluateUpdate_returnsOptionalWhenMinIsZeroOrAtOrBelowCurrent() {
        val base = UpdateInfo(
            latestVersionCode = 8,
            latestVersionName = "v1.2.0",
            changelog = listOf("Improvements"),
            apkUrl = "https://example.com/app.apk"
        )

        assertEquals(
            UpdateStatus.OPTIONAL_UPDATE,
            UpdateChecker.evaluateUpdate(base.copy(minimumVersionCode = 0), currentVersionCode = 6)
        )
        assertEquals(
            UpdateStatus.OPTIONAL_UPDATE,
            UpdateChecker.evaluateUpdate(base.copy(minimumVersionCode = 5), currentVersionCode = 6)
        )
        assertEquals(
            UpdateStatus.OPTIONAL_UPDATE,
            UpdateChecker.evaluateUpdate(base.copy(minimumVersionCode = 6), currentVersionCode = 6)
        )
    }

    @Test
    fun evaluateUpdate_returnsMandatoryWhenMinExceedsCurrentOrMatchesLatest() {
        val infoMinGreaterThanCurrent = UpdateInfo(
            latestVersionCode = 9,
            latestVersionName = "v1.3.0",
            changelog = listOf("Critical fix"),
            apkUrl = "https://example.com/app.apk",
            minimumVersionCode = 7
        )
        assertEquals(
            UpdateStatus.MANDATORY_UPDATE,
            UpdateChecker.evaluateUpdate(infoMinGreaterThanCurrent, currentVersionCode = 6)
        )

        val infoMinMatchesLatest = UpdateInfo(
            latestVersionCode = 7,
            latestVersionName = "v1.1.0",
            changelog = listOf("Required upgrade"),
            apkUrl = "https://example.com/app.apk",
            minimumVersionCode = 7
        )
        assertEquals(
            UpdateStatus.MANDATORY_UPDATE,
            UpdateChecker.evaluateUpdate(infoMinMatchesLatest, currentVersionCode = 6)
        )

        val infoMinExceedsLatest = infoMinMatchesLatest.copy(minimumVersionCode = 8)
        assertEquals(
            UpdateStatus.MANDATORY_UPDATE,
            UpdateChecker.evaluateUpdate(infoMinExceedsLatest, currentVersionCode = 6)
        )
    }
}
