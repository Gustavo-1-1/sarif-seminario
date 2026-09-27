package sarif.modelo;

/**
 * Confianza de una detección satelital, normalizada entre los instrumentos VIIRS y MODIS (RFS09).
 * Cada instrumento la informa distinto (VIIRS con letras y MODIS con un porcentaje), así que la llevo
 * a tres valores comunes para poder filtrar y comparar focos sin importar de qué satélite vienen.
 */
public enum Confianza {
    BAJA, NOMINAL, ALTA;

    /**
     * VIIRS informa la confianza con una letra: l (low), n (nominal) o h (high).
     * Acepto también la palabra completa por si el archivo viene así, y lanzo excepción si llega
     * otra cosa para no guardar un dato inventado.
     */
    public static Confianza desdeViirs(String valor) {
        switch (valor.trim().toLowerCase()) {
            case "l":
            case "low":
                return BAJA;
            case "n":
            case "nominal":
                return NOMINAL;
            case "h":
            case "high":
                return ALTA;
            default:
                throw new IllegalArgumentException("Confianza VIIRS desconocida: " + valor);
        }
    }

    /** MODIS informa un porcentaje: menos de 30 es baja, de 30 a 79 nominal y 80 o más alta. */
    public static Confianza desdeModis(int porcentaje) {
        // Un porcentaje fuera de 0-100 indica un dato corrupto, prefiero rechazarlo.
        if (porcentaje < 0 || porcentaje > 100) {
            throw new IllegalArgumentException("Confianza MODIS fuera de rango: " + porcentaje);
        }
        if (porcentaje < 30) {
            return BAJA;
        }
        return porcentaje < 80 ? NOMINAL : ALTA;
    }
}
