package sarif.modelo;

/**
 * Una fila de la pantalla "Fuentes de datos": un servicio externo o un archivo del modo sin conexión,
 * con su dirección, cómo está configurado, cuándo se usó por última vez y el resultado de la última prueba.
 * El estado va en mayúsculas y sin espacios (CONECTADA, SIN_CONEXION, DISPONIBLE, FALTANTE, SIN_PROBAR)
 * porque también es el sufijo de la clase CSS de la etiqueta de color.
 */
public record FuenteConfigurada(String nombre, String tipo, String direccion, String configuracion,
                                String ultimaSincronizacion, String estado, String detalleEstado) {

    /** Copia de la fila con otro estado; la uso al terminar una prueba de conexión. */
    public FuenteConfigurada conEstado(String nuevoEstado, String nuevoDetalle) {
        return new FuenteConfigurada(nombre, tipo, direccion, configuracion, ultimaSincronizacion, nuevoEstado, nuevoDetalle);
    }
}
