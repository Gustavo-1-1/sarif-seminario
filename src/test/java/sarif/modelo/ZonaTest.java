package sarif.modelo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas unitarias de la entidad Zona (PU-07 y PU-08).
 *
 * <p>Verifico las dos reglas que la zona resuelve por sí misma: la validación de sus datos antes del
 * alta y la pertenencia de un punto a su rectángulo, que es lo que decide si un foco satelital cae en
 * la zona. Como son métodos del modelo que trabajan solo con los atributos del objeto, las pruebas no
 * necesitan la base de datos.</p>
 */
class ZonaTest {

    /** Zona válida de referencia, con los mismos límites que Caviahue - Copahue en sql/02_datos.sql. */
    private final Zona caviahue = new Zona(4, "Caviahue - Copahue", null, -37.95, -37.75, -71.20, -70.95, true);

    @Test
    @DisplayName("PU-07 Validación de zona con coordenadas invertidas y nombre vacío: tres errores")
    void validacion() {
        // Nombre en blanco, latitudes invertidas y longitudes invertidas: espero los tres errores
        // juntos, porque la validación informa todos los problemas de una vez y no solo el primero.
        Zona invalida = new Zona(0, "  ", null, -37.75, -37.95, -70.95, -71.20, false);
        assertEquals(3, invalida.validar().size());
        // Y una zona correcta no tiene que devolver ningún error.
        assertTrue(caviahue.validar().isEmpty());
    }

    @Test
    @DisplayName("PU-08 Pertenencia de un punto a la zona, bordes incluidos")
    void pertenencia() {
        // Un punto interior (el foco de alta confianza de la jornada) y las dos esquinas opuestas:
        // los bordes cuentan como parte de la zona.
        assertTrue(caviahue.contiene(-37.8455, -71.055));
        assertTrue(caviahue.contiene(-37.95, -71.20));
        assertTrue(caviahue.contiene(-37.75, -70.95));
        // Puntos apenas afuera, por una diezmilésima de grado, al norte y al este.
        assertFalse(caviahue.contiene(-37.7499, -71.0));
        assertFalse(caviahue.contiene(-37.85, -70.9499));
    }

    /**
     * Polígono en forma de "L" dentro del rectángulo de Caviahue: le falta el cuarto noreste. Es el caso que
     * justifica los polígonos: un punto de ese cuarto está en el rectángulo pero no en la zona.
     */
    private static Zona enL() {
        Zona z = new Zona();
        z.setNombre("Valle en L");
        z.setVertices(List.of(new Vertice(-37.95, -71.20), new Vertice(-37.95, -70.95), new Vertice(-37.85, -70.95),
                new Vertice(-37.85, -71.05), new Vertice(-37.75, -71.05), new Vertice(-37.75, -71.20)));
        return z;
    }

    @Test
    @DisplayName("PU-28 Zona poligonal: rectángulo que la encierra, pertenencia, superposición y validación")
    void poligono() {
        Zona l = enL();
        assertTrue(l.esPoligono());
        assertTrue(l.validar().isEmpty());
        // El rectángulo que la encierra se calcula solo a partir de los vértices.
        assertEquals(-37.95, l.getLatitudMin(), 1e-9);
        assertEquals(-70.95, l.getLongitudMax(), 1e-9);

        // Adentro de la "L", sobre un borde y en el cuarto que le falta (dentro del rectángulo, fuera de la zona).
        assertTrue(l.contiene(-37.90, -71.10));
        assertTrue(l.contiene(-37.85, -71.00));
        assertFalse(l.contiene(-37.80, -71.00));
        assertTrue(l.dentroDelRectangulo(-37.80, -71.00));
        // El punto de referencia (para la meteorología) tiene que caer adentro aunque el centro del rectángulo no.
        assertTrue(l.contiene(l.getLatitudCentro(), l.getLongitudCentro()));

        // Superposición: un rectángulo en el cuarto que le falta solo comparte bordes (no se superpone);
        // uno que se mete en la "L", sí; y la misma forma repetida, también.
        Zona hueco = new Zona(0, "Hueco", null, -37.85, -37.75, -71.05, -70.95, false);
        Zona pisa = new Zona(0, "Pisa", null, -37.88, -37.80, -71.10, -71.00, false);
        assertFalse(l.seSuperponeCon(hueco));
        assertFalse(hueco.seSuperponeCon(l));
        assertTrue(l.seSuperponeCon(pisa));
        assertTrue(l.seSuperponeCon(enL()));

        // Validación: un "moño" (bordes que se cruzan) y un polígono de dos vértices no se aceptan.
        Zona mono = new Zona();
        mono.setNombre("Moño");
        mono.setVertices(List.of(new Vertice(-37.9, -71.2), new Vertice(-37.8, -71.0), new Vertice(-37.9, -71.0), new Vertice(-37.8, -71.2)));
        assertEquals(List.of("Los bordes del polígono no pueden cruzarse entre sí."), mono.validar());
        Zona linea = new Zona();
        linea.setNombre("Línea");
        linea.setVertices(List.of(new Vertice(-37.9, -71.2), new Vertice(-37.8, -71.0)));
        assertFalse(linea.validar().isEmpty());
    }
}
