package sarif.modelo;

/**
 * Una fila del reporte de estacionalidad: los focos registrados en un mes del año (sumando todas las
 * temporadas), su promedio por año y qué porcentaje del total representan. La marca indica si el mes forma
 * parte de la temporada crítica, para resaltarlo en la tabla.
 */
public record FilaMes(String mes, int focos, double promedioAnual, double porcentaje, boolean temporadaCritica) {
}
