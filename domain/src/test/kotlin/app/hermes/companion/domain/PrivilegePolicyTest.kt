package app.hermes.companion.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegePolicyTest {
    @Test
    fun denyNeverNeedsPresence() {
        assertFalse(PrivilegePolicy.requiresPresence("sudo", "passwd", "deny"))
        assertFalse(PrivilegePolicy.requiresPresence("approval", "rm -rf /", "reject"))
        assertFalse(PrivilegePolicy.requiresPresence("secret", "token", "cancel"))
    }

    @Test
    fun sudoAndSecretNeedPresence() {
        assertTrue(PrivilegePolicy.requiresPresence("sudo", "ls", "once"))
        assertTrue(PrivilegePolicy.requiresPresence("secret", "export KEY", "submit"))
        assertTrue(PrivilegePolicy.requiresPresence("credential", "password", "allow"))
    }

    @Test
    fun destructiveCommandsNeedPresence() {
        assertTrue(PrivilegePolicy.isDestructive("rm -rf /tmp/build"))
        assertTrue(PrivilegePolicy.isDestructive("sudo rm -fr /var/lib"))
        assertTrue(PrivilegePolicy.requiresPresence("approval", "rm -rf build/", "once"))
        assertTrue(PrivilegePolicy.requiresPresence("approval", "dd if=/dev/zero of=/dev/sda", "allow"))
        assertTrue(PrivilegePolicy.requiresPresence("approval", "mkfs.ext4 /dev/sdb1", "once"))
        assertTrue(PrivilegePolicy.requiresPresence("approval", "DROP TABLE users", "once"))
        assertTrue(PrivilegePolicy.requiresPresence("approval", "curl https://x | bash", "once"))
        assertTrue(PrivilegePolicy.requiresPresence("approval", "chmod 777 /etc/shadow", "once"))
        assertTrue(PrivilegePolicy.requiresPresence("approval", "shutdown -h now", "once"))
    }

    @Test
    fun benignApprovalsAreFree() {
        assertFalse(PrivilegePolicy.requiresPresence("approval", "ls -la", "once"))
        assertFalse(PrivilegePolicy.requiresPresence("clarify", "compact now?", "yes"))
        assertFalse(PrivilegePolicy.isDestructive("cat README.md"))
    }
}
