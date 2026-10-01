package com.nyr.guardian.chunkhopper.jar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import com.nyr.guardian.chunkhopper.ChunkHopperScenarios;
import java.io.File;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Loads the built NYR-ChunkHopper jar, not the compiled classes, and plays every scenario through it: collecting drops as
 * they spawn, the filter menu, selling through a Vault economy, breaking, reloading.
 */
class ChunkHopperJarTest {

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
    void theShippedJarCollectsFiltersAndSells() throws Exception {
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
        assertEquals("com.nyr.guardian.chunkhopper.ChunkHopperPlugin", plugin.getClass().getName());
        assertEquals(jar.toURI().toURL().toString(), plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toString(),
            "the plugin class comes from the jar");
        new ChunkHopperScenarios(server, plugin).all();
    }

    @SuppressWarnings("deprecation")
    private static java.util.List<org.bukkit.permissions.Permission> descriptionPermissions(Plugin plugin) {
        return plugin.getDescription().getPermissions();
    }
}
