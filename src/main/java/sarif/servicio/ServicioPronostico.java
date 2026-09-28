package sarif.servicio;

import sarif.datos.ConexionBD;
import sarif.datos.FwiDAO;
import sarif.datos.MeteoDAO;
import sarif.fuentes.FuenteRemota;
import sarif.modelo.DireccionViento;
import sarif.modelo.Permiso;
import sarif.modelo.RegistroFwi;
import sarif.modelo.RegistroMeteo;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Servicio de la pestaña "Pronóstico": el pronóstico extendido de cada zona (16 días de Open-Meteo, con el
 * vector del viento, la lluvia y la humedad del suelo) y un resumen de lo que importa para la propagación
 * de un incendio. Los datos salen de registro_meteo, la misma tabla que usa el índice, así no hay dos
 * versiones del mismo dato.
 */
public class ServicioPronostico {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM");

    private final ServicioSincronizacion sincronizacion = new ServicioSincronizacion();

    /**
     * Días de una zona desde el día anterior a la fecha, el FWI que publicó GWIS para cada uno de esos días
     * (puede faltar, porque es un servicio aparte que se sincroniza por separado) y cuándo se cargó el último
     * pronóstico meteorológico (puede ser null).
     */
    public record Pronostico(List<RegistroMeteo> dias, Map<LocalDate, RegistroFwi> fwi, LocalDateTime cargado) {
    }

    /** Pronóstico de la zona: desde el día anterior a la fecha hasta 15 días después. */
    public Pronostico pronostico(int idZona, LocalDate fecha) throws SQLException {
        LocalDate desde = fecha.minusDays(1);
        LocalDate hasta = fecha.plusDays(FuenteRemota.DIAS_PRONOSTICO - 1L);
        try (Connection cn = ConexionBD.obtener()) {
            MeteoDAO meteo = new MeteoDAO(cn);
            Map<LocalDate, RegistroFwi> fwi = new FwiDAO(cn).entre(idZona, desde, hasta).stream()
                    .collect(Collectors.toMap(RegistroFwi::fecha, Function.identity()));
            return new Pronostico(meteo.entre(idZona, desde, hasta), fwi,
                    meteo.ultimaCargaPronostico(idZona).orElse(null));
        }
    }

    /**
     * Botón "Actualizar pronóstico": sincronizo la meteorología de hoy desde Open-Meteo, que trae los 16 días
     * de todas las zonas, también las que todavía no tienen la vigilancia activa (como una zona recién creada),
     * y recalcula el índice de hoy de las vigiladas. No paso al archivo de respaldo si falla: el archivo es de
     * la jornada de prueba y no tiene pronóstico para hoy. Es parte de operar la guardia.
     */
    public ServicioSincronizacion.Resultado actualizar(int idUsuario) throws SQLException, ValidacionException {
        Permisos.exigir(idUsuario, Permiso.OPERAR);
        return sincronizacion.sincronizarMeteo(sincronizacion.fuenteRemota(), LocalDate.now(), idUsuario, true);
    }

    /**
     * Dirección predominante del viento en varios días, en grados desde donde sopla. No alcanza con promediar
     * los grados (el promedio de 350° y 10° daría 180°, viento del sur, cuando los dos son del norte): sumo los
     * vectores, cada uno con el largo de su velocidad máxima, y tomo el ángulo del resultado. Vacío si ningún
     * día tiene dirección o si los vientos se anulan entre sí.
     */
    public static Optional<Integer> direccionPredominante(List<RegistroMeteo> dias) {
        double x = 0, y = 0;
        for (RegistroMeteo d : dias) {
            if (d.direccionVientoGrados() != null) {
                double radianes = Math.toRadians(d.direccionVientoGrados());
                x += d.vientoMaxKmh() * Math.sin(radianes);
                y += d.vientoMaxKmh() * Math.cos(radianes);
            }
        }
        if (Math.hypot(x, y) < 1e-6) {
            return Optional.empty();
        }
        return Optional.of((int) ((Math.round(Math.toDegrees(Math.atan2(x, y))) + 360) % 360));
    }

    /**
     * Resumen del pronóstico desde la fecha (inclusive) para el pie de la pantalla: días que cumplen la regla
     * 30-30-30, viento y ráfaga máximos, dirección predominante (y hacia dónde empujaría el fuego) y lluvia
     * acumulada. Es estático y sin base para probarlo con JUnit.
     */
    public static String resumen(List<RegistroMeteo> dias, LocalDate desde) {
        List<RegistroMeteo> proximos = dias.stream().filter(d -> !d.fecha().isBefore(desde)).toList();
        if (proximos.isEmpty()) {
            return "No hay datos meteorológicos desde el " + desde.format(FECHA) + ".";
        }
        List<String> partes = new ArrayList<>();
        partes.add(proximos.size() + " día(s) desde el " + desde.format(FECHA));
        List<String> criticos = proximos.stream().filter(RegistroMeteo::cumpleRegla30).map(d -> d.fecha().format(FECHA)).toList();
        partes.add(criticos.isEmpty() ? "ningún día cumple la regla 30-30-30"
                : criticos.size() + " cumple(n) la regla 30-30-30 (" + String.join(", ", criticos) + ")");
        RegistroMeteo ventoso = proximos.stream().max(Comparator.comparingDouble(RegistroMeteo::vientoMaxKmh)).orElseThrow();
        partes.add(String.format(Locale.ROOT, "viento máximo %.0f km/h el %s", ventoso.vientoMaxKmh(), ventoso.fecha().format(FECHA)));
        proximos.stream().filter(d -> d.rafagaMaxKmh() != null).max(Comparator.comparingDouble(RegistroMeteo::rafagaMaxKmh))
                .ifPresent(d -> partes.add(String.format(Locale.ROOT, "ráfagas de hasta %.0f km/h", d.rafagaMaxKmh())));
        direccionPredominante(proximos).ifPresent(g -> partes.add("predomina el viento del " + DireccionViento.puntoCardinal(g)
                + ", que empujaría el fuego hacia el " + DireccionViento.puntoCardinal(DireccionViento.haciaDonde(g))));
        partes.add(String.format(Locale.ROOT, "lluvia acumulada %.1f mm", proximos.stream().mapToDouble(RegistroMeteo::precipitacionMm).sum()));
        return String.join(" · ", partes) + ".";
    }
}
