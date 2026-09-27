package sarif.servicio;

/**
 * Proyección Web Mercator, la que usan OpenStreetMap, OpenTopoMap y casi todos los mapas por mosaicos.
 * <p>
 * El mundo entero es un cuadrado de 256 × 2^zoom píxeles: en el zoom 0 entra en un solo mosaico de 256 × 256,
 * en el zoom 1 en 2 × 2 mosaicos, y así. La longitud se reparte en forma pareja a lo ancho; la latitud no:
 * Mercator estira el mapa hacia los polos (por eso Groenlandia se ve enorme), y la fórmula de y lo refleja.
 * Acá paso de latitud y longitud a píxeles de ese "mundo" y al revés, para un zoom cualquiera (también con
 * decimales, así el zoom con la rueda del mouse es suave). El controlador del mapa resta el desplazamiento
 * de la vista para llevarlo a la pantalla.
 * <p>
 * Todos los métodos son estáticos porque no hay nada que guardar: son fórmulas.
 */
public final class ProyeccionMercator {

    public static final int TAMANO_MOSAICO = 256;
    /** Mercator no llega a los polos: más allá de ±85,05° la y se va al infinito, así que recorto ahí. */
    public static final double LATITUD_LIMITE = 85.05112878;
    // Circunferencia de la Tierra en el ecuador dividida por 256: metros por píxel en el zoom 0.
    private static final double METROS_POR_PIXEL_ZOOM0 = 156543.03392;

    private ProyeccionMercator() {
    }

    /** Ancho (y alto) del mundo en píxeles para ese zoom. */
    public static double tamanoMundo(double zoom) {
        return TAMANO_MOSAICO * Math.pow(2, zoom);
    }

    public static double x(double longitud, double zoom) {
        return (longitud + 180) / 360 * tamanoMundo(zoom);
    }

    public static double y(double latitud, double zoom) {
        double lat = Math.toRadians(Math.max(-LATITUD_LIMITE, Math.min(LATITUD_LIMITE, latitud)));
        return (1 - Math.log(Math.tan(lat) + 1 / Math.cos(lat)) / Math.PI) / 2 * tamanoMundo(zoom);
    }

    public static double longitud(double x, double zoom) {
        return x / tamanoMundo(zoom) * 360 - 180;
    }

    public static double latitud(double y, double zoom) {
        double n = Math.PI * (1 - 2 * y / tamanoMundo(zoom));
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    /**
     * Metros que representa un píxel a esa latitud y zoom. En Mercator la escala cambia con la latitud
     * (se multiplica por el coseno), por eso la barra de escala se calcula en el centro de la vista.
     */
    public static double metrosPorPixel(double latitud, double zoom) {
        return METROS_POR_PIXEL_ZOOM0 * Math.cos(Math.toRadians(latitud)) / Math.pow(2, zoom);
    }

    /** Radio en píxeles de un círculo de {@code km} kilómetros a esa latitud (en Mercator un círculo sigue siendo círculo). */
    public static double radioEnPixeles(double km, double latitud, double zoom) {
        return km * 1000 / metrosPorPixel(latitud, zoom);
    }

    /**
     * Zoom que hace entrar el rectángulo geográfico en un área de pantalla de ancho × alto píxeles.
     * Mido el rectángulo en el zoom 0 y veo cuántas veces hay que duplicarlo para llenar la pantalla.
     */
    public static double zoomParaEncuadrar(double latMin, double latMax, double lonMin, double lonMax,
                                           double ancho, double alto) {
        double dx = Math.max(1e-9, x(lonMax, 0) - x(lonMin, 0));
        double dy = Math.max(1e-9, y(latMin, 0) - y(latMax, 0));
        return Math.log(Math.min(ancho / dx, alto / dy)) / Math.log(2);
    }
}
