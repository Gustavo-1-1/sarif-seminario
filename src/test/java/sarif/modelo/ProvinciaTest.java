package sarif.modelo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba de la lista de provincias del mapa (PU-35): están las 24 jurisdicciones, sin repetir, y cada rectángulo
 * es válido y cae dentro de la vista "Argentina" del mapa, así ninguna queda mal encuadrada por un error de tipeo.
 */
class ProvinciaTest {

    @Test
    @DisplayName("PU-35 Provincias del mapa: las 24 jurisdicciones, sin repetir y con rectángulos válidos dentro del país")
    void provincias() {
        assertEquals(24, Provincia.TODAS.size());
        assertEquals(24, Provincia.TODAS.stream().map(Provincia::nombre).distinct().count());
        for (Provincia p : Provincia.TODAS) {
            assertTrue(p.latMin() < p.latMax() && p.lonMin() < p.lonMax(), p.nombre());
            assertTrue(p.latMin() >= -55.5 && p.latMax() <= -21.0 && p.lonMin() >= -76.0 && p.lonMax() <= -52.0, p.nombre());
        }
        // Neuquén contiene la zona de Caviahue de la jornada de prueba.
        Provincia neuquen = Provincia.TODAS.stream().filter(p -> p.nombre().equals("Neuquén")).findFirst().orElseThrow();
        assertTrue(-37.85 > neuquen.latMin() && -37.85 < neuquen.latMax() && -71.05 > neuquen.lonMin() && -71.05 < neuquen.lonMax());
    }
}
