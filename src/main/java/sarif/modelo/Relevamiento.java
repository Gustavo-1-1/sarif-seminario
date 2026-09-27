package sarif.modelo;

import java.time.LocalDate;

/**
 * Relevamiento de combustible hecho a campo en una zona (RFS21): tipo de vegetación y carga en toneladas
 * por hectárea. No guardo el combustible "actual" en la zona; el vigente es el último relevamiento
 * (vista v_combustible_vigente), así conservo todo el historial.
 */
public record Relevamiento(int idZona, TipoCombustible tipo, double cargaTHa, LocalDate fecha) {
}
