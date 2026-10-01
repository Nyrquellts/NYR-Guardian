package com.nyr.guardian.linkage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The linkage check proved by mutation: a jar planted with calls that link on one server version and not another must be
 * reported, and a jar of calls that exist everywhere must pass. Runs against the real API jars the testbed unpacked.
 */
class ApiLinkageTest {

    @TempDir
    Path dir;

    private static File api(String property) {
        String path = System.getProperty(property);
        File file = path == null ? null : new File(path);
        assertTrue(file != null && file.isFile(), property + " must name a server API jar (got " + path + ")");
        return file;
    }

    /** One class whose single method makes the given calls: {opcode, owner, name, descriptor, isInterface}. */
    private File jarCalling(List<Object[]> calls) throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "planted/Caller", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(Ljava/lang/Object;)V", null, null);
        method.visitCode();
        for (Object[] call : calls) {
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitTypeInsn(Opcodes.CHECKCAST, (String) call[1]);
            method.visitMethodInsn((int) call[0], (String) call[1], (String) call[2], (String) call[3], (boolean) call[4]);
            method.visitInsn(Opcodes.POP);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        File jar = dir.resolve("planted-" + calls.size() + ".jar").toFile();
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
            out.putNextEntry(new JarEntry("planted/Caller.class"));
            out.write(writer.toByteArray());
            out.closeEntry();
        }
        return jar;
    }

    @Test
    void plantedLinkageBreaksAreReported() throws Exception {
        File paper1206 = api("nyr.linkage.paper1206");
        File paper2612 = api("nyr.linkage.paper2612");

        // InventoryView is an interface from 1.21 on: a call compiled that way fails on 1.20.6, where it is a class.
        Object[] viewAsInterface = {Opcodes.INVOKEINTERFACE, "org/bukkit/inventory/InventoryView", "getTopInventory", "()Lorg/bukkit/inventory/Inventory;", true};
        // A method no server has.
        Object[] missing = {Opcodes.INVOKEINTERFACE, "org/bukkit/entity/Player", "nyrPlantedMissingMethod", "()Z", true};
        // Attribute is an enum class on 1.20.6 and an interface on 26.x.
        Object[] attributeAsClass = {Opcodes.INVOKEVIRTUAL, "org/bukkit/attribute/Attribute", "name", "()Ljava/lang/String;", false};
        File planted = jarCalling(List.of(viewAsInterface, missing, attributeAsClass));

        Set<String> on1206 = ApiLinkage.problems(planted, "none/", paper1206);
        assertTrue(on1206.stream().anyMatch(p -> p.contains("InventoryView is a class here")), on1206.toString());
        assertTrue(on1206.stream().anyMatch(p -> p.contains("missing method org.bukkit.entity.Player.nyrPlantedMissingMethod")), on1206.toString());
        assertEquals(2, on1206.size(), on1206.toString());

        Set<String> on2612 = ApiLinkage.problems(planted, "none/", paper2612);
        assertTrue(on2612.stream().anyMatch(p -> p.contains("Attribute is an interface here")), on2612.toString());
        assertTrue(on2612.stream().anyMatch(p -> p.contains("nyrPlantedMissingMethod")), on2612.toString());
        assertEquals(2, on2612.size(), on2612.toString());

        // Calls that exist the same way on both link cleanly.
        Object[] name = {Opcodes.INVOKEINTERFACE, "org/bukkit/command/CommandSender", "getName", "()Ljava/lang/String;", true};
        Object[] isAir = {Opcodes.INVOKEVIRTUAL, "org/bukkit/Material", "isAir", "()Z", false};
        File clean = jarCalling(List.of(name, isAir));
        assertEquals(Set.of(), ApiLinkage.problems(clean, "none/", paper1206));
        assertEquals(Set.of(), ApiLinkage.problems(clean, "none/", paper2612));
    }
}
