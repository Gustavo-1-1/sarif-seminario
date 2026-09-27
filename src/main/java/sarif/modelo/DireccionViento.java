package sarif.modelo;

/**
 * Interpretación de la dirección del viento. Los servicios meteorológicos la dan en grados desde donde
 * sopla (convención meteorológica: 0 = viene del norte, 90 = del este). Para un incendio interesa lo
 * contrario, hacia dónde empuja el fuego: un viento del oeste (270°) lleva las llamas hacia el este.
 */
public final class DireccionViento {

    private static final String[] PUNTOS = {"N", "NE", "E", "SE", "S", "SO", "O", "NO"};
    // Flechas que apuntan hacia cada uno de los ocho puntos, en el mismo orden que PUNTOS.
    private static final String[] FLECHAS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    private DireccionViento() {
    }

    /** Punto cardinal más cercano (de ocho) para una dirección en grados. */
    public static String puntoCardinal(int grados) {
        return PUNTOS[sector(grados)];
    }

    /** Hacia dónde va el viento (y el fuego que empuja): la dirección opuesta a la de origen. */
    public static int haciaDonde(int gradosDesde) {
        return (normalizar(gradosDesde) + 180) % 360;
    }

    /**
     * Texto para las tablas, por ejemplo "O → E (270°)": de dónde viene, una flecha que apunta hacia
     * donde va, y los grados. Null si no hay dato.
     */
    public static String describir(Integer gradosDesde) {
        if (gradosDesde == null) {
            return null;
        }
        return puntoCardinal(gradosDesde) + " " + FLECHAS[sector(haciaDonde(gradosDesde))] + " "
                + puntoCardinal(haciaDonde(gradosDesde)) + " (" + normalizar(gradosDesde) + "°)";
    }

    private static int sector(int grados) {
        return (int) Math.round(normalizar(grados) / 45.0) % 8;
    }

    private static int normalizar(int grados) {
        return ((grados % 360) + 360) % 360;
    }
}
