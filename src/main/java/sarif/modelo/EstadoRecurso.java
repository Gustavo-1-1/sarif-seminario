package sarif.modelo;

/**
 * Estado operativo de un recurso de combate. Solo sugiero despliegues con recursos DISPONIBLE (CU10);
 * al confirmar la asignación (CU11) el recurso pasa a ASIGNADO. Uso un enum para que en el código
 * no se puedan escribir estados que la tabla recurso no acepta.
 */
public enum EstadoRecurso {
    DISPONIBLE, ASIGNADO, FUERA_DE_SERVICIO
}
