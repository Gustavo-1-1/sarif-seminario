package sarif.fuentes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sarif.modelo.Confianza;
import sarif.modelo.FocoCalor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas unitarias del lector de los CSV de focos de NASA FIRMS (PU-10 a PU-12 y PU-16) y de la
 * elección del producto FIRMS según la fecha (PU-17) y la prueba de conexión (PU-21).
 *
 * <p>Acá verifico que el lector entienda los dos formatos que devuelve FIRMS (VIIRS y MODIS), que
 * normalice la confianza de cada instrumento a mi escala BAJA / NOMINAL / ALTA y que rechace las
 * respuestas que no son un CSV válido. Son pruebas unitarias puras: le paso el texto del CSV
 * directamente como cadena, así que no necesitan conexión a internet, ni archivos, ni la base de datos.</p>
 */
class LectorCsvFirmsTest {

    /** Dos filas con el formato VIIRS real: confianza con letras y hora sin ceros a la izquierda (436). */
    private static final String VIIRS = """
            latitude,longitude,bright_ti4,scan,track,acq_date,acq_time,satellite,instrument,confidence,version,bright_ti5,frp,daynight
            -37.84550,-71.05500,352.18,0.39,0.36,2026-01-20,436,N,VIIRS,h,2.0NRT,296.05,18.70,N
            -40.07150,-71.33000,341.77,0.45,0.39,2026-01-20,1748,N20,VIIRS,n,2.0NRT,299.12,12.30,D
            """;

    /** Dos filas MODIS: no traen columna instrument y la confianza es un porcentaje (85 y 25). */
    private static final String MODIS = """
            latitude,longitude,brightness,scan,track,acq_date,acq_time,satellite,confidence,version,bright_t31,frp,daynight
            -38.1,-70.5,320.1,1.0,1.0,2026-01-20,1405,Terra,85,6.1NRT,295.0,20.5,D
            -38.2,-70.6,310.4,1.0,1.0,2026-01-20,15,Aqua,25,6.1NRT,290.0,5.0,N
            """;

    @Test
    @DisplayName("PU-10 Normalización de la confianza VIIRS (l/n/h) y MODIS (porcentaje)")
    void normalizacionConfianza() {
        // VIIRS: cada letra corresponde a un nivel de mi escala.
        assertEquals(Confianza.BAJA, Confianza.desdeViirs("l"));
        assertEquals(Confianza.NOMINAL, Confianza.desdeViirs("n"));
        assertEquals(Confianza.ALTA, Confianza.desdeViirs("h"));
        // MODIS: pruebo justo los bordes de los cortes (menos de 30 es BAJA, de 30 a 79 NOMINAL
        // y desde 80 ALTA), que es donde es más fácil equivocarse con un < o un <=.
        assertEquals(Confianza.BAJA, Confianza.desdeModis(29));
        assertEquals(Confianza.NOMINAL, Confianza.desdeModis(30));
        assertEquals(Confianza.NOMINAL, Confianza.desdeModis(79));
        assertEquals(Confianza.ALTA, Confianza.desdeModis(80));
    }

    @Test
    @DisplayName("PU-11 Lectura del CSV satelital, incluida la hora sin cero inicial (436 -> 04:36)")
    void lecturaCsv() throws FuenteNoDisponibleException {
        // Formato VIIRS: leo las dos filas y reviso en detalle la primera, que es el foco de alta
        // confianza de Caviahue de la jornada de prueba. Lo más delicado es la hora: FIRMS manda
        // 436 y yo tengo que interpretarlo como 04:36 UTC.
        List<FocoCalor> focos = LectorCsvFirms.leer(VIIRS);
        assertEquals(2, focos.size());
        FocoCalor primero = focos.get(0);
        assertEquals(LocalDateTime.of(2026, 1, 20, 4, 36), primero.fechaHoraUtc());
        assertEquals(-37.8455, primero.latitud(), 1e-9);
        assertEquals("VIIRS", primero.instrumento());
        assertEquals(Confianza.ALTA, primero.confianza());
        assertEquals(18.70, primero.potenciaFrpMw(), 1e-9);

        // Formato MODIS: como no hay columna instrument, el lector tiene que deducirlo por las
        // columnas del encabezado. También verifico la hora más corta posible (15 -> 00:15) y la
        // conversión del porcentaje de confianza.
        List<FocoCalor> modis = LectorCsvFirms.leer(MODIS);
        assertEquals("MODIS", modis.get(0).instrumento());
        assertEquals(Confianza.ALTA, modis.get(0).confianza());
        assertEquals(LocalDateTime.of(2026, 1, 20, 0, 15), modis.get(1).fechaHoraUtc());
        assertEquals(Confianza.BAJA, modis.get(1).confianza());
    }

    @Test
    @DisplayName("PU-12 Respuesta satelital con formato inesperado")
    void formatoInesperado() {
        // Tres respuestas que no se pueden procesar: el mensaje de error que devuelve FIRMS cuando la
        // clave es inválida, una respuesta vacía y un CSV con la fecha en otro formato. En todos los
        // casos espero FuenteNoDisponibleException, para que la sincronización quede registrada como
        // fallida en lugar de guardar datos incorrectos.
        assertThrows(FuenteNoDisponibleException.class, () -> LectorCsvFirms.leer("Invalid MAP_KEY."));
        assertThrows(FuenteNoDisponibleException.class, () -> LectorCsvFirms.leer(""));
        assertThrows(FuenteNoDisponibleException.class, () -> LectorCsvFirms.leer(
                "latitude,longitude,acq_date,acq_time,satellite,confidence,instrument\n-38,-71,20-01-2026,436,N,n,VIIRS"));
    }

    @Test
    @DisplayName("PU-16 El archivo histórico (_SP) descarta volcanes y fuentes fijas (type distinto de 0)")
    void descartaVolcanes() throws FuenteNoDisponibleException {
        // El producto _SP agrega la columna type. La segunda fila simula el volcán Copahue (type 1) y la
        // tercera una fuente fija en tierra (type 2): ninguna es un incendio, así que no las tengo que leer.
        // La primera es vegetación (type 0) y sí queda.
        String sp = """
                latitude,longitude,bright_ti4,scan,track,acq_date,acq_time,satellite,instrument,confidence,version,bright_ti5,frp,daynight,type
                -37.84550,-71.05500,352.18,0.39,0.36,2026-01-20,436,N,VIIRS,h,2,296.05,18.70,N,0
                -37.85600,-71.18300,340.00,0.39,0.36,2026-01-20,436,N,VIIRS,n,2,290.00,9.10,N,1
                -38.95000,-68.06000,335.00,0.39,0.36,2026-01-20,436,N,VIIRS,n,2,290.00,4.20,N,2
                """;
        List<FocoCalor> focos = LectorCsvFirms.leer(sp);
        assertEquals(1, focos.size());
        assertEquals(-71.055, focos.get(0).longitud(), 1e-9);
        // Sin columna type (producto _NRT) no descarto nada: el CSV VIIRS de arriba sigue dando 2 focos.
        assertEquals(2, LectorCsvFirms.leer(VIIRS).size());
    }

    @Test
    @DisplayName("PU-17 Elección del producto FIRMS: _SP para fechas anteriores a la cobertura del _NRT")
    void eleccionProducto() {
        // Tabla de disponibilidad con el formato que devuelve FIRMS (data_id,min_date,max_date).
        String disponibilidad = """
                data_id,min_date,max_date
                MODIS_NRT,2026-07-01,2026-09-26
                VIIRS_SNPP_NRT,2026-07-01,2026-09-26
                VIIRS_SNPP_SP,2012-01-20,2026-06-30
                """;
        // La jornada de prueba (20/01/2026) ya no la cubre el _NRT: tengo que pedir el archivo histórico.
        assertEquals("VIIRS_SNPP_SP", FuenteRemota.elegirProducto(disponibilidad, "VIIRS_SNPP_NRT", LocalDate.of(2026, 1, 20)));
        // Una fecha reciente sigue usando el _NRT, igual que el primer día de cobertura (borde).
        assertEquals("VIIRS_SNPP_NRT", FuenteRemota.elegirProducto(disponibilidad, "VIIRS_SNPP_NRT", LocalDate.of(2026, 9, 25)));
        assertEquals("VIIRS_SNPP_NRT", FuenteRemota.elegirProducto(disponibilidad, "VIIRS_SNPP_NRT", LocalDate.of(2026, 7, 1)));
        // Si no existe el _SP del mismo sensor (MODIS en esta tabla), me quedo con el producto configurado.
        assertEquals("MODIS_NRT", FuenteRemota.elegirProducto(disponibilidad, "MODIS_NRT", LocalDate.of(2026, 1, 20)));
    }

    @Test
    @DisplayName("PU-21 Prueba de conexión con FIRMS: clave válida informa la cobertura; clave rechazada o producto ausente fallan")
    void pruebaConexionFirms() throws FuenteNoDisponibleException {
        String disponibilidad = """
                data_id,min_date,max_date
                MODIS_NRT,2026-07-01,2026-09-26
                VIIRS_SNPP_NRT,2026-07-01,2026-09-26
                VIIRS_SNPP_SP,2012-01-20,2026-06-30
                """;
        // Con la tabla, el mensaje dice qué período cubre el producto y que para lo anterior está el _SP.
        String mensaje = FuenteRemota.describirDisponibilidad(disponibilidad, "VIIRS_SNPP_NRT");
        assertTrue(mensaje.startsWith("Clave válida."));
        assertTrue(mensaje.contains("2026-07-01 al 2026-09-26"));
        assertTrue(mensaje.contains("2012-01-20 al 2026-06-30"));
        // Lo que FIRMS devuelve con una clave inválida no es la tabla: tiene que informarse como falla.
        assertThrows(FuenteNoDisponibleException.class, () -> FuenteRemota.describirDisponibilidad("Invalid MAP_KEY.", "VIIRS_SNPP_NRT"));
        // Un producto que no está en la tabla también se informa, para que el operador corrija la configuración.
        assertThrows(FuenteNoDisponibleException.class, () -> FuenteRemota.describirDisponibilidad(disponibilidad, "VIIRS_NOAA21_NRT"));
    }
}
