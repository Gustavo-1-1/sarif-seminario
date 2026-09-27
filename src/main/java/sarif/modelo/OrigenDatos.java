package sarif.modelo;

/**
 * Origen de un lote de datos: el servicio remoto, un archivo local (modo sin conexión, RNF06) o la carga manual.
 * Lo registro en cada sincronización y en los datos meteorológicos para saber después de dónde salió cada dato.
 */
public enum OrigenDatos {
    REMOTA, ARCHIVO, MANUAL
}
