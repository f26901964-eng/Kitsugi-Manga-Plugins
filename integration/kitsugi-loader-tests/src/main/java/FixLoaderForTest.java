import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.instruction.BuilderInstruction3rc;
import org.jf.dexlib2.builder.instruction.BuilderInstruction11x;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.immutable.ImmutableClassDef;
import org.jf.dexlib2.immutable.ImmutableDexFile;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.writer.pool.DexPool;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** TEST ONLY. Not signed and never distributed as a phone update.
 * Keep the real release APK and all real host APIs; change ONLY the two loader
 * entry points to call File.setReadOnly before class loading. The Kotlin patch
 * in integration/kitsugi-mex is the actual app-maintainer deliverable.
 */
public final class FixLoaderForTest {
    private static final String TYPE = "Lcom/kitsugi/animelist/data/manga/MangaExtensionLoader;";
    private static int patched;
    private static int filtered;
    public static void main(String[] args) throws Exception {
        Path original = Path.of(args[0]), output = Path.of(args[1]);
        byte[] apkBytes = Files.readAllBytes(original);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(apkBytes));
        if (!digest.equals("423601778fa865e14b23c99fc60077de06fc85425b466881a3d109b802e2e511")) throw new IllegalStateException("Unrecognized original Kitsugi APK");
        Path temp = Files.createTempDirectory("loader-test-dex-");
        Map<String, Path> replacements = new HashMap<>();
        ClassDef changed = null; int largest = 1;
        try (ZipFile zip = new ZipFile(original.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                if (!name.matches("classes(?:[0-9]+)?\\.dex")) continue;
                if (!name.equals("classes.dex")) largest = Math.max(largest, Integer.parseInt(name.substring(7, name.length() - 4)));
                DexBackedDexFile dex;
                try (InputStream input = new BufferedInputStream(zip.getInputStream(entry))) { dex = DexBackedDexFile.fromInputStream(Opcodes.forApi(26), input); }
                List<ClassDef> remaining = new ArrayList<>(); boolean found = false;
                for (ClassDef cls : dex.getClasses()) {
                    if (!cls.getType().equals(TYPE)) { remaining.add(cls); continue; }
                    if (changed != null) throw new IllegalStateException("Duplicate loader class");
                    List<Method> methods = new ArrayList<>();
                    for (Method method : cls.getMethods()) methods.add(fix(method));
                    changed = new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(), cls.getInterfaces(), cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods);
                    found = true;
                }
                if (found) {
                    Path replacement = temp.resolve(name);
                    DexPool.writeTo(replacement.toString(), new ImmutableDexFile(dex.getOpcodes(), remaining));
                    replacements.put(name, replacement);
                }
            }
            if (changed == null || patched != 2 || filtered < 1) throw new IllegalStateException("Expected exactly APK and MEX loader methods, got " + patched);
            Path newDex = temp.resolve("changed.dex");
            List<ClassDef> additions = new ArrayList<>(); additions.add(changed);
            try (InputStream policyInput = new BufferedInputStream(Files.newInputStream(Path.of(args[2])))) {
                DexBackedDexFile policy = DexBackedDexFile.fromInputStream(Opcodes.forApi(26), policyInput);
                for (ClassDef definition : policy.getClasses()) {
                    if (!definition.getType().equals("Lcom/kitsugi/animelist/data/manga/MangaDeniziRecoveryPolicy;")) throw new IllegalStateException("Unexpected recovery helper class");
                    additions.add(definition);
                }
            }
            if (additions.size() != 2) throw new IllegalStateException("Recovery helper missing");
            DexPool.writeTo(newDex.toString(), new ImmutableDexFile(Opcodes.forApi(26), additions));
            Files.createDirectories(output.getParent());
            try (ZipOutputStream target = new ZipOutputStream(Files.newOutputStream(output))) {
                for (ZipEntry entry : Collections.list(zip.entries())) {
                    String name = entry.getName();
                    if (name.matches("META-INF/.*\\.(?:RSA|DSA|EC|SF|MF)")) continue;
                    target.putNextEntry(new ZipEntry(name));
                    if (replacements.containsKey(name)) Files.copy(replacements.get(name), target);
                    else try (InputStream input = zip.getInputStream(entry)) { input.transferTo(target); }
                    target.closeEntry();
                }
                target.putNextEntry(new ZipEntry("classes" + (largest + 1) + ".dex"));
                Files.copy(newDex, target); target.closeEntry();
            }
        }
        System.out.println("TEST FIXTURE ONLY: two File.setReadOnly calls plus temporary TR selection; no fake host API classes; not a signed APK update");
    }
    private static Method fix(Method method) {
        if (method.getName().equals("getLoadedSources")) {
            MutableMethodImplementation body = new MutableMethodImplementation(method.getImplementation());
            for (int i = body.getInstructions().size() - 1; i >= 0; i--) {
                if (body.getInstructions().get(i).getOpcode() != Opcode.RETURN_OBJECT) continue;
                int register = ((OneRegisterInstruction) body.getInstructions().get(i)).getRegisterA();
                body.addInstruction(i, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, register, 1, new ImmutableMethodReference("Lcom/kitsugi/animelist/data/manga/MangaDeniziRecoveryPolicy;", "filterSources", List.of("Ljava/util/List;"), "Ljava/util/List;")));
                body.addInstruction(i + 1, new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, register));
                filtered++;
            }
            return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(), method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), body);
        }
        if (!(method.getName().equals("loadNativeMex") || method.getName().equals("loadMihonApk"))) return method;
        if (method.getParameters().size() != 3 || !method.getParameters().get(2).getType().equals("Ljava/io/File;") || method.getImplementation() == null) throw new IllegalStateException("Unexpected loader ABI");
        MutableMethodImplementation implementation = new MutableMethodImplementation(method.getImplementation());
        int fileRegister = implementation.getRegisterCount() - 1;
        implementation.addInstruction(0, new BuilderInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, fileRegister, 1, new ImmutableMethodReference("Ljava/io/File;", "setReadOnly", List.of(), "Z")));
        patched++;
        return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(), method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), implementation);
    }
}
