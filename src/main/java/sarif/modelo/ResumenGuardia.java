package sarif.modelo;

/**
 * Totales de la cronología de la guardia en el período: cuántos hitos de cada tipo hubo y cuánto se tardó,
 * en promedio y como máximo, en avisar de una alerta desde que el sistema la generó.
 *
 * @param minutosPromedioAviso null si en el período no se notificó ninguna alerta
 */
public record ResumenGuardia(int alertas, int notificadas, int cerradas, int descartadas, int cambiosNivel,
                             int asignaciones, Double minutosPromedioAviso, Long minutosMaximoAviso) {
}
