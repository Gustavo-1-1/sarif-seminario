package sarif.modelo;

/**
 * Zona que se puede elegir al ajustar el despliegue a mano (CU10 paso 5). Lleva el índice de la fecha si ya se
 * calculó; si no (por ejemplo, una zona recién dada de alta o sin meteorología del día), indice es null y la
 * asignación se registra sin índice que la fundamente. El nivel de alerta vigente lo muestro para que el jefe de
 * guardia vea, aunque falte el índice, que la zona está en ALERTA o EMERGENCIA.
 *
 * @param nivelAlerta nombre del nivel de alerta vigente, o null si la zona no tiene
 */
public record ZonaParaAsignar(int idZona, String nombreZona, IndiceRiesgo indice, String nivelAlerta) {
}
