package sarif.fuentes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sarif.modelo.RegistroMeteo;
import sarif.modelo.Zona;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba unitaria del lector de los CSV meteorológicos de Open-Meteo (PU-13).
 *
 * <p>Verifico que el lector tome las cuatro variables que usa el índice, que marque como pronóstico
 * solo los días posteriores a la fecha de referencia, que funcione igual con saltos de línea de Windows
 * y que, cuando el archivo trae varias ubicaciones, se quede únicamente con la que cae dentro de la zona.
 * También verifico la humedad del suelo, que es opcional (RC06).
 * Le paso el CSV como texto y armo la zona en memoria, así que la prueba no necesita red, archivos ni
 * base de datos.</p>
 */
class LectorCsvOpenMeteoTest {

    /**
     * CSV de una sola ubicación, tal como lo devuelve Open-Meteo: un bloque con los datos del punto, una
     * línea en blanco y el bloque de datos diarios. Los días son el 19, el 20 y el 21 de enero de 2026.
     */
    private static final String UNA_UBICACION = """
            latitude,longitude,elevation,utc_offset_seconds,timezone,timezone_abbreviation
            -37.85,-71.075,1650.0,-10800,America/Argentina/Buenos_Aires,-03

            time,temperature_2m_max (°C),relative_humidity_2m_min (%),wind_speed_10m_max (km/h),precipitation_sum (mm)
            2026-01-19,30.2,17,41.0,0.0
            2026-01-20,31.5,15,46.5,0.0
            2026-01-21,29.8,19,35.5,2.4
            """;

    @Test
    @DisplayName("PU-13 Lectura del CSV meteorológico y distinción entre observado y pronóstico")
    void lecturaYTipo() throws FuenteNoDisponibleException {
        // Uso la zona de Caviahue con los mismos límites que en sql/02_datos.sql y el 20/01/2026
        // como fecha de referencia (la de la jornada de prueba).
        Zona caviahue = new Zona(4, "Caviahue - Copahue", null, -37.95, -37.75, -71.20, -70.95, true);
        LocalDate referencia = LocalDate.of(2026, 1, 20);
        List<RegistroMeteo> registros = LectorCsvOpenMeteo.leer(UNA_UBICACION, caviahue, referencia);

        // Leo los tres días y reviso que el registro del 20 quede asociado a la zona y con las
        // variables que después usa la componente meteorológica.
        assertEquals(3, registros.size());
        RegistroMeteo delDia = registros.get(1);
        assertEquals(4, delDia.idZona());
        assertEquals(31.5, delDia.temperaturaMaxC(), 1e-9);
        assertEquals(15, delDia.humedadMinPct(), 1e-9);
        assertEquals(46.5, delDia.vientoMaxKmh(), 1e-9);
        // El 19 y el 20 son datos observados; solo el 21, posterior a la referencia, es pronóstico.
        // También verifico que la precipitación del pronóstico se lea bien.
        assertFalse(registros.get(0).esPronostico());
        assertFalse(delDia.esPronostico());
        assertTrue(registros.get(2).esPronostico());
        assertEquals(2.4, registros.get(2).precipitacionMm(), 1e-9);

        // El mismo archivo guardado en Windows (saltos de línea \r\n) tiene que leerse igual.
        assertEquals(3, LectorCsvOpenMeteo.leer(UNA_UBICACION.replace("\n", "\r\n"), caviahue, referencia).size());

        // Archivo con varias ubicaciones: se toman solo las filas de la ubicación que cae en la zona.
        // La ubicación 0 está en San Martín de los Andes y la 1 en Caviahue, así que tiene que quedar
        // un solo registro, el de la ubicación 1.
        String varias = """
                location_id,latitude,longitude,elevation,utc_offset_seconds,timezone,timezone_abbreviation
                0,-40.11,-71.375,640.0,-10800,America/Argentina/Buenos_Aires,-03
                1,-37.85,-71.075,1650.0,-10800,America/Argentina/Buenos_Aires,-03

                location_id,time,temperature_2m_max (°C),relative_humidity_2m_min (%),wind_speed_10m_max (km/h),precipitation_sum (mm)
                0,2026-01-20,25.0,18,33.0,0.0
                1,2026-01-20,31.5,15,46.5,0.0
                """;
        List<RegistroMeteo> deLaZona = LectorCsvOpenMeteo.leer(varias, caviahue, referencia);
        assertEquals(1, deLaZona.size());
        assertEquals(31.5, deLaZona.get(0).temperaturaMaxC(), 1e-9);

        // Sin columna de humedad del suelo (como el archivo de la jornada de prueba) queda en null.
        assertNull(delDia.humedadSueloM3m3());
        // Con la columna que agregué al pedido (RC06) la leo; una celda vacía también queda en null.
        String conSuelo = """
                latitude,longitude,elevation,utc_offset_seconds,timezone,timezone_abbreviation
                -37.85,-71.075,1650.0,-10800,America/Argentina/Buenos_Aires,-03

                time,temperature_2m_max (°C),relative_humidity_2m_min (%),wind_speed_10m_max (km/h),precipitation_sum (mm),soil_moisture_0_to_10cm_mean (m³/m³)
                2026-01-20,31.5,15,46.5,0.0,0.142
                2026-01-21,29.8,19,35.5,2.4,
                """;
        List<RegistroMeteo> suelo = LectorCsvOpenMeteo.leer(conSuelo, caviahue, referencia);
        assertEquals(0.142, suelo.get(0).humedadSueloM3m3(), 1e-9);
        assertNull(suelo.get(1).humedadSueloM3m3());
        // Sin las columnas del vector del viento y del pronóstico extendido, también quedan en null.
        assertNull(delDia.direccionVientoGrados());
        assertNull(delDia.rafagaMaxKmh());
    }

    @Test
    @DisplayName("PU-36 Vector del viento y pronóstico extendido: ráfaga, dirección, mínima, prob. de lluvia y evapotranspiración")
    void vectorDelVientoYPronostico() throws FuenteNoDisponibleException {
        Zona caviahue = new Zona(4, "Caviahue - Copahue", null, -37.95, -37.75, -71.20, -70.95, true);
        // Columnas en el orden en que las devuelve Open-Meteo con el pedido actual; la dirección 359,6 se
        // redondea a 0 (norte) y no a 360. En el segundo día faltan la ráfaga y la probabilidad de lluvia.
        String csv = """
                latitude,longitude,elevation,utc_offset_seconds,timezone,timezone_abbreviation
                -37.85,-71.075,1650.0,-10800,America/Argentina/Buenos_Aires,-03

                time,temperature_2m_max (°C),relative_humidity_2m_min (%),wind_speed_10m_max (km/h),precipitation_sum (mm),soil_moisture_0_to_10cm_mean (m³/m³),temperature_2m_min (°C),wind_gusts_10m_max (km/h),wind_direction_10m_dominant (°),precipitation_probability_max (%),et0_fao_evapotranspiration (mm)
                2026-01-20,31.5,15,46.5,0.0,0.077,12.6,73.1,292,1,7.6
                2026-01-21,29.8,19,35.5,0.0,0.080,12.0,,359.6,,7.0
                """;
        List<RegistroMeteo> r = LectorCsvOpenMeteo.leer(csv, caviahue, LocalDate.of(2026, 1, 20));
        assertEquals(2, r.size());
        assertEquals(12.6, r.get(0).temperaturaMinC(), 1e-9);
        assertEquals(73.1, r.get(0).rafagaMaxKmh(), 1e-9);
        assertEquals(292, r.get(0).direccionVientoGrados());
        assertEquals(1, r.get(0).probPrecipitacionPct(), 1e-9);
        assertEquals(7.6, r.get(0).evapotranspiracionMm(), 1e-9);
        assertEquals(7.7, r.get(0).humedadSueloPct(), 1e-9);
        assertTrue(r.get(0).cumpleRegla30());
        assertFalse(r.get(1).cumpleRegla30());
        assertEquals(0, r.get(1).direccionVientoGrados());
        assertNull(r.get(1).rafagaMaxKmh());
        assertNull(r.get(1).probPrecipitacionPct());
        assertTrue(r.get(1).esPronostico());
    }

    @Test
    @DisplayName("PU-25 Open-Meteo: API de pronóstico para fechas recientes y API histórica para más de 80 días atrás")
    void eleccionApiMeteo() {
        LocalDate hoy = LocalDate.of(2026, 9, 27);
        // La jornada de prueba (20/01/2026) ya no la acepta la API de pronóstico: va a la histórica.
        String jornada = FuenteRemota.urlMeteo(-37.85, -71.075, LocalDate.of(2026, 1, 20), hoy);
        assertTrue(jornada.startsWith("https://historical-forecast-api.open-meteo.com/v1/forecast?"));
        assertTrue(jornada.contains("start_date=2026-01-19&end_date=2026-01-21"));
        // Hoy y una fecha de hace un mes siguen en la API de pronóstico.
        assertTrue(FuenteRemota.urlMeteo(-37.85, -71.075, hoy, hoy).startsWith("https://api.open-meteo.com/"));
        assertTrue(FuenteRemota.urlMeteo(-37.85, -71.075, hoy.minusDays(30), hoy).startsWith("https://api.open-meteo.com/"));
        // Borde: el día anterior a la referencia exactamente 80 días atrás todavía usa la de pronóstico.
        assertTrue(FuenteRemota.urlMeteo(-37.85, -71.075, hoy.minusDays(79), hoy).startsWith("https://api.open-meteo.com/"));
        assertTrue(FuenteRemota.urlMeteo(-37.85, -71.075, hoy.minusDays(80), hoy).startsWith("https://historical-forecast-api"));
        // Los decimales van con punto aunque la computadora esté configurada en español.
        assertTrue(jornada.contains("latitude=-37.8500&longitude=-71.0750"));
        // Con la fecha de hoy (o ayer) se pide el pronóstico extendido: hasta hoy + 15, 16 días en total.
        assertTrue(FuenteRemota.urlMeteo(-37.85, -71.075, hoy, hoy).contains("start_date=2026-09-26&end_date=2026-10-12"));
        assertTrue(FuenteRemota.urlMeteo(-37.85, -71.075, hoy.minusDays(1), hoy).contains("end_date=2026-10-12"));
        // Para una jornada pasada, solo hasta el día siguiente: no tiene sentido un "pronóstico" de días que ya pasaron.
        assertTrue(FuenteRemota.urlMeteo(-37.85, -71.075, hoy.minusDays(10), hoy).contains("start_date=2026-09-16&end_date=2026-09-18"));
        // Y siempre se piden las variables del vector del viento.
        assertTrue(jornada.contains("wind_direction_10m_dominant") && jornada.contains("wind_gusts_10m_max"));
    }
}
