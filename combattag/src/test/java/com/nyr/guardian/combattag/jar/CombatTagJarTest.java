package com.nyr.guardian.combattag.jar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import com.nyr.guardian.combattag.CombatTagScenarios;
import java.io.File;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Loads the built NYR-CombatTagPro jar, not the compiled classes, and plays the same scenario through it. */
class CombatTagJarTest {

    private ServerMock server;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    @Test
    void theShippedJarTagsLeavesADummyAndAppliesItsKill() throws Exception {
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
        assertEquals("com.nyr.guardian.combattag.CombatTagPlugin", plugin.getClass().getName());
        assertEquals(jar.toURI().toURL().toString(), plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toString());
        new CombatTagScenarios(server, plugin).all();
    }

    @SuppressWarnings("deprecation")
    private static java.util.List<org.bukkit.permissions.Permission> descriptionPermissions(Plugin plugin) {
        return plugin.getDescription().getPermissions();
    }
}
