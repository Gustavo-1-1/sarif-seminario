package sarif.modelo;

import java.time.LocalDate;

/**
 * Índice de riesgo diario de una zona (CU09), con sus tres componentes (histórica, meteorológica y de
 * combustible), el valor final ponderado y el nivel de riesgo que le corresponde.
 * Guardo las componentes por separado y no solo el resultado para poder explicar de dónde sale el valor (CU09).
 * El id es Integer porque es null mientras el índice no se registró.
 */
public record IndiceRiesgo(Integer id, int idZona, String nombreZona, LocalDate fecha, double compHistorica,
                           double compMeteorologica, double compCombustible, double valorFinal, String nivel) {
}
