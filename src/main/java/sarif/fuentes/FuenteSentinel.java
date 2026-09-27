package sarif.fuentes;

import sarif.modelo.RegistroNdvi;
import sarif.modelo.Vertice;
import sarif.modelo.Zona;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NDVI de Sentinel-2 desde Copernicus Data Space Ecosystem (RC05 del primer trabajo).
 * <p>
 * Esta fuente se aparta de lo previsto en RNF05 y lo dejo explicado: Copernicus no ofrece una consulta
 * GET con CSV como FIRMS u Open-Meteo. Primero hay que pedir un token OAuth2 (POST con el cliente y el
 * secreto de la cuenta) y después mandar un POST a la API de estadísticas con un JSON que describe la zona,
 * el período y un pequeño script que calcula el NDVI; la respuesta también es JSON. Lo resuelvo igual con
 * el cliente HTTP estándar de Java y leo el JSON con expresiones regulares, sin bibliotecas externas
 * (RNF01), porque solo necesito unos pocos valores de la respuesta.
 * <p>
 * Para esquivar las nubes, el script descarta los píxeles que la clasificación de escena de Sentinel-2
 * (banda SCL) no marca como vegetación (4) o suelo desnudo (5), y me quedo con la imagen más reciente
 * que tenga al menos un 20 % de la zona despejada.
 * <p>
 * Además pido a la API Process una imagen PNG coloreada del NDVI de cada zona, que dibujo en la pestaña
 * Mapa de riesgo para ver dónde está la vegetación seca dentro de la zona, no solo el promedio.
 */
public class FuenteSentinel {

    public static final String SERVIDOR = "https://sh.dataspace.copernicus.eu";
    private static final String URL_TOKEN = "https://identity.dataspace.copernicus.eu/auth/realms/CDSE/protocol/openid-connect/token";
    private static final String URL_ESTADISTICAS = SERVIDOR + "/statistics/v1";

    /** Días hacia atrás en los que busco una imagen despejada: Sentinel-2 pasa cada 5 días aproximadamente. */
    static final int DIAS_BUSQUEDA = 30;
    /** Porcentaje mínimo de píxeles despejados para aceptar una imagen. */
    static final double MINIMO_VALIDO_PCT = 20.0;
    /** Resolución en grados (unos 100 m): suficiente para un promedio por zona y consume pocas unidades de proceso. */
    private static final double RESOLUCION_GRADOS = 0.001;

    // El script va en una sola línea porque viaja dentro de un texto JSON.
    private static final String EVALSCRIPT = "//VERSION=3\\n"
            + "function setup(){return{input:[{bands:[\\\"B04\\\",\\\"B08\\\",\\\"SCL\\\",\\\"dataMask\\\"]}],"
            + "output:[{id:\\\"ndvi\\\",bands:1,sampleType:\\\"FLOAT32\\\"},{id:\\\"dataMask\\\",bands:1}]};}\\n"
            + "function evaluatePixel(s){var ndvi=(s.B08-s.B04)/(s.B08+s.B04);"
            + "var valido=(s.dataMask==1&&(s.SCL==4||s.SCL==5))?1:0;"
            + "return{ndvi:[ndvi],dataMask:[valido]};}";

    private static final String PEDIDO = "{\"input\":{\"bounds\":%s,"
            + "\"data\":[{\"type\":\"sentinel-2-l2a\",\"dataFilter\":{\"maxCloudCoverage\":90}}]},"
            + "\"aggregation\":{\"timeRange\":{\"from\":\"%sT00:00:00Z\",\"to\":\"%sT00:00:00Z\"},"
            + "\"aggregationInterval\":{\"of\":\"P1D\"},\"evalscript\":\"%s\",\"resx\":%s,\"resy\":%s}}";

    private static final String URL_PROCESO = SERVIDOR + "/api/v1/process";

    /**
     * Script de la imagen para el mapa: pinta cada píxel con una escala de colores según el NDVI (gris para
     * roca o nieve, marrón para suelo desnudo, amarillo para vegetación seca y verdes cada vez más oscuros
     * para vegetación más densa). Las nubes, sus sombras y el agua quedan transparentes (banda SCL), así se
     * ve el mapa de abajo. Devuelve RGBA con valores de 0 a 1, que Sentinel Hub pasa a 0-255 en el PNG.
     */
    private static final String EVALSCRIPT_IMAGEN = "//VERSION=3\\n"
            + "function setup(){return{input:[\\\"B04\\\",\\\"B08\\\",\\\"SCL\\\",\\\"dataMask\\\"],output:{bands:4}};}\\n"
            + "function evaluatePixel(s){var v=(s.B08-s.B04)/(s.B08+s.B04);"
            + "var tapado=[3,6,8,9,10].indexOf(s.SCL)>=0;"
            + "var c=colorBlend(v,[-0.1,0.1,0.25,0.4,0.6,0.8],"
            + "[[0.72,0.72,0.72],[0.62,0.45,0.28],[0.93,0.84,0.42],[0.62,0.8,0.3],[0.2,0.6,0.2],[0.0,0.36,0.1]]);"
            + "return[c[0],c[1],c[2],(s.dataMask==1&&!tapado)?0.8:0];}";

    // Con "leastCC" Sentinel Hub arma la imagen con la pasada menos nublada del período.
    private static final String PEDIDO_IMAGEN = "{\"input\":{\"bounds\":%s,"
            + "\"data\":[{\"type\":\"sentinel-2-l2a\",\"dataFilter\":{\"timeRange\":{\"from\":\"%sT00:00:00Z\","
            + "\"to\":\"%sT00:00:00Z\"},\"maxCloudCoverage\":60,\"mosaickingOrder\":\"leastCC\"}}]},"
            + "\"output\":{\"width\":%d,\"height\":%d,\"responses\":[{\"identifier\":\"default\","
            + "\"format\":{\"type\":\"image/png\"}}]},\"evalscript\":\"%s\"}";

    /** Píxeles máximos del lado mayor de la imagen del mapa: unos 20 a 30 m por píxel en zonas como las de prueba. */
    public static final int LADO_MAXIMO_IMAGEN = 1024;

    private final String cliente;
    private final String secreto;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    /** El cliente y el secreto OAuth salen de config/sarif.local.properties (copernicus.cliente y copernicus.secreto). */
    public FuenteSentinel(String cliente, String secreto) {
        this.cliente = cliente;
        this.secreto = secreto;
    }

    /**
     * Busco el NDVI medio de la zona en la imagen despejada más reciente de los 30 días anteriores a la
     * fecha (incluida). Si en ese período todas las imágenes estuvieron nubladas, devuelvo vacío.
     */
    public Optional<RegistroNdvi> obtenerNdvi(Zona zona, LocalDate fecha) throws FuenteNoDisponibleException {
        String json = String.format(Locale.ROOT, PEDIDO, limites(zona),
                fecha.minusDays(DIAS_BUSQUEDA - 1L), fecha.plusDays(1), EVALSCRIPT,
                String.valueOf(RESOLUCION_GRADOS), String.valueOf(RESOLUCION_GRADOS));
        HttpRequest pedido = HttpRequest.newBuilder(URI.create(URL_ESTADISTICAS))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return leerEstadisticas(new String(enviar(pedido), StandardCharsets.UTF_8)).map(r -> r.conZona(zona.getId()));
    }

    /**
     * Imagen NDVI coloreada de la zona para dibujar sobre el mapa (PNG con transparencia), armada con la
     * pasada menos nublada de los 30 días anteriores a la fecha. La imagen cubre el rectángulo de la zona;
     * si la zona es un polígono, lo que queda afuera del contorno viene transparente.
     */
    public byte[] imagenNdvi(Zona zona, LocalDate fecha) throws FuenteNoDisponibleException {
        int[] tamano = tamanoImagen(zona);
        String json = String.format(Locale.ROOT, PEDIDO_IMAGEN, limites(zona),
                fecha.minusDays(DIAS_BUSQUEDA - 1L), fecha.plusDays(1), tamano[0], tamano[1], EVALSCRIPT_IMAGEN);
        HttpRequest pedido = HttpRequest.newBuilder(URI.create(URL_PROCESO))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/json")
                .header("Accept", "image/png")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return enviar(pedido);
    }

    /**
     * Ancho y alto de la imagen respetando las proporciones reales de la zona: un grado de longitud mide
     * menos kilómetros que uno de latitud (se achica con el coseno de la latitud). El lado mayor va con
     * LADO_MAXIMO_IMAGEN píxeles.
     */
    static int[] tamanoImagen(Zona zona) {
        double anchoKm = (zona.getLongitudMax() - zona.getLongitudMin()) * Math.cos(Math.toRadians(zona.getLatitudCentro()));
        double altoKm = zona.getLatitudMax() - zona.getLatitudMin();
        double factor = LADO_MAXIMO_IMAGEN / Math.max(anchoKm, altoKm);
        return new int[]{Math.max(16, (int) Math.round(anchoKm * factor)), Math.max(16, (int) Math.round(altoKm * factor))};
    }

    /**
     * Parte "bounds" del pedido: el rectángulo de la zona y, si es un polígono, también su contorno
     * en GeoJSON (longitud primero; el anillo se cierra repitiendo el primer vértice).
     */
    static String limites(Zona zona) {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "{\"bbox\":[%.5f,%.5f,%.5f,%.5f],",
                zona.getLongitudMin(), zona.getLatitudMin(), zona.getLongitudMax(), zona.getLatitudMax()));
        if (zona.esPoligono()) {
            sb.append("\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[");
            List<Vertice> v = new ArrayList<>(zona.getVertices());
            v.add(v.get(0));
            for (int i = 0; i < v.size(); i++) {
                sb.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT, "[%.5f,%.5f]", v.get(i).longitud(), v.get(i).latitud()));
            }
            sb.append("]]},");
        }
        return sb.append("\"properties\":{\"crs\":\"http://www.opengis.net/def/crs/OGC/1.3/CRS84\"}}").toString();
    }

    /** "Probar conexión": si Copernicus me da un token, el cliente y el secreto son válidos. */
    public String probar() throws FuenteNoDisponibleException {
        token();
        return "Credenciales válidas: Copernicus Data Space entregó el token de acceso.";
    }

    /**
     * Pido el token OAuth2 con el flujo "client credentials": un POST con el cliente y el secreto en
     * formato de formulario. El token dura unos minutos, así que lo pido en cada consulta.
     */
    private String token() throws FuenteNoDisponibleException {
        if (cliente == null || cliente.isBlank() || secreto == null || secreto.isBlank()) {
            throw new FuenteNoDisponibleException("No están configuradas las credenciales de Copernicus "
                    + "(copernicus.cliente y copernicus.secreto en config/sarif.local.properties).");
        }
        String formulario = "grant_type=client_credentials&client_id=" + URLEncoder.encode(cliente, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(secreto, StandardCharsets.UTF_8);
        HttpRequest pedido = HttpRequest.newBuilder(URI.create(URL_TOKEN))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formulario))
                .build();
        return leerToken(new String(enviar(pedido), StandardCharsets.UTF_8));
    }

    /** Saco el access_token de la respuesta JSON del servidor de identidad. */
    static String leerToken(String json) throws FuenteNoDisponibleException {
        Matcher m = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"").matcher(json == null ? "" : json);
        if (!m.find()) {
            throw new FuenteNoDisponibleException("Copernicus no entregó el token de acceso: revise el cliente y el secreto.");
        }
        return m.group(1);
    }

    /**
     * Leo la respuesta de la API de estadísticas. Viene un bloque por día ("interval") con la fecha, el NDVI
     * medio y la cantidad de píxeles (sampleCount) y de píxeles descartados (noDataCount). Los días sin
     * imagen o totalmente nublados traen mean "NaN" o todos los píxeles descartados. Recorro los bloques y
     * me quedo con el más reciente que tenga al menos un 20 % de píxeles válidos.
     */
    static Optional<RegistroNdvi> leerEstadisticas(String json) throws FuenteNoDisponibleException {
        if (json == null || !json.contains("\"data\"")) {
            throw new FuenteNoDisponibleException("Respuesta inesperada de la API de estadísticas de Copernicus.");
        }
        Pattern desde = Pattern.compile("\"from\"\\s*:\\s*\"(\\d{4}-\\d{2}-\\d{2})");
        Pattern media = Pattern.compile("\"mean\"\\s*:\\s*\"?(-?[0-9.eE+-]+|NaN)\"?");
        Pattern muestras = Pattern.compile("\"sampleCount\"\\s*:\\s*(\\d+)");
        Pattern sinDato = Pattern.compile("\"noDataCount\"\\s*:\\s*(\\d+)");
        RegistroNdvi elegido = null;
        String[] bloques = json.split("\"interval\"");
        for (int i = 1; i < bloques.length; i++) {
            Matcher f = desde.matcher(bloques[i]);
            Matcher m = media.matcher(bloques[i]);
            Matcher s = muestras.matcher(bloques[i]);
            Matcher n = sinDato.matcher(bloques[i]);
            if (!f.find() || !m.find() || !s.find() || !n.find() || m.group(1).equals("NaN")) {
                continue;
            }
            try {
                int total = Integer.parseInt(s.group(1));
                int validos = total - Integer.parseInt(n.group(1));
                double porcentaje = total == 0 ? 0 : Math.round(1000.0 * validos / total) / 10.0;
                LocalDate fecha = LocalDate.parse(f.group(1));
                if (porcentaje >= MINIMO_VALIDO_PCT && (elegido == null || fecha.isAfter(elegido.fechaImagen()))) {
                    elegido = new RegistroNdvi(0, fecha, Double.parseDouble(m.group(1)), validos, porcentaje);
                }
            } catch (NumberFormatException | DateTimeParseException e) {
                throw new FuenteNoDisponibleException("Valor inesperado en la respuesta de Copernicus.", e);
            }
        }
        return Optional.ofNullable(elegido);
    }

    // Envío el pedido y convierto cualquier problema (red, timeout, código distinto de 200) en FuenteNoDisponibleException.
    // Leo la respuesta como bytes porque la imagen NDVI es un PNG; las respuestas JSON las paso a texto afuera.
    private byte[] enviar(HttpRequest pedido) throws FuenteNoDisponibleException {
        try {
            HttpResponse<byte[]> respuesta = http.send(pedido, HttpResponse.BodyHandlers.ofByteArray());
            if (respuesta.statusCode() == 401 || respuesta.statusCode() == 403) {
                throw new FuenteNoDisponibleException("Copernicus rechazó las credenciales (código " + respuesta.statusCode() + ").");
            }
            if (respuesta.statusCode() != 200) {
                String motivo = respuesta.body() == null ? "" : new String(respuesta.body(), StandardCharsets.UTF_8).strip();
                throw new FuenteNoDisponibleException("Copernicus respondió con el código " + respuesta.statusCode()
                        + (motivo.isEmpty() ? "." : ": " + motivo.substring(0, Math.min(150, motivo.length()))));
            }
            return respuesta.body();
        } catch (IOException e) {
            throw new FuenteNoDisponibleException("No se pudo conectar con Copernicus: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FuenteNoDisponibleException("La consulta a Copernicus fue interrumpida.", e);
        }
    }
}
