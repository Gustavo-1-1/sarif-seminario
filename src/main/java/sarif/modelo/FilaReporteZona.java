package sarif.modelo;

/**
 * Una fila del reporte histórico por zona y período (RFS18, CU13): cuántos focos tuvo la zona, cuántos de
 * alta confianza, su potencia promedio y cómo estuvo su índice de riesgo en esos días.
 * Los promedios son Double porque quedan en null si en el período no hubo focos o índices calculados.
 */
public record FilaReporteZona(String zona, int focos, int focosAltaConfianza, Double frpPromedioMw, int diasConIndice,
                              Double indicePromedio, Double indiceMaximo, int diasAltoOExtremo) {
}
