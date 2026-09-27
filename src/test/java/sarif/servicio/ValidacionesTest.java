package sarif.servicio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sarif.modelo.ActivoProtegido;
import sarif.modelo.FilaDesempeno;
import sarif.modelo.MedioNotificacion;
import sarif.modelo.Recurso;
import sarif.modelo.RegistroMeteo;
import sarif.modelo.TipoMarca;
import sarif.modelo.TipoRecurso;
import sarif.modelo.Zona;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas unitarias de las reglas que agregué para cubrir lo propuesto en el primer trabajo:
 * carga manual de meteorología (PU-18, RFS12), alta de activos y recursos (PU-19, CU02 y CU03) y
 * el cálculo del reporte de desempeño y la temporada (PU-20, CU13) y la configuración de la pantalla
 * de fuentes de datos (PU-22), los datos que se registran al notificar una alerta (PU-31) y las marcas y
 * ubicaciones de recursos del mapa (PU-33).
 *
 * <p>Las validaciones son métodos estáticos que no tocan la base, así que las pruebo directo con datos
 * armados en memoria, igual que la validación de zona de PU-07.</p>
 */
class ValidacionesTest {

    private final Zona caviahue = new Zona(4, "Caviahue - Copahue", null, -37.95, -37.75, -71.20, -70.95, true);

    @Test
    @DisplayName("PU-18 Carga manual de meteorología: valores válidos pasan y los fuera de rango se informan juntos")
    void validacionMeteoManual() {
        // Un día típico de verano en Caviahue: no tiene que haber errores.
        RegistroMeteo valido = new RegistroMeteo(4, LocalDate.of(2026, 1, 20), 31.5, 18, 35, 0, false);
        assertTrue(ServicioSincronizacion.validarMeteo(valido).isEmpty());
        // Los bordes de los rangos también son válidos.
        RegistroMeteo bordes = new RegistroMeteo(4, LocalDate.of(2026, 1, 20), 50, 0, 200, 500, true);
        assertTrue(ServicioSincronizacion.validarMeteo(bordes).isEmpty());
        // Sin zona, sin fecha, temperatura de 60 °C y humedad de 120 %: espero los cuatro errores juntos,
        // para que el operador los corrija de una vez.
        RegistroMeteo invalido = new RegistroMeteo(0, null, 60, 120, 35, 0, false);
        assertEquals(4, ServicioSincronizacion.validarMeteo(invalido).size());
        // Viento y precipitación negativos.
        RegistroMeteo negativos = new RegistroMeteo(4, LocalDate.of(2026, 1, 20), 20, 40, -1, -0.5, false);
        assertEquals(2, ServicioSincronizacion.validarMeteo(negativos).size());
        // Humedad del suelo opcional: 0,25 m³/m³ es válida y 1,5 (tipeada como porcentaje mal) se rechaza.
        assertTrue(ServicioSincronizacion.validarMeteo(new RegistroMeteo(4, LocalDate.of(2026, 1, 20), 31.5, 18, 35, 0, false, 0.25)).isEmpty());
        assertEquals(1, ServicioSincronizacion.validarMeteo(new RegistroMeteo(4, LocalDate.of(2026, 1, 20), 31.5, 18, 35, 0, false, 1.5)).size());
    }

    @Test
    @DisplayName("PU-19 Alta de activo (ubicación dentro de la zona, distancia 0-50 km) y de recurso (dotación 1-500)")
    void validacionActivoYRecurso() {
        // Activo: la villa de Caviahue está dentro de la zona y con 5 km de distancia de alerta.
        ActivoProtegido villa = new ActivoProtegido(0, 4, "Villa Caviahue", "POBLACION", -37.87, -71.05, 5);
        assertTrue(ServicioZonas.validarActivo(villa, caviahue).isEmpty());
        // Fuera de la zona, tipo inexistente y distancia 0: tres errores.
        ActivoProtegido malo = new ActivoProtegido(0, 4, "Puesto", "OTRO", -38.50, -71.05, 0);
        assertEquals(3, ServicioZonas.validarActivo(malo, caviahue).size());
        // Sin zona elegida y sin nombre: no intento ver si el punto está adentro, solo informo los dos errores.
        ActivoProtegido sinDatos = new ActivoProtegido(0, 0, " ", "POBLACION", -37.87, -71.05, 5);
        assertEquals(2, ServicioZonas.validarActivo(sinDatos, null).size());

        // Recurso: una brigada terrestre de 12 personas es válida.
        assertTrue(ServicioRecursos.validarRecurso(new Recurso(0, "Brigada Caviahue", TipoRecurso.TERRESTRE, 12, null, 4)).isEmpty());
        // Sin denominación, sin tipo y dotación 0: tres errores; dotación 501 también se rechaza.
        List<String> errores = ServicioRecursos.validarRecurso(new Recurso(0, "", null, 0, null, null));
        assertEquals(3, errores.size());
        assertEquals(1, ServicioRecursos.validarRecurso(new Recurso(0, "Avión hidrante", TipoRecurso.AEREO, 501, null, null)).size());
    }

    @Test
    @DisplayName("PU-20 Reportes: porcentaje de días con focos por nivel e inicio de la temporada (julio a junio)")
    void reportes() {
        // 3 de 8 días-zona EXTREMO con focos: 37,5 %. Sin días con ese nivel, el porcentaje queda vacío.
        assertEquals(37.5, new FilaDesempeno("EXTREMO", 8, 3, 5).porcentajeConFocos(), 1e-9);
        assertEquals(0.0, new FilaDesempeno("BAJO", 20, 0, 0).porcentajeConFocos(), 1e-9);
        assertNull(new FilaDesempeno("ALTO", 0, 0, 0).porcentajeConFocos());
        // La jornada de prueba pertenece a la temporada 2025-2026; julio ya abre la siguiente.
        assertEquals(LocalDate.of(2025, 7, 1), ServicioReportes.inicioTemporada(LocalDate.of(2026, 1, 20)));
        assertEquals(LocalDate.of(2025, 7, 1), ServicioReportes.inicioTemporada(LocalDate.of(2026, 6, 30)));
        assertEquals(LocalDate.of(2026, 7, 1), ServicioReportes.inicioTemporada(LocalDate.of(2026, 7, 1)));
    }

    @Test
    @DisplayName("PU-22 Configuración de fuentes: producto de la lista, intervalo 15-1440 min, días 1-10; la clave se enmascara")
    void configuracionFuentes() {
        assertTrue(ServicioFuentes.validarConfiguracion("VIIRS_SNPP_NRT", 180, 2).isEmpty());
        // Bordes válidos.
        assertTrue(ServicioFuentes.validarConfiguracion("MODIS_NRT", 15, 10).isEmpty());
        assertTrue(ServicioFuentes.validarConfiguracion("VIIRS_NOAA20_NRT", 1440, 1).isEmpty());
        // Producto inventado, 10 minutos y 0 días: los tres errores juntos.
        assertEquals(3, ServicioFuentes.validarConfiguracion("GOES_NRT", 10, 0).size());
        assertEquals(2, ServicioFuentes.validarConfiguracion(null, 1441, 5).size());
        // En pantalla solo se ven los últimos 4 caracteres de la clave; una clave muy corta no se muestra.
        assertEquals("••••0a0b", ServicioFuentes.enmascarar("abcd1234ef560a0b"));
        assertEquals("••••", ServicioFuentes.enmascarar("abc"));
    }

    @Test
    @DisplayName("PU-31 Notificación de alerta: a quién se avisó (obligatorio, hasta 120) y medio obligatorio")
    void validacionNotificacion() {
        assertTrue(ServicioAlertas.validarNotificacion("Defensa Civil de Aluminé", MedioNotificacion.TELEFONO).isEmpty());
        // Justo 120 caracteres pasa; 121 se rechaza.
        assertTrue(ServicioAlertas.validarNotificacion("x".repeat(120), MedioNotificacion.RADIO).isEmpty());
        assertEquals(1, ServicioAlertas.validarNotificacion("x".repeat(121), MedioNotificacion.RADIO).size());
        // Destinatario en blanco y sin medio: los dos errores juntos.
        assertEquals(2, ServicioAlertas.validarNotificacion("   ", null).size());
        assertEquals(1, ServicioAlertas.validarNotificacion(null, MedioNotificacion.PRESENCIAL).size());
        // El operador no puede elegir SISTEMA: ese medio lo pone el sistema al aplicar el nivel sugerido.
        assertFalse(List.of(MedioNotificacion.elegibles()).contains(MedioNotificacion.SISTEMA));
    }

    @Test
    @DisplayName("PU-33 Marcas y ubicaciones del mapa: descripción obligatoria (hasta 120), coordenadas válidas y zona del punto")
    void validacionMarcasYUbicaciones() {
        assertTrue(ServicioUbicaciones.validarMarca(TipoMarca.PUNTO_AGUA, "Tanque de la estancia", -37.86, -71.03).isEmpty());
        assertTrue(ServicioUbicaciones.validarMarca(TipoMarca.PELIGRO, "x".repeat(120), -37.86, -71.03).isEmpty());
        assertEquals(1, ServicioUbicaciones.validarMarca(TipoMarca.PELIGRO, "x".repeat(121), -37.86, -71.03).size());
        // Sin tipo, sin descripción y con una latitud imposible: los tres errores juntos.
        assertEquals(3, ServicioUbicaciones.validarMarca(null, "  ", -95, -71.03).size());
        // Las observaciones de la ubicación son optativas, pero de hasta 120 caracteres.
        assertTrue(ServicioUbicaciones.validarUbicacion(-37.84, -71.06, null).isEmpty());
        assertEquals(1, ServicioUbicaciones.validarUbicacion(-37.84, -71.06, "x".repeat(121)).size());
        assertEquals(1, ServicioUbicaciones.validarUbicacion(-37.84, 190, "").size());
        // La zona del punto: Caviahue contiene el foco de la jornada; un punto en Zapala no cae en ninguna zona.
        assertEquals(4, ServicioUbicaciones.zonaQueContiene(List.of(caviahue), -37.8455, -71.055));
        assertNull(ServicioUbicaciones.zonaQueContiene(List.of(caviahue), -38.90, -70.06));
    }
}
