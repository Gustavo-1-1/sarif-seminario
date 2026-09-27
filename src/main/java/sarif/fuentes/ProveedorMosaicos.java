package sarif.fuentes;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Mosaicos del mapa topográfico (OpenTopoMap: relieve sombreado, curvas de nivel, ríos, rutas y nombres
 * de todo el mundo, hecho con datos de OpenStreetMap y SRTM).
 * <p>
 * Un mapa por mosaicos está cortado en cuadrados de 256 × 256 píxeles: en cada nivel de zoom hay
 * 2^zoom × 2^zoom mosaicos y cada uno se pide con una dirección del estilo .../{z}/{x}/{y}.png.
 * Para que SARIF funcione sin conexión busco cada mosaico en tres lugares, en este orden:
 * <ol>
 *   <li>la carpeta de caché (config: mapa.cache), donde guardo todo lo que ya se descargó una vez;</li>
 *   <li>el mapa general que viene dentro del programa (recursos sarif/mapa/mosaicos): Argentina y los
 *       países limítrofes con poco detalle y Neuquén con un poco más, así nunca queda la pantalla vacía;</li>
 *   <li>el servidor de OpenTopoMap, y lo que baja lo guardo en la caché.</li>
 * </ol>
 * No descargo el mapa entero de antemano: las reglas de uso de OpenTopoMap lo prohíben (es un servicio
 * gratuito sostenido por voluntarios) y pesaría varios gigas. Por la misma razón mando un User-Agent
 * que identifica al programa y el controlador pide de a pocos mosaicos por vez.
 * <p>
 * Esta clase no sabe nada de JavaFX: devuelve los bytes del PNG y el controlador los convierte en imagen.
 */
public class ProveedorMosaicos {

    /** Zoom máximo que publica OpenTopoMap. */
    public static final int ZOOM_MAXIMO = 17;
    private static final String SEMILLA = "/sarif/mapa/mosaicos/";
    private static final String AGENTE = "SARIF/1.0 (trabajo universitario, sistema de alerta de incendios forestales)";

    private final String plantilla;
    private final Path cache;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * @param plantilla dirección con {z}, {x} e {y}, por ejemplo https://tile.opentopomap.org/{z}/{x}/{y}.png
     * @param cache     carpeta donde guardo los mosaicos descargados
     */
    public ProveedorMosaicos(String plantilla, Path cache) {
        this.plantilla = plantilla;
        this.cache = cache;
    }

    /** Busco el mosaico sin usar la red: primero en la caché y después en el mapa general. Null si no está. */
    public byte[] local(int z, int x, int y) {
        Path archivo = archivo(z, x, y);
        try {
            if (Files.isRegularFile(archivo)) {
                return Files.readAllBytes(archivo);
            }
        } catch (IOException e) {
            // Si el archivo de la caché está dañado o bloqueado, sigo como si no estuviera.
        }
        try (InputStream in = ProveedorMosaicos.class.getResourceAsStream(SEMILLA + z + "/" + x + "/" + y + ".png")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Descargo el mosaico y lo guardo en la caché. Lo escribo primero en un archivo temporal y después
     * lo renombro, así un corte a mitad de la descarga nunca deja un PNG a medias en la caché.
     *
     * @return los bytes del PNG, o null si el servidor no tiene ese mosaico (código 404)
     */
    public byte[] descargar(int z, int x, int y) throws FuenteNoDisponibleException {
        String url = plantilla.replace("{z}", String.valueOf(z)).replace("{x}", String.valueOf(x)).replace("{y}", String.valueOf(y));
        HttpRequest pedido = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", AGENTE)
                .GET()
                .build();
        try {
            HttpResponse<byte[]> respuesta = http.send(pedido, HttpResponse.BodyHandlers.ofByteArray());
            if (respuesta.statusCode() == 404) {
                return null;
            }
            if (respuesta.statusCode() != 200) {
                throw new FuenteNoDisponibleException("El servidor de mapas respondió con el código " + respuesta.statusCode() + ".");
            }
            guardar(z, x, y, respuesta.body());
            return respuesta.body();
        } catch (IOException e) {
            throw new FuenteNoDisponibleException("Sin conexión con el servidor de mapas: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FuenteNoDisponibleException("La descarga del mapa fue interrumpida.", e);
        }
    }

    private void guardar(int z, int x, int y, byte[] png) {
        Path destino = archivo(z, x, y);
        try {
            Files.createDirectories(destino.getParent());
            Path temporal = Files.createTempFile(destino.getParent(), "mosaico", ".tmp");
            Files.write(temporal, png);
            Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Si no puedo escribir la caché, el mapa se ve igual; solo que la próxima vez lo vuelvo a pedir.
        }
    }

    private Path archivo(int z, int x, int y) {
        return cache.resolve(String.valueOf(z)).resolve(String.valueOf(x)).resolve(y + ".png");
    }

    public Path getCache() {
        return cache;
    }
}
