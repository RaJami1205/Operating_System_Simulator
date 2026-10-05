package io.github.rajami1205.osimulator.model.cpu;

/** Destino permitido de MOV: registros generales y de servicio; AH/AL no se admiten como fuentes generales. */
public sealed interface MovDestination permits RegisterName, ServiceRegister {
    /** Expone el nombre simbólico del destino para formatting de instrucciones. */
    String name();
}
