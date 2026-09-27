package sarif.modelo;

/**
 * Medio por el que la guardia avisó de una alerta. SARIF no manda mensajes: el aviso lo da una persona
 * por radio, teléfono, etc., y acá queda registrado cómo lo hizo. SISTEMA no lo elige el operador: lo uso
 * cuando la alerta se atiende aplicando el nivel sugerido, porque ese cambio queda publicado en SARIF.
 * Los nombres coinciden con los valores de la columna medio_notificacion de la tabla alerta.
 */
public enum MedioNotificacion {
    RADIO("Radio"),
    TELEFONO("Teléfono"),
    MENSAJE("Mensaje escrito"),
    PRESENCIAL("En persona"),
    SISTEMA("Cambio de nivel en SARIF");

    private final String texto;

    MedioNotificacion(String texto) {
        this.texto = texto;
    }

    public String texto() {
        return texto;
    }

    /** Medios que puede elegir el operador al notificar (todos menos SISTEMA). */
    public static MedioNotificacion[] elegibles() {
        return new MedioNotificacion[]{RADIO, TELEFONO, MENSAJE, PRESENCIAL};
    }

    @Override
    public String toString() {
        return texto;
    }
}
