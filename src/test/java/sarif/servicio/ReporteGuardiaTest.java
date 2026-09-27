package sarif.servicio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sarif.modelo.EventoGuardia;
import sarif.modelo.ResumenGuardia;
import sarif.modelo.TipoEvento;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba unitaria del reporte de la guardia (PU-32, CU13): el resumen de la cronología con el tiempo de
 * aviso, el formato de las duraciones y el HTML exportado, sin base de datos.
 */
class ReporteGuardiaTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 1, 20, 18, 0);

    private static EventoGuardia evento(TipoEvento tipo, int minutosDespues, Long desdeAlerta, String detalle) {
        return new EventoGuardia(T.plusMinutes(minutosDespues), "Caviahue - Copahue", tipo, detalle, "ATENCION",
                "Marcela Ruiz", 1, desdeAlerta);
    }

    @Test
    @DisplayName("PU-32 Reporte de la guardia: resumen con tiempo de aviso, duraciones y HTML con los hitos escapados")
    void reporteGuardia() {
        List<EventoGuardia> eventos = List.of(
                evento(TipoEvento.ALERTA_GENERADA, 0, null, "#1 Activo en riesgo: foco a 2,72 km"),
                evento(TipoEvento.ALERTA_GENERADA, 5, null, "#2 Foco en zona"),
                evento(TipoEvento.ALERTA_NOTIFICADA, 12, 12L, "#1 avisada a Defensa Civil <Caviahue> & bomberos"),
                evento(TipoEvento.ALERTA_NOTIFICADA, 95, 90L, "#2 avisada a brigada"),
                evento(TipoEvento.CAMBIO_NIVEL, 100, null, "Pasa a ALERTA"),
                evento(TipoEvento.ASIGNACION, 110, null, "Brigada Caviahue"),
                evento(TipoEvento.ALERTA_CERRADA, 300, 300L, "#1 resuelta"));
        // Resumen: 2 alertas, 2 notificadas en 12 y 90 minutos (promedio 51, máximo 90), 1 cerrada, 0 descartadas.
        ResumenGuardia r = ServicioReportes.resumir(eventos);
        assertEquals(2, r.alertas());
        assertEquals(2, r.notificadas());
        assertEquals(1, r.cerradas());
        assertEquals(0, r.descartadas());
        assertEquals(1, r.cambiosNivel());
        assertEquals(1, r.asignaciones());
        assertEquals(51.0, r.minutosPromedioAviso(), 1e-9);
        assertEquals(90L, r.minutosMaximoAviso());
        assertEquals("promedio 51 min, máximo 1 h 30 min", ReporteHtml.tiempoAviso(r));
        // Sin notificaciones no hay tiempo de aviso.
        ResumenGuardia vacio = ServicioReportes.resumir(List.of());
        assertNull(vacio.minutosPromedioAviso());
        assertEquals("sin alertas notificadas en el período", ReporteHtml.tiempoAviso(vacio));

        // Duraciones: minutos sueltos, horas con minutos en dos cifras y guion si no corresponde.
        assertEquals("0 min", ReporteHtml.duracion(0L));
        assertEquals("59 min", ReporteHtml.duracion(59L));
        assertEquals("2 h 05 min", ReporteHtml.duracion(125L));
        assertEquals("—", ReporteHtml.duracion(null));

        // HTML: título, período, cada hito y los textos del operador escapados (no se cuela una etiqueta).
        String html = ReporteHtml.armar(new ReporteHtml.Datos(LocalDate.of(2026, 1, 20), LocalDate.of(2026, 1, 21),
                "Todas las zonas", "Marcela Ruiz", eventos, r, List.of(), List.of()), T);
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("Período: 20/01/2026 al 21/01/2026"));
        assertTrue(html.contains("20/01/2026 18:12"));
        assertTrue(html.contains("Defensa Civil &lt;Caviahue&gt; &amp; bomberos"));
        assertFalse(html.contains("<Caviahue>"));
        assertTrue(html.contains("5 h 00 min"));
        // Sin hitos, lo dice en vez de dejar una tabla vacía.
        String sinHitos = ReporteHtml.armar(new ReporteHtml.Datos(LocalDate.of(2026, 1, 20), LocalDate.of(2026, 1, 21),
                "Todas las zonas", "Marcela Ruiz", List.of(), vacio, List.of(), List.of()), T);
        assertTrue(sinHitos.contains("No hay hitos registrados en el período."));
    }
}
