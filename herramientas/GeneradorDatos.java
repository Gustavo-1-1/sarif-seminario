import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Genera el archivo histórico de focos (datos/focos_historicos_2020_2025.csv) con el formato VIIRS de NASA FIRMS.
 * Los focos son ficticios, pero las cantidades por zona y temporada están elegidas para ejercitar el cálculo
 * de la componente histórica del índice y la consulta C-3.
 *
 * <p>Escribí esta herramienta porque no tenía sentido armar a mano cientos de filas de CSV, y porque necesitaba
 * controlar exactamente cuántos focos caen en cada zona, en cada temporada y dentro de la ventana de ±15 días
 * alrededor del 20 de enero. De esas cantidades depende la componente histórica (H) del índice de la jornada de
 * prueba, así que con este generador me aseguro de que los valores del informe sean reproducibles. En total
 * genero 257 focos dentro de las zonas y 103 fuera de ellas (360 filas), que son los números que verifica
 * la prueba de integración PI-03.</p>
 *
 * <p>No forma parte del sistema entregado: es un programa suelto, sin paquete, que se ejecuta con el lanzador de
 * archivos únicos de Java (sin compilar antes).</p>
 *
 * Uso, desde la carpeta del proyecto:  java herramientas/GeneradorDatos.java
 */
public class GeneradorDatos {

    /** Rectángulos de las zonas de sql/02_datos.sql: latMin, latMax, lonMin, lonMax. */
    static final double[][] ZONAS = {
            {-40.20, -40.02, -71.50, -71.25},   // 1 San Martín de los Andes - Lolog
            {-39.40, -39.10, -71.40, -70.85},   // 2 Aluminé - Lanín Norte
            {-40.85, -40.65, -71.80, -71.55},   // 3 Villa La Angostura
            {-37.95, -37.75, -71.20, -70.95},   // 4 Caviahue - Copahue
            {-39.05, -38.80, -71.35, -71.05},   // 5 Villa Pehuenia - Moquehue
            {-40.00, -39.70, -71.50, -71.00}};  // 6 Junín de los Andes - Huechulafquen

    /**
     * Focos por temporada (2020-21 a 2024-25) de cada zona. Caviahue y Pehuenia son las zonas con más
     * actividad y Villa La Angostura la de menos, para que C-3 (que solo muestra temporadas con diez focos
     * o más) deje afuera algunas temporadas.
     */
    static final int[][] POR_TEMPORADA = {
            {10, 7, 6, 9, 11},
            {13, 13, 10, 8, 7},
            {3, 2, 4, 3, 2},
            {14, 14, 17, 14, 12},
            {15, 14, 9, 11, 12},
            {4, 3, 4, 3, 3}};

    /**
     * De ellos, cuántos caen en la ventana de ±15 días alrededor del 20 de enero. La suma de cada fila,
     * dividida por la saturación de 30 focos, da la componente H de la zona (por ejemplo, 28 / 30 = 0,933
     * en Caviahue, el valor que uso en PU-05).
     */
    static final int[][] EN_VENTANA = {
            {5, 3, 3, 4, 4},      // 19 focos -> H = 0,633
            {4, 3, 3, 3, 2},      // 15 focos -> H = 0,500
            {1, 1, 2, 1, 1},      //  6 focos -> H = 0,200
            {6, 5, 7, 5, 5},      // 28 focos -> H = 0,933
            {7, 6, 4, 6, 6},      // 29 focos -> H = 0,967
            {1, 1, 1, 1, 1}};

    /** Focos que no caen en ninguna zona: la importación los tiene que leer y descartar (PI-03). */
    static final int FUERA_DE_ZONAS = 103;

    /** Uso una semilla fija para que cada ejecución genere exactamente el mismo archivo. */
    static final Random azar = new Random(2026);
    /** Claves de los focos ya generados, para no repetir una detección (misma clave natural que en la base). */
    static final Set<String> claves = new HashSet<>();

    public static void main(String[] args) throws IOException {
        List<String[]> filas = new ArrayList<>();
        // Recorro cada zona y cada temporada; los primeros EN_VENTANA focos van dentro de la ventana del
        // 20 de enero y el resto fuera. Achico el rectángulo 0,005 grados por lado para que ningún foco
        // quede justo sobre el borde de la zona.
        for (int z = 0; z < ZONAS.length; z++) {
            for (int t = 0; t < 5; t++) {
                int anioInicio = 2020 + t;
                for (int i = 0; i < POR_TEMPORADA[z][t]; i++) {
                    LocalDate fecha = i < EN_VENTANA[z][t] ? fechaEnVentana(anioInicio) : fechaFueraDeVentana(anioInicio);
                    double[] r = ZONAS[z];
                    filas.add(foco(entre(r[0] + 0.005, r[1] - 0.005), entre(r[2] + 0.005, r[3] - 0.005), fecha));
                }
            }
        }
        for (int i = 0; i < FUERA_DE_ZONAS; i++) {
            // Estepa al este de la cordillera: ninguna zona llega al este de -70,85.
            int anioInicio = 2020 + azar.nextInt(5);
            filas.add(foco(entre(-39.50, -37.50), entre(-70.60, -69.80), fechaFueraDeVentana(anioInicio)));
        }
        // Ordeno por fecha y hora, como vienen los archivos de FIRMS. Completo la hora con ceros a la
        // izquierda solo para comparar, porque en el CSV va sin ceros (por ejemplo, 436 es 04:36).
        filas.sort((a, b) -> (a[5] + String.format("%04d", Integer.parseInt(a[6])))
                .compareTo(b[5] + String.format("%04d", Integer.parseInt(b[6]))));

        // Encabezado idéntico al de un CSV VIIRS de FIRMS, para que LectorCsvFirms lo lea como uno real.
        StringBuilder csv = new StringBuilder(
                "latitude,longitude,bright_ti4,scan,track,acq_date,acq_time,satellite,instrument,confidence,version,bright_ti5,frp,daynight\n");
        for (String[] f : filas) {
            csv.append(String.join(",", f)).append('\n');
        }
        Path destino = Path.of("datos", "focos_historicos_2020_2025.csv");
        Files.writeString(destino, csv.toString(), StandardCharsets.UTF_8);
        System.out.println("Generados " + filas.size() + " focos en " + destino);
    }

    /** Entre el 6 de enero y el 3 de febrero del segundo año de la temporada. */
    static LocalDate fechaEnVentana(int anioInicio) {
        return LocalDate.of(anioInicio + 1, 1, 6).plusDays(azar.nextInt(29));
    }

    /** Noviembre-diciembre o mediados de febrero a marzo: siempre fuera de la ventana del 20 de enero. */
    static LocalDate fechaFueraDeVentana(int anioInicio) {
        if (azar.nextBoolean()) {
            return LocalDate.of(anioInicio, 11, 1).plusDays(azar.nextInt(61));
        }
        return LocalDate.of(anioInicio + 1, 2, 12).plusDays(azar.nextInt(48));
    }

    /**
     * Armo una fila del CSV con los valores de las columnas VIIRS. Elijo horas cercanas a los pasos
     * reales de los satélites (madrugada y tarde en UTC), uso el satélite N20 recién desde 2023 y reparto
     * la confianza en 20 % baja, 60 % nominal y 20 % alta. Si la combinación de coordenadas, fecha, hora y
     * satélite ya salió, sorteo de nuevo la hora y el satélite, porque la base la rechazaría como duplicada.
     */
    static String[] foco(double lat, double lon, LocalDate fecha) {
        while (true) {
            int[] horas = {4, 5, 17, 18};
            int hora = horas[azar.nextInt(horas.length)];
            int minuto = azar.nextInt(60);
            String satelite = fecha.getYear() >= 2023 && azar.nextBoolean() ? "N20" : "N";
            String clave = String.format(Locale.ROOT, "%.5f|%.5f|%s|%d|%s", lat, lon, fecha, hora * 100 + minuto, satelite);
            if (!claves.add(clave)) {
                continue;
            }
            int c = azar.nextInt(10);
            String confianza = c < 2 ? "l" : (c < 8 ? "n" : "h");
            // Uso Locale.ROOT para que los decimales salgan con punto, como en los archivos de FIRMS.
            return new String[]{
                    String.format(Locale.ROOT, "%.5f", lat),
                    String.format(Locale.ROOT, "%.5f", lon),
                    String.format(Locale.ROOT, "%.2f", entre(305, 367)),
                    String.format(Locale.ROOT, "%.2f", entre(0.39, 0.60)),
                    String.format(Locale.ROOT, "%.2f", entre(0.36, 0.50)),
                    fecha.toString(),
                    String.valueOf(hora * 100 + minuto),
                    satelite,
                    "VIIRS",
                    confianza,
                    "2",
                    String.format(Locale.ROOT, "%.2f", entre(280, 302)),
                    String.format(Locale.ROOT, "%.2f", entre(1.5, 30)),
                    hora >= 12 ? "D" : "N"};
        }
    }

    /** Número al azar entre min y max, con la semilla fija de la clase. */
    static double entre(double min, double max) {
        return min + azar.nextDouble() * (max - min);
    }
}
