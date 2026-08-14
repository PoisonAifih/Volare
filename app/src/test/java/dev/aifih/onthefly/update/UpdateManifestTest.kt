package dev.aifih.onthefly.update

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateManifestTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `parses latest json published by CI`() {
        val payload = """
            {
              "versionCode": 42,
              "versionName": "0.1.42",
              "assetId": 987654321,
              "apkUrl": "https://github.com/o/r/releases/download/v0.1.42/onthefly-0.1.42.apk",
              "publishedAt": "2026-08-14T08:00:00Z"
            }
        """.trimIndent()

        val manifest = json.decodeFromString(UpdateManifest.serializer(), payload)

        assertEquals(42L, manifest.versionCode)
        assertEquals("0.1.42", manifest.versionName)
        assertEquals(987654321L, manifest.assetId)
        assertNull(manifest.notes)
    }

    @Test
    fun `manifest from before the private switch has no asset id`() {
        val payload = """
            {
              "versionCode": 41,
              "versionName": "0.1.41",
              "apkUrl": "https://example.com/a.apk"
            }
        """.trimIndent()

        val manifest = json.decodeFromString(UpdateManifest.serializer(), payload)

        assertNull(manifest.assetId)
    }

    @Test
    fun `extra fields added later do not break older clients`() {
        val payload = """
            {
              "versionCode": 43,
              "versionName": "0.1.43",
              "apkUrl": "https://example.com/a.apk",
              "minAndroidSdk": 26
            }
        """.trimIndent()

        val manifest = json.decodeFromString(UpdateManifest.serializer(), payload)

        assertEquals(43L, manifest.versionCode)
    }
}
