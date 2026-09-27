package sarif.modelo;

/**
 * Nivel de riesgo con sus umbrales, leído del catálogo nivel_riesgo. El intervalo incluye el mínimo
 * y excluye el máximo, salvo en el nivel superior (que incluye también el máximo), así ningún valor
 * del índice queda sin nivel ni cae en dos niveles a la vez. El color lo uso en el tablero.
 */
public record NivelRiesgo(int id, String nombre, double umbralMin, double umbralMax, int orden, String color) {
}
