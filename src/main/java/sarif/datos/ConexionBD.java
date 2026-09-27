package sarif.datos;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Acá obtengo las conexiones JDBC a MySQL con los datos de config/sarif.properties, que está fuera del
 * código fuente: así no dejo la URL, el usuario ni la clave escritos en las clases. Si además existe
 * config/sarif.local.properties, sus valores pisan a los del archivo principal; ahí va la clave de la base,
 * que no se sube al repositorio, y cualquier ajuste local sin tocar la configuración común.
 * La clase es final y con constructor privado porque solo tiene métodos estáticos (es una utilidad).
 */
public final class ConexionBD {

    // La carpeta se puede cambiar con -Dsarif.config=...; por defecto es "config" relativa al directorio de trabajo.
    private static final Path CARPETA_CONFIG = Path.of(System.getProperty("sarif.config", "config"));
    // Leo los archivos una sola vez y guardo el resultado acá (carga perezosa en propiedades()).
    private static Properties propiedades;

    private ConexionBD() {
    }

    /**
     * Abre una conexión nueva. No uso un pool porque es una aplicación de escritorio con un solo usuario;
     * quien la pide es responsable de cerrarla (normalmente con try-with-resources en el servicio).
     */
    public static Connection obtener() throws SQLException {
        Properties p = propiedades();
        return DriverManager.getConnection(p.getProperty("db.url"), p.getProperty("db.usuario"), p.getProperty("db.clave"));
    }

    /** Devuelve un valor de la configuración, o el valor por defecto si no está definido. */
    public static String propiedad(String clave, String porDefecto) {
        return propiedades().getProperty(clave, porDefecto);
    }

    /**
     * Indica si la base está disponible (lo uso para avisar al iniciar y en las pruebas de integración,
     * que se saltean si no hay MySQL). Espero como máximo 3 segundos la validación.
     */
    public static boolean disponible() {
        try (Connection cn = obtener()) {
            return cn.isValid(3);
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Convierte un double a BigDecimal con la escala indicada, para mandarlo a una columna DECIMAL.
     * Lo uso sobre todo con las coordenadas: un double no representa exacto un valor como -37.84550,
     * y al comparar con = en SQL podría no encontrar un foco que sí existe. Redondeando a la misma
     * escala que la columna, comparo sin errores de redondeo. Es de paquete porque solo lo usan los DAO.
     */
    static BigDecimal decimal(double valor, int escala) {
        return BigDecimal.valueOf(valor).setScale(escala, RoundingMode.HALF_UP);
    }

    // Es synchronized para que, si dos hilos piden conexión a la vez (por ejemplo una tarea en segundo plano),
    // los archivos se lean una sola vez. Primero cargo el principal y después el local, que pisa sus valores.
    private static synchronized Properties propiedades() {
        if (propiedades == null) {
            Properties p = new Properties();
            cargar(p, CARPETA_CONFIG.resolve("sarif.properties"));
            cargar(p, CARPETA_CONFIG.resolve("sarif.local.properties"));
            // Sin URL no puedo conectarme: prefiero fallar con un mensaje que diga dónde busqué el archivo.
            if (p.getProperty("db.url") == null) {
                throw new IllegalStateException("No se encontró la configuración de la base en "
                        + CARPETA_CONFIG.toAbsolutePath().resolve("sarif.properties"));
            }
            propiedades = p;
        }
        return propiedades;
    }

    // Si el archivo no existe lo ignoro (el local es opcional); si existe pero no se puede leer, es un error.
    private static void cargar(Properties p, Path archivo) {
        if (!Files.exists(archivo)) {
            return;
        }
        try (InputStream in = Files.newInputStream(archivo)) {
            p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer " + archivo, e);
        }
    }
}
