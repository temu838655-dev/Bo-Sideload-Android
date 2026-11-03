package com.bosideload.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class ZsignEngineCommandTest {
    @Test
    fun addsOneMinusMArgumentForEachProvisioningProfileInOrder() {
        val binary = File("/app/zsign")
        val input = File("/files/input.ipa")
        val p12 = File("/files/signing.p12")
        val mainProfile = File("/files/main.mobileprovision")
        val extensionProfile = File("/files/share.mobileprovision")
        val output = File("/files/output.ipa")

        val command = ZsignEngine.buildSigningCommand(
            binary,
            input,
            p12,
            listOf(mainProfile, extensionProfile),
            "temporary-password",
            output
        )

        assertEquals(
            listOf(
                "/app/zsign", "-f",
                "-k", "/files/signing.p12",
                "-p", "temporary-password",
                "-m", "/files/main.mobileprovision",
                "-m", "/files/share.mobileprovision",
                "-o", "/files/output.ipa",
                "/files/input.ipa"
            ),
            command
        )
    }

    @Test
    fun rejectsEmptyProvisioningProfileList() {
        assertThrows(IllegalArgumentException::class.java) {
            ZsignEngine.buildSigningCommand(
                File("/app/zsign"),
                File("/files/input.ipa"),
                File("/files/signing.p12"),
                emptyList(),
                "temporary-password",
                File("/files/output.ipa")
            )
        }
    }
}
