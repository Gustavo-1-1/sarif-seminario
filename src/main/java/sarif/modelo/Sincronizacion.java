package sarif.modelo;

import java.time.LocalDateTime;

/**
 * Registro de auditoría de cada lote de datos que incorporo desde una fuente (CU07, CU08, RFS08).
 * Guardo cuántos registros leí y cuántos eran nuevos, el resultado (OK o FALLIDA) y un detalle,
 * para poder revisar después qué pasó en cada sincronización.
 */
public record Sincronizacion(int id, String tipo, OrigenDatos origen, LocalDateTime inicio, String resultado,
                             int registrosLeidos, int registrosNuevos, String detalle) {
}
