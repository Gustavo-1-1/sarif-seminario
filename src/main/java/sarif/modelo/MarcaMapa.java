package sarif.modelo;

import java.time.LocalDateTime;

/**
 * Marca operativa vigente en el mapa (las quitadas no se listan).
 *
 * @param zona    nombre de la zona que contiene el punto, o null si está fuera de las zonas
 * @param usuario nombre completo de quien la puso
 */
public record MarcaMapa(int id, TipoMarca tipo, String descripcion, double latitud, double longitud, String zona,
                        LocalDateTime fechaHora, String usuario) {

    public static final int LARGO_MAXIMO_DESCRIPCION = 120;
}
