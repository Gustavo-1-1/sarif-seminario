package sarif.servicio;

import sarif.datos.ConexionBD;
import sarif.datos.ReporteDAO;
import sarif.modelo.EventoGuardia;
import sarif.modelo.FilaDesempeno;
import sarif.modelo.FilaReporteZona;
import sarif.modelo.ResumenGuardia;
import sarif.modelo.TipoEvento;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LongSummaryStatistics;

/**
 * CU13 Generar reportes: la cronología de la guardia (cada alerta, notificación, cierre, cambio de nivel y
 * asignación de recursos, con fecha, hora y responsable), el histórico de focos e índices por zona y
 * período (RFS18) y el de desempeño, que compara el riesgo previsto con los focos que ocurrieron (RFS19).
 * El reporte completo se puede exportar a un archivo HTML para imprimirlo o adjuntarlo.
 */
public class ServicioReportes {

    public List<FilaReporteZona> historicoPorZona(LocalDate desde, LocalDate hasta) throws SQLException, ValidacionException {
        validarPeriodo(desde, hasta);
        try (Connection cn = ConexionBD.obtener()) {
            return new ReporteDAO(cn).historicoPorZona(desde, hasta);
        }
    }

    public List<FilaDesempeno> desempeno(LocalDate desde, LocalDate hasta) throws SQLException, ValidacionException {
        validarPeriodo(desde, hasta);
        try (Connection cn = ConexionBD.obtener()) {
            return new ReporteDAO(cn).desempeno(desde, hasta);
        }
    }

    /**
     * Hitos de la guardia en el período, en orden de fecha y hora.
     *
     * @param idZona 0 para todas las zonas
     */
    public List<EventoGuardia> cronologia(LocalDate desde, LocalDate hasta, int idZona) throws SQLException, ValidacionException {
        validarPeriodo(desde, hasta);
        try (Connection cn = ConexionBD.obtener()) {
            return new ReporteDAO(cn).cronologia(desde, hasta, idZona);
        }
    }

    /**
     * Cuento los hitos por tipo y calculo el tiempo de aviso: los minutos entre que el sistema generó cada
     * alerta y que alguien la notificó. Lo hago en memoria sobre la cronología ya leída; es estático para
     * probarlo con JUnit sin base.
     */
    public static ResumenGuardia resumir(List<EventoGuardia> eventos) {
        LongSummaryStatistics aviso = eventos.stream()
                .filter(e -> e.tipo() == TipoEvento.ALERTA_NOTIFICADA && e.minutosDesdeAlerta() != null)
                .mapToLong(EventoGuardia::minutosDesdeAlerta)
                .summaryStatistics();
        return new ResumenGuardia(contar(eventos, TipoEvento.ALERTA_GENERADA), contar(eventos, TipoEvento.ALERTA_NOTIFICADA),
                contar(eventos, TipoEvento.ALERTA_CERRADA), contar(eventos, TipoEvento.ALERTA_DESCARTADA),
                contar(eventos, TipoEvento.CAMBIO_NIVEL), contar(eventos, TipoEvento.ASIGNACION),
                aviso.getCount() == 0 ? null : aviso.getAverage(),
                aviso.getCount() == 0 ? null : aviso.getMax());
    }

    private static int contar(List<EventoGuardia> eventos, TipoEvento tipo) {
        return (int) eventos.stream().filter(e -> e.tipo() == tipo).count();
    }

    /**
     * Guardo el reporte completo en un archivo HTML (UTF-8), que se abre con cualquier navegador y se puede
     * imprimir o guardar como PDF desde ahí. No uso bibliotecas de terceros: el HTML lo arma ReporteHtml.
     */
    public void exportarHtml(Path destino, ReporteHtml.Datos datos) throws IOException {
        Files.writeString(destino, ReporteHtml.armar(datos, LocalDateTime.now()), StandardCharsets.UTF_8);
    }

    /**
     * Inicio de la temporada de incendios que contiene la fecha. Tomo la temporada de julio a junio,
     * igual que en la consulta C-3: el 20/01/2026 pertenece a la temporada que empezó el 01/07/2025.
     */
    public static LocalDate inicioTemporada(LocalDate fecha) {
        int anio = fecha.getMonthValue() >= 7 ? fecha.getYear() : fecha.getYear() - 1;
        return LocalDate.of(anio, 7, 1);
    }

    private static void validarPeriodo(LocalDate desde, LocalDate hasta) throws ValidacionException {
        if (desde == null || hasta == null) {
            throw new ValidacionException("Debe indicar las fechas desde y hasta del período.");
        }
        if (desde.isAfter(hasta)) {
            throw new ValidacionException("La fecha desde no puede ser posterior a la fecha hasta.");
        }
    }
}
