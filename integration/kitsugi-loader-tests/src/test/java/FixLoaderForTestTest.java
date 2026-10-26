import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.builder.MethodImplementationBuilder;
import org.jf.dexlib2.builder.instruction.BuilderInstruction10t;
import org.jf.dexlib2.builder.instruction.BuilderInstruction11x;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.OffsetInstruction;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class FixLoaderForTestTest {
    @Test public void aBranchToReturnCannotSkipRecoveryFilter() {
        MethodImplementationBuilder body = new MethodImplementationBuilder(1);
        body.addInstruction(new BuilderInstruction10t(Opcode.GOTO, body.getLabel("done")));
        body.addLabel("done");
        body.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT, 0));
        Method original = new ImmutableMethod("Lcom/kitsugi/animelist/data/manga/MangaExtensionLoader;", "getLoadedSources", List.of(), "Ljava/util/List;", AccessFlags.PUBLIC.getValue(), null, null, body.getMethodImplementation());
        Method changed = FixLoaderForTest.fix(original);
        List<Instruction> instructions = new ArrayList<>(); changed.getImplementation().getInstructions().forEach(instructions::add);
        assertEquals(Opcode.INVOKE_STATIC_RANGE, instructions.get(1).getOpcode());
        assertEquals(Opcode.MOVE_RESULT_OBJECT, instructions.get(2).getOpcode());
        assertEquals(Opcode.RETURN_OBJECT, instructions.get(3).getOpcode());
        assertEquals(1, ((OffsetInstruction) instructions.get(0)).getCodeOffset());
    }
}
