package sarif.fuentes;

import sarif.modelo.FocoCalor;
import sarif.modelo.OrigenDatos;
import sarif.modelo.RegistroMeteo;
import sarif.modelo.Zona;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fuente remota: consulto por HTTPS los servicios NASA FIRMS (focos de calor) y Open-Meteo (meteorología).
 * Uso el cliente HTTP estándar de Java (java.net.http) para no sumar dependencias externas.
 * Le puse timeouts (15 s para conectar y 30 s para la respuesta) para que la guardia no quede colgada
 * esperando si el servicio no contesta: en ese caso se informa la falla y se puede pasar a FuenteArchivo.
 * <p>
 * Al probar la conexión real con FIRMS aparecieron dos límites que tengo en cuenta acá: cada consulta
 * acepta como máximo 5 días, y el producto casi en tiempo real (_NRT) solo cubre los últimos meses;
 * lo anterior está en el archivo histórico (_SP).
 */
public class FuenteRemota extends FuenteDeDatos {

    /** Máximo de días que FIRMS acepta en una consulta de área (con más responde error 400). */
    static final int MAX_DIAS_POR_CONSULTA = 5;

    /** Servidores de cada servicio; los expongo para mostrarlos en la pantalla de fuentes de datos. */
    public static final String SERVIDOR_FIRMS = "https://firms.modaps.eosdis.nasa.gov";
    public static final String SERVIDOR_OPEN_METEO = "https://api.open-meteo.com";

    private static final String URL_FIRMS = SERVIDOR_FIRMS + "/api/area/csv/%s/%s/%s/%d/%s";
    private static final String URL_DISPONIBILIDAD = SERVIDOR_FIRMS + "/api/data_availability/csv/%s/ALL";
    /**
     * La API de pronóstico de Open-Meteo solo acepta fechas de los últimos meses; para fechas más viejas
     * (como la jornada de prueba del 20/01/2026) hay que usar la API histórica de pronósticos, que tiene
     * las mismas variables y el mismo formato. Dejo un margen: más de 80 días atrás uso la histórica.
     */
    static final int DIAS_API_PRONOSTICO = 80;
    private static final String SERVIDOR_OPEN_METEO_HISTORICO = "https://historical-forecast-api.open-meteo.com";
    /**
     * Días de pronóstico que traigo cuando la fecha de trabajo es hoy (o ayer): hoy y los 15 siguientes,
     * que es lo máximo que ofrece la API de pronóstico de Open-Meteo.
     */
    public static final int DIAS_PRONOSTICO = 16;
    private static final String CONSULTA_OPEN_METEO = "/v1/forecast?latitude=%.4f&longitude=%.4f"
            + "&daily=temperature_2m_max,relative_humidity_2m_min,wind_speed_10m_max,precipitation_sum,soil_moisture_0_to_10cm_mean"
            + ",temperature_2m_min,wind_gusts_10m_max,wind_direction_10m_dominant,precipitation_probability_max"
            + ",et0_fao_evapotranspiration"
            + "&timezone=America%%2FArgentina%%2FBuenos_Aires&start_date=%s&end_date=%s&format=csv";

    private final String claveFirms;
    private final String productoFirms;
    private final HttpClient cliente = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    // Guardo la tabla de disponibilidad de FIRMS la primera vez que la pido, así no la consulto por cada zona.
    private String disponibilidad;

    /**
     * Recibo la clave de FIRMS y el producto satelital (por ejemplo VIIRS_SNPP_NRT). La clave sale de
     * config/sarif.local.properties o, si no está, de la tabla parametro de la base.
     */
    public FuenteRemota(String claveFirms, String productoFirms) {
        this.claveFirms = claveFirms;
        this.productoFirms = productoFirms;
    }

    /**
     * Pido a FIRMS los focos del rectángulo de la zona. Sin clave no tiene sentido ni intentar la consulta,
     * así que corto antes con un mensaje que le dice al operador qué parámetro falta.
     */
    @Override
    public List<FocoCalor> obtenerFocos(Zona zona, int dias, LocalDate hasta) throws FuenteNoDisponibleException {
        return obtenerFocosArea(zona.getLongitudMin(), zona.getLatitudMin(), zona.getLongitudMax(), zona.getLatitudMax(),
                dias, hasta);
    }

    /**
     * Pido a FIRMS los focos de un rectángulo cualquiera. La uso para cada zona y también para el mapa, que
     * muestra los focos de toda la región aunque caigan fuera de las zonas vigiladas. Los focos que devuelve
     * no tienen zona asignada (idZona 0).
     */
    public List<FocoCalor> obtenerFocosArea(double oeste, double sur, double este, double norte, int dias, LocalDate hasta)
            throws FuenteNoDisponibleException {
        if (claveFirms == null || claveFirms.isBlank()) {
            throw new FuenteNoDisponibleException("No está configurada la clave del servicio satelital "
                    + "(firms.clave en config/sarif.local.properties o parámetro firms_clave).");
        }
        // Armo el rectángulo en el orden que espera el servicio: oeste, sur, este, norte.
        // Uso Locale.ROOT para que los decimales salgan con punto y no con coma.
        String area = String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f", oeste, sur, este, norte);
        LocalDate desde = hasta.minusDays(dias - 1L);
        // Como FIRMS acepta como máximo 5 días por consulta, si me piden más parto el rango en tramos.
        List<FocoCalor> focos = new ArrayList<>();
        for (LocalDate inicio = desde; !inicio.isAfter(hasta); inicio = inicio.plusDays(MAX_DIAS_POR_CONSULTA)) {
            int tramo = (int) Math.min(MAX_DIAS_POR_CONSULTA, ChronoUnit.DAYS.between(inicio, hasta) + 1);
            String url = String.format(Locale.ROOT, URL_FIRMS, claveFirms, productoPara(inicio), area, tramo, inicio);
            focos.addAll(LectorCsvFirms.leer(get(url)));
        }
        // Igual filtro por fechas por si el servicio devuelve algún día de más.
        return filtrarPorFechas(focos, dias, hasta);
    }

    /**
     * Pido a Open-Meteo los datos diarios en el centro de la zona, desde el día anterior a la fecha de
     * referencia (ver urlMeteo hasta qué día). Los días posteriores a la referencia quedan como pronóstico.
     */
    @Override
    public List<RegistroMeteo> obtenerMeteo(Zona zona, LocalDate fechaReferencia) throws FuenteNoDisponibleException {
        String url = urlMeteo(zona.getLatitudCentro(), zona.getLongitudCentro(), fechaReferencia, LocalDate.now());
        return LectorCsvOpenMeteo.leer(get(url), zona, fechaReferencia);
    }

    /**
     * Armo la URL de Open-Meteo desde el día anterior a la fecha de referencia, eligiendo la API según la
     * antigüedad del período. Si la referencia es hoy o ayer pido el pronóstico extendido (hasta hoy + 15);
     * para una jornada pasada alcanza con el día siguiente, porque un "pronóstico" de días que ya pasaron
     * sería engañoso. Recibo "hoy" como parámetro para poder probarlo con JUnit.
     */
    static String urlMeteo(double latitud, double longitud, LocalDate referencia, LocalDate hoy) {
        LocalDate desde = referencia.minusDays(1);
        LocalDate hasta = referencia.isBefore(hoy.minusDays(1)) ? referencia.plusDays(1) : hoy.plusDays(DIAS_PRONOSTICO - 1L);
        String servidor = desde.isBefore(hoy.minusDays(DIAS_API_PRONOSTICO)) ? SERVIDOR_OPEN_METEO_HISTORICO : SERVIDOR_OPEN_METEO;
        return servidor + String.format(Locale.ROOT, CONSULTA_OPEN_METEO, latitud, longitud, desde, hasta);
    }

    @Override
    public OrigenDatos getOrigen() {
        return OrigenDatos.REMOTA;
    }

    /**
     * "Probar conexión" del servicio satelital. Pido la tabla de disponibilidad, que es la consulta más
     * liviana de FIRMS que exige la clave: si responde con la tabla, la clave es válida y además puedo
     * informar qué período cubre el producto configurado.
     */
    public String probarSatelital() throws FuenteNoDisponibleException {
        if (claveFirms == null || claveFirms.isBlank()) {
            throw new FuenteNoDisponibleException("No hay clave configurada (firms.clave en config/sarif.local.properties).");
        }
        disponibilidad = null;
        return describirDisponibilidad(get(String.format(URL_DISPONIBILIDAD, claveFirms)), productoFirms);
    }

    /**
     * "Probar conexión" del servicio meteorológico: pido el pronóstico de hoy para un punto de la región
     * (la ciudad de Neuquén) y controlo que la respuesta sea el CSV con las variables que uso.
     */
    public String probarMeteorologica() throws FuenteNoDisponibleException {
        LocalDate hoy = LocalDate.now();
        String reciente = get(urlMeteo(-38.9516, -68.0591, hoy, hoy));
        String historico = get(urlMeteo(-38.9516, -68.0591, hoy.minusYears(1), hoy));
        if (!reciente.contains("temperature_2m_max") || !historico.contains("temperature_2m_max")) {
            throw new FuenteNoDisponibleException("El servicio respondió, pero no con el formato CSV esperado.");
        }
        return "Responden la API de pronóstico (fechas recientes) y la histórica (más de " + DIAS_API_PRONOSTICO + " días atrás).";
    }

    /**
     * Armo el mensaje de la prueba a partir de la tabla de disponibilidad. Si FIRMS no devuelve la tabla
     * (por ejemplo, contesta "Invalid MAP_KEY."), lo tomo como clave rechazada. Es estático y sin red para
     * probarlo con JUnit, igual que elegirProducto.
     */
    static String describirDisponibilidad(String csv, String producto) throws FuenteNoDisponibleException {
        if (csv == null || !csv.startsWith("data_id")) {
            String respuesta = csv == null ? "" : csv.strip();
            throw new FuenteNoDisponibleException("FIRMS rechazó la consulta"
                    + (respuesta.isEmpty() ? "." : ": " + respuesta.lines().findFirst().orElse("")));
        }
        String cobertura = null;
        String historico = null;
        for (String linea : csv.split("\\R")) {
            String[] c = linea.split(",");
            if (c.length < 3) {
                continue;
            }
            if (c[0].trim().equals(producto)) {
                cobertura = c[1].trim() + " al " + c[2].trim();
            } else if (c[0].trim().equals(producto.replace("_NRT", "_SP")) && !producto.equals(c[0].trim())) {
                historico = c[1].trim() + " al " + c[2].trim();
            }
        }
        if (cobertura == null) {
            throw new FuenteNoDisponibleException("La clave es válida, pero FIRMS no ofrece el producto " + producto + ".");
        }
        return "Clave válida. " + producto + " cubre del " + cobertura
                + (historico == null ? "." : "; para fechas anteriores se usa el archivo histórico (del " + historico + ").");
    }

    /**
     * Decido qué producto pedir para un tramo que empieza en {@code desde}. Si el configurado es casi en
     * tiempo real (_NRT), consulto una sola vez la tabla de disponibilidad de FIRMS; si no la puedo obtener,
     * sigo con el producto configurado y que responda el servicio.
     */
    private String productoPara(LocalDate desde) {
        if (!productoFirms.endsWith("_NRT")) {
            return productoFirms;
        }
        if (disponibilidad == null) {
            try {
                disponibilidad = get(String.format(URL_DISPONIBILIDAD, claveFirms));
            } catch (FuenteNoDisponibleException e) {
                return productoFirms;
            }
        }
        return elegirProducto(disponibilidad, productoFirms, desde);
    }

    /**
     * Con la tabla de disponibilidad de FIRMS (columnas data_id, min_date, max_date) elijo el producto:
     * si la fecha es anterior al primer día que cubre el _NRT y existe el mismo sensor en el archivo
     * histórico (_SP), uso ese. Por ejemplo, para el 20/01/2026 pido VIIRS_SNPP_SP en lugar de VIIRS_SNPP_NRT.
     * Es estático y no toca la red para poder probarlo con JUnit.
     */
    static String elegirProducto(String csvDisponibilidad, String producto, LocalDate desde) {
        String historico = producto.replace("_NRT", "_SP");
        LocalDate inicioNrt = null;
        boolean hayHistorico = false;
        for (String linea : csvDisponibilidad.split("\\R")) {
            String[] c = linea.split(",");
            if (c.length < 2) {
                continue;
            }
            String id = c[0].trim();
            if (id.equals(producto)) {
                try {
                    inicioNrt = LocalDate.parse(c[1].trim());
                } catch (DateTimeParseException e) {
                    return producto;
                }
            } else if (id.equals(historico)) {
                hayHistorico = true;
            }
        }
        return inicioNrt != null && desde.isBefore(inicioNrt) && hayHistorico ? historico : producto;
    }

    /**
     * Ejecuto la petición GET. Cualquier falla de red, timeout o código distinto de 200 lo convierto en
     * FuenteNoDisponibleException, así el servicio trata todos los problemas de la fuente de la misma forma
     * (flujo alternativo S2 del CU07).
     */
    private String get(String url) throws FuenteNoDisponibleException {
        HttpRequest pedido = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        try {
            HttpResponse<String> respuesta = cliente.send(pedido, HttpResponse.BodyHandlers.ofString());
            if (respuesta.statusCode() != 200) {
                // Muestro el comienzo de la respuesta: Open-Meteo y FIRMS explican ahí el motivo del rechazo.
                String motivo = respuesta.body() == null ? "" : respuesta.body().strip();
                throw new FuenteNoDisponibleException("El servicio respondió con el código " + respuesta.statusCode()
                        + (motivo.isEmpty() ? "." : ": " + motivo.substring(0, Math.min(150, motivo.length()))));
            }
            return respuesta.body();
        } catch (IOException e) {
            throw new FuenteNoDisponibleException("No se pudo conectar con el servicio: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            // Vuelvo a marcar la interrupción del hilo para no perderla, como se recomienda en Java.
            Thread.currentThread().interrupt();
            throw new FuenteNoDisponibleException("La consulta al servicio fue interrumpida.", e);
        }
    }
}
