package io.github.ryzemcbe.smoke;

import org.allaymc.server.command.tree.node.StringNode;
import org.allaymc.api.command.tree.CommandNode;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParam;

/** Compile-only linkage gate: server internals, stable API and Bedrock codec. */
public final class EngineCentralSmoke {
    private EngineCentralSmoke() { }

    public static Class<?>[] linkedTypes() {
        return new Class<?>[] {
            StringNode.class,
            CommandNode.class,
            CommandParam.class
        };
    }
}
