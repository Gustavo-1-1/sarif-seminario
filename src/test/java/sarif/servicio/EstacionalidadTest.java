package sarif.servicio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sarif.modelo.FilaMes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba unitaria de la estacionalidad de focos (PU-40). Uso un arreglo con la forma del histórico de Neuquén:
 * los focos se concentran entre noviembre y marzo, así que la temporada crítica cruza el fin de año y tiene
 * que encontrarse con la ventana circular. No uso la base: la clase recibe el arreglo ya leído.
 */
class EstacionalidadTest {

    //                                      ene feb mar abr may jun jul ago sep oct nov dic
    private static final int[] HISTORICO = {89, 61, 71, 0, 0, 0, 0, 0, 0, 0, 68, 71};

    @Test
    @DisplayName("PU-40 Estacionalidad: temporada crítica circular, filas marcadas, promedio anual y datos inválidos")
    void calculaLaTemporadaCritica() {
        Estacionalidad e = new Estacionalidad(HISTORICO, 6);
        assertEquals(360, e.total());
        // Noviembre + diciembre + enero = 228 focos, más que diciembre a febrero (221) y enero a marzo (221).
        assertEquals(11, e.inicioTemporadaCritica(3));
        assertEquals(228, e.focosEnVentana(11, 3));
        // La ventana que arranca en diciembre sigue en enero y febrero: el índice vuelve al principio del arreglo.
        assertEquals(71 + 89 + 61, e.focosEnVentana(12, 3));

        List<FilaMes> filas = e.filas(3);
        assertEquals(12, filas.size());
        assertTrue(filas.get(10).temporadaCritica());   // noviembre
        assertTrue(filas.get(11).temporadaCritica());   // diciembre
        assertTrue(filas.get(0).temporadaCritica());    // enero
        assertFalse(filas.get(1).temporadaCritica());   // febrero queda afuera
        assertEquals("Enero", filas.get(0).mes());
        assertEquals(14.8, filas.get(0).promedioAnual(), 1e-9);   // 89 focos en 6 años
        assertEquals(24.7, filas.get(0).porcentaje(), 1e-9);      // 89 de 360
        assertTrue(e.resumen(3).startsWith("Temporada crítica: Noviembre a enero concentra 228 de 360 focos"), e.resumen(3));

        // Copia defensiva: modificar el arreglo original después no cambia el objeto.
        int[] original = HISTORICO.clone();
        Estacionalidad copia = new Estacionalidad(original, 6);
        original[0] = 0;
        assertEquals(89, copia.focos(1));

        // Sin focos no hay temporada crítica que marcar.
        Estacionalidad vacia = new Estacionalidad(new int[12], 0);
        assertTrue(vacia.filas(3).stream().noneMatch(FilaMes::temporadaCritica));
        assertTrue(vacia.resumen(3).startsWith("No hay focos registrados"));

        // Datos inválidos: un arreglo que no tiene 12 meses, un mes negativo o una temporada fuera de rango.
        assertThrows(IllegalArgumentException.class, () -> new Estacionalidad(new int[11], 6));
        assertThrows(IllegalArgumentException.class, () -> new Estacionalidad(new int[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1}, 6));
        assertThrows(IllegalArgumentException.class, () -> e.focosEnVentana(13, 3));
        assertThrows(IllegalArgumentException.class, () -> e.inicioTemporadaCritica(0));
    }
}
