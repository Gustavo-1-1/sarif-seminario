package sarif.servicio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Prueba unitaria del cálculo de distancias geodésicas (PU-09).
 *
 * <p>La distancia entre un foco y un activo protegido decide si se genera la alerta de activo en riesgo,
 * así que quise comprobarla contra valores conocidos. Geo es una clase de utilidad sin estado, por lo
 * que la prueba no necesita la base de datos ni ningún otro componente.</p>
 */
class GeoTest {

    @Test
    @DisplayName("PU-09 Distancia geodésica: 1 grado de latitud equivale a unos 111,19 km")
    void unGradoDeLatitud() {
        // Un grado de latitud sobre el mismo meridiano mide unos 111,19 km (con el radio medio de la Tierra).
        assertEquals(111.19, Geo.distanciaKm(-38.0, -71.0, -39.0, -71.0), 0.01);
        // La distancia de un punto a sí mismo tiene que ser exactamente cero.
        assertEquals(0.0, Geo.distanciaKm(-38.0, -71.0, -38.0, -71.0), 1e-9);
        // Caso real de la jornada: el foco de Caviahue queda a 2,72 km de Villa Caviahue.
        assertEquals(2.72, Geo.distanciaKm(-37.8455, -71.055, -37.87, -71.055), 0.005);
    }
}
