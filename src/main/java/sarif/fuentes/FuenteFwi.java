package sarif.fuentes;

import sarif.modelo.RegistroFwi;
import sarif.modelo.Zona;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Índice FWI de peligro meteorológico de incendios, tomado ya calculado del servicio de mapas del
 * Global Wildfire Information System (GWIS) de Copernicus, que publica el FWI del modelo ECMWF con
 * una resolución de unos 8 km, tanto del pasado como del pronóstico de los próximos días.
 * <p>
 * El servicio es un WMS estándar. Sus capas raster no admiten GetFeatureInfo (vienen declaradas con
 * queryable="0"), así que el valor lo obtengo con un GetMap sobre el rectángulo de la zona pidiendo
 * FORMAT=image/tiff: en ese formato el servidor devuelve el dato original, un GeoTIFF de una banda de
 * punto flotante, y no la imagen coloreada de la leyenda. Pido una grilla chica y promedio sus celdas,
 * de modo que una zona más grande que la celda del modelo quede representada por el promedio de las
 * celdas que la cubren. El TIFF lo leo con ImageIO, que forma parte de la biblioteca estándar de Java,
 * así que no hace falta ninguna dependencia externa (RNF01).
 * <p>
 * Igual que con Sentinel-2, esto se aparta del GET con CSV previsto en RNF05 y queda documentado: el
 * servicio no ofrece una consulta de texto por punto.
 */
public class FuenteFwi {

    public static final String SERVIDOR = "https://maps.effis.emergency.copernicus.eu/gwis";

    /** Capa del FWI calculado con el modelo ECMWF, la que GWIS usa en su visor de peligro. */
    public static final String CAPA = "ecmwf.fwi";
    public static final String MODELO = "ECMWF";

    /** Celdas por lado que le pido al servicio: con 3x3 alcanza para promediar una zona de varios kilómetros. */
    private static final int CELDAS = 3;
    /** Margen mínimo en grados alrededor de la zona, para que el rectángulo nunca sea degenerado. */
    private static final double MARGEN_MINIMO = 0.02;
    /** El servidor corta la conexión cada tanto; reintento unas pocas veces antes de darlo por caído. */
    private static final int INTENTOS = 4;
    /** Valores fuera de este rango los trato como "sin dato" (el servicio usa sentinelas negativas). */
    private static final double VALOR_MAXIMO = 200.0;

    /**
     * Cliente de descarga. Fuerzo HTTP/1.1 porque con HTTP/2 el servidor de GWIS aborta la transferencia
     * del GeoTIFF; y lo dejo reemplazable porque, cuando corta una descarga, la conexión reutilizada queda
     * en mal estado y hay que empezar de cero (ver {@link #descargar(String)}).
     */
    private HttpClient http = cliente();

    private static HttpClient cliente() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /** FWI de la zona para un día. Vacío si el servicio no tiene dato para esa fecha y ese punto. */
    public Optional<RegistroFwi> obtenerFwi(Zona zona, LocalDate fecha) throws FuenteNoDisponibleException {
        Optional<byte[]> tiff = descargar(url(zona, fecha));
        if (tiff.isEmpty()) {
            return Optional.empty();
        }
        return promedio(tiff.get()).map(v -> new RegistroFwi(zona.getId(), fecha, v, MODELO));
    }

    /**
     * FWI de la zona día por día entre dos fechas, que es como lo muestra la pestaña Pronóstico. Cada día
     * es una consulta (el WMS responde por fecha); los días sin dato simplemente no vienen en la lista.
     */
    public List<RegistroFwi> obtenerFwi(Zona zona, LocalDate desde, LocalDate hasta) throws FuenteNoDisponibleException {
        List<RegistroFwi> registros = new ArrayList<>();
        for (LocalDate f = desde; !f.isAfter(hasta); f = f.plusDays(1)) {
            obtenerFwi(zona, f).ifPresent(registros::add);
        }
        return registros;
    }

    /** "Probar conexión": le pido las capacidades al WMS y verifico que ofrezca la capa del FWI. */
    public String probar() throws FuenteNoDisponibleException {
        byte[] cuerpo = descargar(SERVIDOR + "?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetCapabilities")
                .orElseThrow(() -> new FuenteNoDisponibleException("El servicio de FWI respondió sin contenido."));
        String xml = new String(cuerpo, StandardCharsets.UTF_8);
        if (!xml.contains("<Name>" + CAPA + "</Name>")) {
            throw new FuenteNoDisponibleException("El servicio respondió, pero no ofrece la capa " + CAPA + ".");
        }
        return "Servicio disponible: GWIS publica la capa " + CAPA + " (FWI del modelo " + MODELO + ").";
    }

    /**
     * Armo el GetMap del día. El rectángulo es el de la zona con un margen mínimo, en coordenadas
     * geográficas (EPSG:4326, longitud primero), y el TIME lleva la fecha en formato ISO.
     */
    static String url(Zona zona, LocalDate fecha) {
        double margen = Math.max(MARGEN_MINIMO, 0);
        double lonMin = zona.getLongitudMin() - margen;
        double lonMax = zona.getLongitudMax() + margen;
        double latMin = zona.getLatitudMin() - margen;
        double latMax = zona.getLatitudMax() + margen;
        return String.format(Locale.ROOT, "%s?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&LAYERS=%s&STYLES="
                        + "&FORMAT=image/tiff&SRS=EPSG:4326&BBOX=%.5f,%.5f,%.5f,%.5f&WIDTH=%d&HEIGHT=%d&TIME=%s",
                SERVIDOR, CAPA, lonMin, latMin, lonMax, latMax, CELDAS, CELDAS, fecha);
    }

    /**
     * Promedio de las celdas con dato del GeoTIFF, redondeado a dos decimales. Vacío si el servicio
     * devolvió todas las celdas sin dato (por ejemplo, una fecha que el modelo todavía no cubre).
     */
    static Optional<Double> promedio(byte[] tiff) throws FuenteNoDisponibleException {
        BufferedImage imagen;
        try {
            imagen = ImageIO.read(new ByteArrayInputStream(tiff));
        } catch (IOException e) {
            throw new FuenteNoDisponibleException("No se pudo leer la respuesta del servicio de FWI.", e);
        }
        if (imagen == null) {
            String texto = new String(tiff, StandardCharsets.UTF_8).strip();
            throw new FuenteNoDisponibleException("El servicio de FWI no devolvió una imagen de datos"
                    + (texto.isEmpty() ? "." : ": " + texto.substring(0, Math.min(150, texto.length()))));
        }
        Raster raster = imagen.getRaster();
        double suma = 0;
        int celdas = 0;
        for (int y = 0; y < raster.getHeight(); y++) {
            for (int x = 0; x < raster.getWidth(); x++) {
                double valor = raster.getSampleFloat(x, y, 0);
                if (!Double.isNaN(valor) && valor >= 0 && valor <= VALOR_MAXIMO) {
                    suma += valor;
                    celdas++;
                }
            }
        }
        if (celdas == 0) {
            return Optional.empty();
        }
        return Optional.of(Math.round(100.0 * suma / celdas) / 100.0);
    }

    /**
     * Descargo la respuesta del servicio, distinguiendo las dos fallas que tiene GWIS:
     * <ul>
     *   <li>si no llego a hablar con el servidor (no conecta, vence el tiempo, responde un código de error),
     *       es una caída del servicio y lo informo como tal;</li>
     *   <li>si el servidor acepta la consulta pero después no manda el contenido que anunció, es una falla del
     *       propio servicio en ese día puntual: hay fechas en las que responde 200 y corta el raster siempre,
     *       intento tras intento. En ese caso devuelvo vacío para que la jornada quede sin dato y la
     *       sincronización siga con los demás días, en lugar de dar todo el servicio por caído.</li>
     * </ul>
     * En los dos casos reintento unas pocas veces con una conexión nueva, porque la conexión que se cortó
     * queda en mal estado y no sirve para reutilizar.
     */
    private Optional<byte[]> descargar(String url) throws FuenteNoDisponibleException {
        HttpRequest pedido = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "SARIF/1.0 (trabajo universitario)")
                .header("Accept", "*/*")
                .GET()
                .build();
        IOException ultima = null;
        boolean cortoElContenido = false;
        for (int intento = 1; intento <= INTENTOS; intento++) {
            try {
                // Pido el cuerpo como flujo: así sé que el servidor aceptó la consulta antes de leer el contenido.
                HttpResponse<InputStream> respuesta = http.send(pedido, HttpResponse.BodyHandlers.ofInputStream());
                if (respuesta.statusCode() != 200) {
                    throw new FuenteNoDisponibleException("El servicio de FWI respondió con el código "
                            + respuesta.statusCode() + ".");
                }
                try (InputStream flujo = respuesta.body()) {
                    return Optional.of(flujo.readAllBytes());
                } catch (IOException e) {
                    cortoElContenido = true;
                    ultima = e;
                }
            } catch (IOException e) {
                cortoElContenido = false;
                ultima = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FuenteNoDisponibleException("La consulta del FWI fue interrumpida.", e);
            }
            http = cliente();
            try {
                Thread.sleep(500L * intento);
            } catch (InterruptedException corte) {
                Thread.currentThread().interrupt();
                throw new FuenteNoDisponibleException("La consulta del FWI fue interrumpida.", corte);
            }
        }
        if (cortoElContenido) {
            return Optional.empty();
        }
        throw new FuenteNoDisponibleException("No se pudo conectar con el servicio de FWI de Copernicus (GWIS): "
                + ultima.getMessage(), ultima);
    }
}
