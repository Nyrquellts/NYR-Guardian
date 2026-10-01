package com.nyr.guardian.smarttick.jar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import com.nyr.guardian.smarttick.SmartTickScenarios;
import java.io.File;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Loads the built NYR-SmartTick jar, not the compiled classes, and plays the same scenarios through it: under a forced high
 * load it sleeps exactly the eligible villagers, and wakes them after.
 */
class SmartTickJarTest {

    private ServerMock server;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    @Test
    void theShippedJarSleepsAndWakesVillagers() throws Exception {
        String path = System.getProperty("nyr.guardian.jar");
        assertNotNull(path, "run through the jarTest task, which names the jar");
        File jar = new File(path);
        Plugin plugin = server.getPluginManager().loadPlugin(jar);
        // A real server registers plugin.yml permissions with their defaults; MockBukkit's loadPlugin(File) does not.
        for (org.bukkit.permissions.Permission permission : descriptionPermissions(plugin)) {
            if (server.getPluginManager().getPermission(permission.getName()) == null) {
                server.getPluginManager().addPermission(permission);
            }
        }
        server.getPluginManager().enablePlugin(plugin);
        assertTrue(plugin.isEnabled(), "the jar enables");
        assertEquals("com.nyr.guardian.smarttick.SmartTickPlugin", plugin.getClass().getName());
        assertEquals(jar.toURI().toURL().toString(), plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toString());
        assertTrue(plugin.getClass().getSuperclass().getName().startsWith("com.nyr.guardian.smarttick.lib.common."));
        new SmartTickScenarios(server, plugin).all();
    }

    @SuppressWarnings("deprecation")
    private static java.util.List<org.bukkit.permissions.Permission> descriptionPermissions(Plugin plugin) {
        return plugin.getDescription().getPermissions();
    }
}
