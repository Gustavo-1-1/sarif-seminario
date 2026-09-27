import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Herramienta aparte (no es parte de SARIF) que arma el mapa general sin conexión de la pestaña
 * "Mapa de riesgo": baja de OpenTopoMap los mosaicos de poco detalle de Argentina y los países limítrofes
 * (zoom 2 a 6) y los de Neuquén con más detalle (zoom 7 a 9), y los deja en
 * src/main/resources/sarif/mapa/mosaicos/{z}/{x}/{y}.png para que viajen dentro del programa.
 * <p>
 * Con eso el mapa nunca queda vacío aunque no haya internet. El detalle fino (curvas de nivel cada pocos
 * metros, caminos, nombres de parajes) lo baja SARIF a medida que se mira el mapa y lo guarda en la caché.
 * No bajo más que esto a propósito: las reglas de uso de OpenTopoMap prohíben las descargas masivas, así
 * que pido de a un mosaico, con una pausa entre pedidos y un User-Agent que identifica al programa. Son
 * 269 mosaicos (unos 8 MB).
 * <p>
 * Uso (desde la carpeta del proyecto):
 * <pre>
 *   javac -encoding UTF-8 -d %TEMP%\mapa herramientas/GeneradorMapa.java
 *   java -cp %TEMP%\mapa GeneradorMapa
 * </pre>
 * Si se corta, se puede volver a correr: salta los mosaicos que ya están.
 */
public class GeneradorMapa {

    private static final String URL = "https://tile.opentopomap.org/%d/%d/%d.png";
    private static final String AGENTE = "SARIF/1.0 (trabajo universitario; generador del mapa general)";
    private static final Path DESTINO = Path.of("src/main/resources/sarif/mapa/mosaicos");

    // Región general: desde el Pacífico hasta el Atlántico y desde el sur de Bolivia y Brasil hasta Tierra del Fuego.
    private static final double[] REGION = {-82.0, -34.0, -56.5, -9.5};      // lonMin, lonMax, latMin, latMax
    private static final double[] NEUQUEN = {-72.3, -67.7, -41.4, -35.8};

    public static void main(String[] args) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        int bajados = 0, existentes = 0, fallidos = 0;
        for (int z = 2; z <= 9; z++) {
            double[] area = z <= 6 ? REGION : NEUQUEN;
            int x0 = mosaicoX(area[0], z), x1 = mosaicoX(area[1], z);
            int y0 = mosaicoY(area[3], z), y1 = mosaicoY(area[2], z);
            System.out.printf("Zoom %d: x %d a %d, y %d a %d (%d mosaicos)%n", z, x0, x1, y0, y1, (x1 - x0 + 1) * (y1 - y0 + 1));
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    Path archivo = DESTINO.resolve(z + "/" + x + "/" + y + ".png");
                    if (Files.exists(archivo)) {
                        existentes++;
                        continue;
                    }
                    if (bajar(http, z, x, y, archivo)) {
                        bajados++;
                    } else {
                        fallidos++;
                    }
                    Thread.sleep(250);
                }
            }
        }
        System.out.printf("Listo: %d bajados, %d ya estaban, %d fallidos.%n", bajados, existentes, fallidos);
    }

    private static boolean bajar(HttpClient http, int z, int x, int y, Path archivo) throws InterruptedException {
        HttpRequest pedido = HttpRequest.newBuilder(URI.create(String.format(URL, z, x, y)))
                .header("User-Agent", AGENTE).timeout(Duration.ofSeconds(30)).GET().build();
        try {
            HttpResponse<byte[]> r = http.send(pedido, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) {
                System.out.printf("  %d/%d/%d: código %d%n", z, x, y, r.statusCode());
                return false;
            }
            Files.createDirectories(archivo.getParent());
            Files.write(archivo, r.body());
            return true;
        } catch (IOException e) {
            System.out.printf("  %d/%d/%d: %s%n", z, x, y, e.getMessage());
            return false;
        }
    }

    // Las mismas fórmulas de sarif.servicio.ProyeccionMercator, pero en número de mosaico.
    private static int mosaicoX(double lon, int z) {
        return (int) Math.floor((lon + 180) / 360 * (1 << z));
    }

    private static int mosaicoY(double lat, int z) {
        double r = Math.toRadians(lat);
        return (int) Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1 << z));
    }
}
