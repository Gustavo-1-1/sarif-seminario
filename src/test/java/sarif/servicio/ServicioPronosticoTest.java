package sarif.servicio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sarif.modelo.DireccionViento;
import sarif.modelo.RegistroMeteo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba de la interpretación del viento y del resumen del pronóstico (PU-37): puntos cardinales, hacia dónde
 * empuja el fuego, la dirección predominante de varios días (promedio de vectores, no de grados) y el resumen
 * con la regla 30-30-30. No usa base ni red.
 */
class ServicioPronosticoTest {

    private static RegistroMeteo dia(int d, double temp, double hum, double viento, double lluvia, Integer direccion) {
        return new RegistroMeteo(4, LocalDate.of(2026, 1, d), temp, hum, viento, lluvia, d > 20, null, null,
                viento * 1.5, direccion, null, null);
    }

    @Test
    @DisplayName("PU-37 Viento: puntos cardinales, hacia dónde va el fuego, dirección predominante y resumen del pronóstico")
    void vientoYResumen() {
        assertEquals("N", DireccionViento.puntoCardinal(0));
        assertEquals("N", DireccionViento.puntoCardinal(350));
        assertEquals("O", DireccionViento.puntoCardinal(270));
        assertEquals("NO", DireccionViento.puntoCardinal(300));
        assertEquals("SO", DireccionViento.puntoCardinal(225));
        // Un viento del oeste empuja el fuego hacia el este; uno del norte, hacia el sur.
        assertEquals(90, DireccionViento.haciaDonde(270));
        assertEquals(180, DireccionViento.haciaDonde(0));
        assertEquals("O → E (270°)", DireccionViento.describir(270));

        // 350° y 10° son los dos del norte: el promedio de los grados daría 180° (sur), el de los vectores 0°.
        assertEquals(Optional.of(0), ServicioPronostico.direccionPredominante(
                List.of(dia(20, 20, 40, 20, 0, 350), dia(21, 20, 40, 20, 0, 10))));
        // El día de más viento pesa más: 30 km/h del oeste y 10 del norte dan un viento del oeste-noroeste.
        int predominante = ServicioPronostico.direccionPredominante(
                List.of(dia(20, 20, 40, 30, 0, 270), dia(21, 20, 40, 10, 0, 0))).orElseThrow();
        assertEquals(288, predominante);
        // Vientos opuestos de igual fuerza se anulan, y sin direcciones no hay predominante.
        assertTrue(ServicioPronostico.direccionPredominante(
                List.of(dia(20, 20, 40, 20, 0, 90), dia(21, 20, 40, 20, 0, 270))).isEmpty());
        assertTrue(ServicioPronostico.direccionPredominante(List.of(dia(20, 20, 40, 20, 0, null))).isEmpty());

        // Resumen desde el 20/01: el 19 queda afuera, el 20 cumple la regla 30-30-30 y el 21 no (llueve y hay humedad).
        List<RegistroMeteo> dias = List.of(dia(19, 35, 10, 60, 0, 90), dia(20, 31.5, 15, 46.5, 0, 292),
                dia(21, 25, 45, 20, 3.5, 284));
        String resumen = ServicioPronostico.resumen(dias, LocalDate.of(2026, 1, 20));
        assertTrue(resumen.startsWith("2 día(s) desde el 20/01"), resumen);
        assertTrue(resumen.contains("1 cumple(n) la regla 30-30-30 (20/01)"), resumen);
        assertTrue(resumen.contains("viento máximo 47 km/h el 20/01"), resumen);
        assertTrue(resumen.contains("ráfagas de hasta 70 km/h"), resumen);
        assertTrue(resumen.contains("predomina el viento del O, que empujaría el fuego hacia el E"), resumen);
        assertTrue(resumen.contains("lluvia acumulada 3.5 mm"), resumen);
    }
}
