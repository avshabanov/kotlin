package org.jetbrains.jet.lang.resolve.scopes.receivers;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.lang.types.JetType;

/**
 * @author abreslav
 */
public interface ReceiverDescriptor {

    ReceiverDescriptor NO_RECEIVER = new ReceiverDescriptor() {
        @NotNull
        @Override
        public JetType getType() {
            throw new UnsupportedOperationException("NO_RECEIVER.getType()");
        }

        @Override
        public boolean exists() {
            return false;
        }

        @Override
        public <R, D> R accept(@NotNull ReceiverDescriptorVisitor<R, D> visitor, D data) {
            return visitor.visitNoReceiver(this, data);
        }

        @Override
        public String toString() {
            return "NO_RECEIVER";
        }
    };

    @NotNull
    JetType getType();

    boolean exists();

    <R, D> R accept(@NotNull ReceiverDescriptorVisitor<R, D> visitor, D data);
}
