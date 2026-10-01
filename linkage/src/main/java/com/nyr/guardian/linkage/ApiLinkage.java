package com.nyr.guardian.linkage;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Checks that every server API member a built plugin jar calls exists, with the same kind of owner, in real server API jars.
 *
 * <p>A plugin compiles against one API (Paper 1.21.1) and runs on 1.20.6 through 26.x. A method the API gained later, or a
 * class that became an interface (InventoryView did in 1.21), links on one version and throws NoSuchMethodError or
 * IncompatibleClassChangeError on another, but only when that code runs. This reads the jar's bytecode and resolves each
 * call, field and type it names in each API jar, the way the JVM would link it.
 *
 * <pre>java ApiLinkage plugin.jar skipPrefix api-1.jar [api-2.jar ...]</pre>
 *
 * Classes whose path starts with skipPrefix (the relocated FoliaLib, which picks its classes by platform) are not read.
 * Exit 0 when everything links everywhere, 1 with the list of problems otherwise.
 */
public final class ApiLinkage {

    /** Packages the API jars define; references elsewhere (JDK, Guava, Adventure) are not this check's business. */
    private static final List<String> API_PACKAGES = List.of("org/bukkit/", "io/papermc/paper/", "com/destroystokyo/paper/", "org/spigotmc/");

    private record Ref(String kind, String owner, String name, String desc, String from) {
    }

    private record Info(boolean isInterface, String superName, List<String> interfaces, Set<String> methods, Set<String> fields) {
    }

    private final Map<String, Info> cache = new HashMap<>();
    private final JarFile api;

    private ApiLinkage(JarFile api) {
        this.api = api;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("usage: ApiLinkage plugin.jar skipPrefix api.jar [api.jar ...]");
            System.exit(2);
        }
        List<Ref> refs = references(new File(args[0]), args[1]);
        boolean failed = false;
        for (int i = 2; i < args.length; i++) {
            File apiFile = new File(args[i]);
            Set<String> problems = problems(refs, apiFile);
            if (problems.isEmpty()) {
                System.out.println("links against " + apiFile.getName() + ": " + refs.size() + " API references");
            } else {
                failed = true;
                System.out.println("does NOT link against " + apiFile.getName() + ":");
                problems.forEach(problem -> System.out.println("  " + problem));
            }
        }
        System.exit(failed ? 1 : 0);
    }

    /** Every way the jar's API references fail to link against one API jar; empty when they all link. */
    public static Set<String> problems(File jar, String skipPrefix, File apiJar) throws IOException {
        return problems(references(jar, skipPrefix), apiJar);
    }

    private static Set<String> problems(List<Ref> refs, File apiFile) throws IOException {
        try (JarFile apiJar = new JarFile(apiFile)) {
            return new TreeSet<>(new ApiLinkage(apiJar).check(refs));
        }
    }

    private static boolean api(String internalName) {
        for (String prefix : API_PACKAGES) {
            if (internalName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static List<Ref> references(File jar, String skipPrefix) throws IOException {
        Set<Ref> refs = new LinkedHashSet<>();
        try (JarFile file = new JarFile(jar)) {
            for (JarEntry entry : java.util.Collections.list(file.entries())) {
                if (!entry.getName().endsWith(".class") || entry.getName().startsWith(skipPrefix)) {
                    continue;
                }
                ClassNode node = new ClassNode();
                try (InputStream in = file.getInputStream(entry)) {
                    new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
                }
                String from = node.name.replace('/', '.');
                if (node.superName != null && api(node.superName)) {
                    refs.add(new Ref("extends", node.superName, "", "", from));
                }
                for (String iface : node.interfaces) {
                    if (api(iface)) {
                        refs.add(new Ref("implements", iface, "", "", from));
                    }
                }
                for (MethodNode method : node.methods) {
                    String where = from + "." + method.name;
                    for (AbstractInsnNode insn : method.instructions) {
                        collect(insn, where, refs);
                    }
                }
                for (FieldNode field : node.fields) {
                    addType(Type.getType(field.desc), from + "." + field.name, refs);
                }
            }
        }
        return new ArrayList<>(refs);
    }

    private static void collect(AbstractInsnNode insn, String where, Set<Ref> refs) {
        if (insn instanceof MethodInsnNode call && api(call.owner)) {
            String kind = switch (call.getOpcode()) {
                case Opcodes.INVOKEINTERFACE -> "invokeinterface";
                case Opcodes.INVOKESTATIC -> call.itf ? "invokestatic-interface" : "invokestatic";
                case Opcodes.INVOKESPECIAL -> "invokespecial";
                default -> "invokevirtual";
            };
            refs.add(new Ref(kind, call.owner, call.name, call.desc, where));
        } else if (insn instanceof FieldInsnNode field && api(field.owner)) {
            refs.add(new Ref("field", field.owner, field.name, field.desc, where));
        } else if (insn instanceof TypeInsnNode type) {
            addType(Type.getObjectType(type.desc), where, refs);
        } else if (insn instanceof MultiANewArrayInsnNode array) {
            addType(Type.getType(array.desc), where, refs);
        } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type) {
            addType(type, where, refs);
        } else if (insn instanceof InvokeDynamicInsnNode indy) {
            for (Object arg : indy.bsmArgs) {
                if (arg instanceof Handle handle && api(handle.getOwner())) {
                    String kind = handle.isInterface() && handle.getTag() != Opcodes.H_INVOKESTATIC ? "invokeinterface" : "invokevirtual";
                    if (handle.getTag() == Opcodes.H_INVOKESTATIC) {
                        kind = "invokestatic";
                    }
                    refs.add(new Ref(kind, handle.getOwner(), handle.getName(), handle.getDesc(), where));
                }
            }
        }
    }

    private static void addType(Type type, String where, Set<Ref> refs) {
        Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
        if (element.getSort() == Type.OBJECT && api(element.getInternalName())) {
            refs.add(new Ref("type", element.getInternalName(), "", "", where));
        }
    }

    private List<String> check(List<Ref> refs) throws IOException {
        List<String> problems = new ArrayList<>();
        for (Ref ref : refs) {
            Info owner = info(ref.owner());
            String at = " (in " + ref.from() + ")";
            if (owner == null) {
                problems.add("missing class " + ref.owner().replace('/', '.') + at);
                continue;
            }
            switch (ref.kind()) {
                case "invokeinterface", "invokestatic-interface" -> {
                    if (!owner.isInterface()) {
                        problems.add(ref.owner().replace('/', '.') + " is a class here, called as an interface: " + ref.name() + ref.desc() + at);
                    } else if (!hasMethod(ref.owner(), ref.name() + ref.desc())) {
                        problems.add("missing method " + ref.owner().replace('/', '.') + "." + ref.name() + ref.desc() + at);
                    }
                }
                case "invokevirtual", "invokestatic", "invokespecial" -> {
                    if (owner.isInterface() && !ref.kind().equals("invokestatic")) {
                        problems.add(ref.owner().replace('/', '.') + " is an interface here, called as a class: " + ref.name() + ref.desc() + at);
                    } else if (!hasMethod(ref.owner(), ref.name() + ref.desc())) {
                        problems.add("missing method " + ref.owner().replace('/', '.') + "." + ref.name() + ref.desc() + at);
                    }
                }
                case "field" -> {
                    if (!hasField(ref.owner(), ref.name() + ":" + ref.desc())) {
                        problems.add("missing field " + ref.owner().replace('/', '.') + "." + ref.name() + " " + ref.desc() + at);
                    }
                }
                case "extends" -> {
                    if (owner.isInterface()) {
                        problems.add(ref.owner().replace('/', '.') + " is an interface here, extended as a class" + at);
                    }
                }
                case "implements" -> {
                    if (!owner.isInterface()) {
                        problems.add(ref.owner().replace('/', '.') + " is a class here, implemented as an interface" + at);
                    }
                }
                default -> {
                    // "type": the class exists, which is all a cast, instanceof or class literal needs
                }
            }
        }
        return problems;
    }

    private boolean hasMethod(String owner, String signature) throws IOException {
        Info info = info(owner);
        if (info == null) {
            return false;
        }
        if (info.methods().contains(signature)) {
            return true;
        }
        if (info.superName() != null && hasMethod(info.superName(), signature)) {
            return true;
        }
        for (String iface : info.interfaces()) {
            if (hasMethod(iface, signature)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasField(String owner, String signature) throws IOException {
        Info info = info(owner);
        if (info == null) {
            return false;
        }
        if (info.fields().contains(signature)) {
            return true;
        }
        if (info.superName() != null && hasField(info.superName(), signature)) {
            return true;
        }
        for (String iface : info.interfaces()) {
            if (hasField(iface, signature)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Newer API jars are compiled for Java 25, whose class files this ASM version refuses by version number alone. Only the
     * class structure is read here (names, access flags, signatures), which that format change leaves alone, so the version
     * is lowered to Java 21 before reading.
     */
    static byte[] readable(byte[] classFile) {
        int major = ((classFile[6] & 0xff) << 8) | (classFile[7] & 0xff);
        if (major > Opcodes.V21) {
            classFile[6] = 0;
            classFile[7] = (byte) Opcodes.V21;
        }
        return classFile;
    }

    /** A class from the API jar, or from the JDK for java.* supertypes; null when neither has it. */
    private Info info(String internalName) throws IOException {
        if (cache.containsKey(internalName)) {
            return cache.get(internalName);
        }
        InputStream in = null;
        JarEntry entry = api.getJarEntry(internalName + ".class");
        if (entry != null) {
            in = api.getInputStream(entry);
        } else if (internalName.startsWith("java/")) {
            in = ClassLoader.getSystemResourceAsStream(internalName + ".class");
        }
        Info info = null;
        if (in != null) {
            try (InputStream stream = in) {
                ClassNode node = new ClassNode();
                new ClassReader(readable(stream.readAllBytes())).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
                Set<String> methods = new java.util.HashSet<>();
                node.methods.forEach(m -> methods.add(m.name + m.desc));
                Set<String> fields = new java.util.HashSet<>();
                node.fields.forEach(f -> fields.add(f.name + ":" + f.desc));
                info = new Info((node.access & Opcodes.ACC_INTERFACE) != 0, node.superName, node.interfaces, methods, fields);
            }
        }
        cache.put(internalName, info);
        return info;
    }
}
