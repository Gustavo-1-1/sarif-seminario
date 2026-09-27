package sarif.modelo;

import java.time.LocalDate;

/**
 * Fila del tablero de zonas (CU06, RFS14): los valores vigentes de cada zona, tal como los devuelve
 * la vista v_tablero_zonas. No es una entidad, es solo una proyección para mostrar en pantalla.
 * fechaIndice, indice y los niveles pueden venir en null si la zona todavía no tiene índice calculado.
 */
public record FilaTablero(int idZona, String nombre, boolean vigilanciaActiva, LocalDate fechaIndice,
                          Double indice, String nivelRiesgo, String nivelAlerta) {
}
