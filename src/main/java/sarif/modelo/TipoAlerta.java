package sarif.modelo;

/**
 * Tipo de alerta. Las dos primeras salen de un foco: dentro de una zona vigilada (CU14) o cerca de un
 * activo protegido (CU15). Las dos últimas salen de cruzar el índice de riesgo del día con los demás
 * datos: la zona subió a ALTO o EXTREMO, o hay focos confiables en una zona que ya está en riesgo alto.
 * NIVEL_ELEVADO sale de un cambio de nivel de alerta hecho a mano (CU12) que sube el nivel de la zona.
 * Los nombres coinciden con los valores que acepta la columna tipo de la tabla alerta.
 */
public enum TipoAlerta {
    FOCO_EN_ZONA("Foco en zona"),
    ACTIVO_EN_RIESGO("Activo en riesgo"),
    RIESGO_ELEVADO("Riesgo elevado"),
    FOCO_CON_RIESGO_ALTO("Foco con riesgo alto"),
    NIVEL_ELEVADO("Nivel de alerta elevado");

    private final String texto;

    TipoAlerta(String texto) {
        this.texto = texto;
    }

    /** Nombre legible para mostrar en la pantalla. */
    public String texto() {
        return texto;
    }

    /** Las alertas de riesgo son las que se generan a partir del índice y pueden sugerir un nivel de alerta. */
    public boolean esDeRiesgo() {
        return this == RIESGO_ELEVADO || this == FOCO_CON_RIESGO_ALTO;
    }
}
