package sarif.servicio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba unitaria de la proyección Web Mercator del mapa de riesgo (PU-27).
 *
 * <p>Verifico los bordes del mundo, la simetría respecto del ecuador, que ida y vuelta devuelva la misma
 * coordenada, que Neuquén capital caiga en el mosaico que publica OpenTopoMap para esa ciudad, la escala
 * en metros por píxel y el zoom para encuadrar la provincia. No dibuja nada, así que no necesita JavaFX.</p>
 */
class ProyeccionMercatorTest {

    @Test
    @DisplayName("PU-27 Proyección Web Mercator: bordes, ida y vuelta, mosaico, escala y encuadre")
    void proyeccion() {
        // En el zoom 0 el mundo es un mosaico de 256 píxeles: el antimeridiano va en los bordes y el ecuador en el medio.
        assertEquals(0, ProyeccionMercator.x(-180, 0), 1e-9);
        assertEquals(256, ProyeccionMercator.x(180, 0), 1e-9);
        assertEquals(128, ProyeccionMercator.y(0, 0), 1e-9);
        // Cada zoom duplica el tamaño y el sur queda abajo, a la misma distancia del ecuador que el norte.
        assertEquals(1024, ProyeccionMercator.tamanoMundo(2), 1e-9);
        assertEquals(1024 - ProyeccionMercator.y(38.95, 2), ProyeccionMercator.y(-38.95, 2), 1e-9);

        // Ida y vuelta con Villa Caviahue, con un zoom con decimales como los de la rueda del mouse.
        double x = ProyeccionMercator.x(-71.055, 11.5), y = ProyeccionMercator.y(-37.87, 11.5);
        assertEquals(-71.055, ProyeccionMercator.longitud(x, 11.5), 1e-9);
        assertEquals(-37.87, ProyeccionMercator.latitud(y, 11.5), 1e-9);

        // Neuquén capital (-38.95, -68.06) cae en el mosaico 9/159/316.
        assertEquals(159, (int) (ProyeccionMercator.x(-68.06, 9) / 256));
        assertEquals(316, (int) (ProyeccionMercator.y(-38.95, 9) / 256));

        // Escala: 156 543 m por píxel en el ecuador en el zoom 0; a la latitud de Neuquén y zoom 10, unos 119 m,
        // así que el radio de 5 km de un activo mide unos 42 píxeles.
        assertEquals(156543.03, ProyeccionMercator.metrosPorPixel(0, 0), 0.01);
        assertEquals(119.5, ProyeccionMercator.metrosPorPixel(-38.6, 10), 0.1);
        assertEquals(41.85, ProyeccionMercator.radioEnPixeles(5, -38.6, 10), 0.05);

        // La provincia entera (5,4° de alto, estirados por Mercator a esa latitud) entra en 800 × 800 píxeles
        // con un zoom de entre 7 y 7,5; con uno más, ya no entraría.
        double zoom = ProyeccionMercator.zoomParaEncuadrar(-41.3, -35.9, -72.2, -67.8, 800, 800);
        assertTrue(zoom > 7 && zoom < 7.5, "zoom " + zoom);
        assertTrue(ProyeccionMercator.y(-41.3, zoom) - ProyeccionMercator.y(-35.9, zoom) <= 800.001);
    }
}
