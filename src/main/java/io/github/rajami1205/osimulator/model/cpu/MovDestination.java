package io.github.rajami1205.osimulator.model.cpu;

/** Service registers are MOV destinations, not general-purpose operands. */
public sealed interface MovDestination permits RegisterName, ServiceRegister {
    String name();
}
