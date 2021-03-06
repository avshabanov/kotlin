package org.jetbrains.jet.lang.cfg.pseudocode;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.lang.psi.JetElement;

import java.util.Collection;
import java.util.Collections;

/**
 * @author abreslav
 */
public class SubroutineExitInstruction extends InstructionImpl {
    private final JetElement subroutine;
    private final String debugLabel;
    private SubroutineSinkInstruction sinkInstruction;

    public SubroutineExitInstruction(@NotNull JetElement subroutine, @NotNull String debugLabel) {
        this.subroutine = subroutine;
        this.debugLabel = debugLabel;
    }

    public JetElement getSubroutine() {
        return subroutine;
    }

    public void setSink(SubroutineSinkInstruction instruction) {
        sinkInstruction = (SubroutineSinkInstruction) outgoingEdgeTo(instruction);
    }

    @NotNull
    @Override
    public Collection<Instruction> getNextInstructions() {
        if (sinkInstruction != null) {
            return Collections.<Instruction>singleton(sinkInstruction);
        }
        return Collections.emptyList();
    }

    @Override
    public void accept(InstructionVisitor visitor) {
        visitor.visitSubroutineExit(this);
    }

    @Override
    public String toString() {
        return debugLabel;
    }
}
