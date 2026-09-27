package sarif.servicio;

import sarif.modelo.EventoGuardia;
import sarif.modelo.FilaDesempeno;
import sarif.modelo.FilaReporteZona;
import sarif.modelo.ResumenGuardia;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Arma el reporte de la guardia como una página HTML autocontenida (con su propio estilo, sin archivos
 * externos), para abrirla en el navegador, imprimirla o guardarla como PDF. Es una clase sin estado ni
 * acceso a la base, así que la pruebo con JUnit comparando el texto que genera.
 */
public final class ReporteHtml {

    /** Todo lo que va en el reporte; lo junta el controlador con lo que ya muestra en pantalla. */
    public record Datos(LocalDate desde, LocalDate hasta, String zona, String usuario, List<EventoGuardia> eventos,
                        ResumenGuardia resumen, List<FilaReporteZona> zonas, List<FilaDesempeno> desempeno) {
    }

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private ReporteHtml() {
    }

    static String armar(Datos d, LocalDateTime generado) {
        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html>\n<html lang=\"es\">\n<head>\n<meta charset=\"UTF-8\">\n")
                .append("<title>SARIF - Reporte de la guardia</title>\n<style>\n")
                .append("body{font-family:Segoe UI,Arial,sans-serif;margin:24px;color:#263238}")
                .append("h1{margin:0 0 4px}h2{margin-top:28px;border-bottom:2px solid #c62828;padding-bottom:4px}")
                .append(".dato{color:#546e7a}table{border-collapse:collapse;width:100%;font-size:13px}")
                .append("th,td{border:1px solid #cfd8dc;padding:4px 6px;text-align:left;vertical-align:top}")
                .append("th{background:#eceff1}.resumen td{border:none;padding:2px 12px 2px 0}")
                .append("@media print{body{margin:0}}\n</style>\n</head>\n<body>\n");

        h.append("<h1>SARIF - Reporte de la guardia</h1>\n<p class=\"dato\">Período: ")
                .append(d.desde().format(FECHA)).append(" al ").append(d.hasta().format(FECHA))
                .append(" · Zona: ").append(texto(d.zona()))
                .append(" · Generado por ").append(texto(d.usuario())).append(" el ").append(generado.format(FECHA_HORA))
                .append("</p>\n");

        ResumenGuardia r = d.resumen();
        h.append("<h2>Resumen</h2>\n<table class=\"resumen\">\n")
                .append(fila("Alertas generadas", r.alertas()))
                .append(fila("Alertas notificadas", r.notificadas()))
                .append(fila("Alertas cerradas", r.cerradas()))
                .append(fila("Alertas descartadas", r.descartadas()))
                .append(fila("Cambios de nivel de alerta", r.cambiosNivel()))
                .append(fila("Asignaciones de recursos", r.asignaciones()))
                .append("<tr><td>Tiempo de aviso (desde la alerta)</td><td>").append(tiempoAviso(r)).append("</td></tr>\n")
                .append("</table>\n");

        h.append("<h2>Cronología</h2>\n");
        if (d.eventos().isEmpty()) {
            h.append("<p>No hay hitos registrados en el período.</p>\n");
        } else {
            h.append("<table>\n<tr><th>Fecha y hora</th><th>Zona</th><th>Hito</th><th>Detalle</th>")
                    .append("<th>Nivel de alerta</th><th>Responsable</th><th>Desde la alerta</th></tr>\n");
            for (EventoGuardia e : d.eventos()) {
                h.append("<tr><td>").append(e.fechaHora() == null ? "" : e.fechaHora().format(FECHA_HORA))
                        .append("</td><td>").append(texto(e.zona()))
                        .append("</td><td>").append(texto(e.tipo().texto()))
                        .append("</td><td>").append(texto(e.detalle()))
                        .append("</td><td>").append(e.nivelAlerta() == null ? "—" : texto(e.nivelAlerta()))
                        .append("</td><td>").append(texto(e.usuario()))
                        .append("</td><td>").append(duracion(e.minutosDesdeAlerta()))
                        .append("</td></tr>\n");
            }
            h.append("</table>\n");
        }

        h.append("<h2>Focos e índices por zona</h2>\n<table>\n<tr><th>Zona</th><th>Focos</th><th>Confianza alta</th>")
                .append("<th>FRP prom. (MW)</th><th>Días con índice</th><th>Índice prom.</th><th>Índice máx.</th>")
                .append("<th>Días ALTO o EXTREMO</th></tr>\n");
        for (FilaReporteZona z : d.zonas()) {
            h.append("<tr><td>").append(texto(z.zona())).append("</td><td>").append(z.focos())
                    .append("</td><td>").append(z.focosAltaConfianza()).append("</td><td>").append(numero(z.frpPromedioMw()))
                    .append("</td><td>").append(z.diasConIndice()).append("</td><td>").append(numero(z.indicePromedio()))
                    .append("</td><td>").append(numero(z.indiceMaximo())).append("</td><td>").append(z.diasAltoOExtremo())
                    .append("</td></tr>\n");
        }
        h.append("</table>\n");

        h.append("<h2>Desempeño del índice</h2>\n<table>\n<tr><th>Nivel previsto</th><th>Días-zona</th>")
                .append("<th>Días con focos</th><th>Días con focos (%)</th><th>Focos</th></tr>\n");
        for (FilaDesempeno f : d.desempeno()) {
            h.append("<tr><td>").append(texto(f.nivel())).append("</td><td>").append(f.diasZona())
                    .append("</td><td>").append(f.diasConFocos()).append("</td><td>").append(numero(f.porcentajeConFocos()))
                    .append("</td><td>").append(f.focos()).append("</td></tr>\n");
        }
        h.append("</table>\n</body>\n</html>\n");
        return h.toString();
    }

    /** "12 min" o "2 h 05 min"; un guion si el hito no tiene tiempo desde la alerta. */
    public static String duracion(Long minutos) {
        if (minutos == null) {
            return "—";
        }
        if (minutos < 60) {
            return minutos + " min";
        }
        return String.format("%d h %02d min", minutos / 60, minutos % 60);
    }

    /** Promedio y máximo del tiempo de aviso, o la aclaración de que no hubo notificaciones. */
    public static String tiempoAviso(ResumenGuardia r) {
        if (r.minutosPromedioAviso() == null) {
            return "sin alertas notificadas en el período";
        }
        return "promedio " + duracion(Math.round(r.minutosPromedioAviso())) + ", máximo " + duracion(r.minutosMaximoAviso());
    }

    private static String fila(String titulo, int valor) {
        return "<tr><td>" + titulo + "</td><td>" + valor + "</td></tr>\n";
    }

    private static String numero(Double valor) {
        return valor == null ? "—" : String.format(Locale.forLanguageTag("es-AR"), "%.2f", valor);
    }

    /**
     * Escapo los caracteres especiales de HTML: los fundamentos y destinatarios los escribe el operador,
     * y un "<" o un "&" sin escapar rompería la página (o insertaría código si alguien lo hiciera a propósito).
     */
    static String texto(String valor) {
        if (valor == null) {
            return "";
        }
        return valor.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
