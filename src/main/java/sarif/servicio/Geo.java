package sarif.servicio;

/**
 * Cálculos geográficos sobre coordenadas WGS 84 (las que usan FIRMS y Open-Meteo).
 * La dejé como clase utilitaria aparte porque la distancia la necesito en las alertas del CU15
 * y así la pruebo sola con JUnit.
 */
public final class Geo {

    private static final double RADIO_TIERRA_KM = 6371.0;

    private Geo() {
    }

    /**
     * Distancia sobre la superficie terrestre entre dos puntos, en kilómetros, con la fórmula del
     * semiverseno (haversine). Tomo la Tierra como esfera de radio medio 6371 km; el error es chico
     * para las distancias de pocos kilómetros que manejo en las alertas.
     */
    public static double distanciaKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * RADIO_TIERRA_KM * Math.asin(Math.sqrt(a));
    }
}
