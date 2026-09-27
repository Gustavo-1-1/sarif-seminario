package sarif.modelo;

/**
 * Acciones de SARIF que dependen del rol. Las agrupé por responsabilidad y no una por botón: registrar
 * zonas, sincronizar o atender alertas son todas parte de "operar la guardia", que es lo que el AP1 le
 * asigna al operador de guardia en todos los casos de uso. Aparte quedan las decisiones que en los
 * procesos del organismo toma el jefe de guardia (el nivel de alerta y la asignación de brigadas) y las
 * tareas técnicas del administrador (configuración y usuarios).
 */
public enum Permiso {

    OPERAR("operar la guardia"),
    CAMBIAR_NIVEL_ALERTA("cambiar el nivel de alerta de una zona"),
    ASIGNAR_RECURSOS("confirmar asignaciones de recursos"),
    CONFIGURAR("modificar la configuración del sistema"),
    ADMINISTRAR_USUARIOS("administrar usuarios");

    private final String descripcion;

    Permiso(String descripcion) {
        this.descripcion = descripcion;
    }

    /** Texto para los mensajes, por ejemplo "Su rol no le permite cambiar el nivel de alerta de una zona." */
    public String descripcion() {
        return descripcion;
    }
}
