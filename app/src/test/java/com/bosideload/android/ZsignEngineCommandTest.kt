package com.bosideload.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class ZsignEngineCommandTest {
    @Test
    fun addsOneMinusMArgumentForEachProvisioningProfileInOrder() {
        val binary = File("/app/zsign")
        val input = File("/files/input.ipa")
        val privateKey = File("/files/identity-key.pem")
        val certificate = File("/files/identity-cert.pem")
        val mainProfile = File("/files/main.mobileprovision")
        val extensionProfile = File("/files/share.mobileprovision")
        val output = File("/files/output.ipa")

        val command = ZsignEngine.buildSigningCommand(
            binary,
            input,
            privateKey,
            certificate,
            listOf(mainProfile, extensionProfile),
            output
        )

        assertFalse("P12 password must never appear in process arguments", command.contains("-p"))
        assertFalse("P12 password must never appear in process arguments", command.contains("temporary-password"))

        assertEquals(
            listOf(
                "/app/zsign", "-f",
                "-k", "/files/identity-key.pem",
                "-c", "/files/identity-cert.pem",
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
                File("/files/identity-key.pem"),
                File("/files/identity-cert.pem"),
                emptyList(),
                File("/files/output.ipa")
            )
        }
    }
}
