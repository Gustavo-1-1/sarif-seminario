package sarif.modelo;

import java.time.LocalDateTime;

/**
 * Última ubicación informada de un recurso desplegado, con los datos del recurso para dibujarlo en el mapa.
 *
 * @param zona    nombre de la zona que contiene el punto, o null si está fuera de las zonas
 * @param usuario nombre completo de quien informó la ubicación
 */
public record PosicionRecurso(int idRecurso, String denominacion, TipoRecurso tipo, int dotacion, double latitud,
                              double longitud, String zona, LocalDateTime fechaHora, String usuario,
                              String observaciones) {

    public static final int LARGO_MAXIMO_OBSERVACIONES = 120;
}
