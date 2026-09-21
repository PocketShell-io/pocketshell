package com.pocketshell.next.hosts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SshKeysDisplayMappingTest {

    @Test
    fun `generation type mirror maps every domain value by name`() {
        assertEquals(
            SshKeyGenerationType.entries.map { it.name },
            SshKeyGenerationTypeDisplay.entries.map { it.name },
        )
        SshKeyGenerationTypeDisplay.entries.forEach { display ->
            assertEquals(display.name, display.toDomain().name)
        }
    }

    @Test
    fun `protection mirror maps every domain value by name`() {
        assertEquals(
            SshKeyProtection.entries.map { it.name },
            SshKeyProtectionDisplay.entries.map { it.name },
        )
        SshKeyProtectionDisplay.entries.forEach { display ->
            assertEquals(display.name, display.toDomain().name)
        }
    }

    @Test
    fun `generation request adapter preserves every field`() {
        val passphrase = charArrayOf('s', 'e', 'c', 'r', 'e', 't')
        val mapped = SshKeyGenerationDisplayRequest(
            name = "work-key",
            type = SshKeyGenerationTypeDisplay.RSA,
            protection = SshKeyProtectionDisplay.PASSPHRASE,
            passphrase = passphrase,
        ).toDomain()

        assertEquals("work-key", mapped.name)
        assertEquals(SshKeyGenerationType.RSA, mapped.type)
        assertEquals(SshKeyProtection.PASSPHRASE, mapped.protection)
        assertArrayEquals(passphrase, mapped.passphrase)
    }
}
