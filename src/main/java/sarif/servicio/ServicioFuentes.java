package sarif.servicio;

import sarif.datos.ConexionBD;
import sarif.datos.ConfiguracionDAO;
import sarif.datos.SincronizacionDAO;
import sarif.fuentes.FuenteNoDisponibleException;
import sarif.fuentes.FuenteRemota;
import sarif.fuentes.FuenteSentinel;
import sarif.modelo.FuenteConfigurada;
import sarif.modelo.OrigenDatos;
import sarif.modelo.Permiso;
import sarif.modelo.Sincronizacion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Pantalla "Fuentes de datos": reúno en un solo lugar las conexiones que usa SARIF (el servicio satelital
 * de focos, el meteorológico, Sentinel-2 para el NDVI y los archivos del modo sin conexión), permito probarlas y ajustar la configuración de
 * la sincronización. La clave de FIRMS no se edita desde acá a propósito: vive en
 * config/sarif.local.properties, que no se sube al repositorio, y en pantalla solo muestro sus últimos
 * caracteres.
 * <p>
 * Los sensores y estaciones de campo del organismo no se conectan en esta versión: quedaron registrados
 * como requerimiento candidato RC07, porque exigirían recibir datos desde la red y protocolos que están
 * fuera del alcance (8.3 y RNF05 del primer trabajo).
 */
public class ServicioFuentes {

    public static final String FIRMS = "NASA FIRMS";
    public static final String OPEN_METEO = "Open-Meteo";
    public static final String SENTINEL = "Copernicus Sentinel-2";
    public static final String ARCHIVO_FOCOS = "Archivo de focos";
    public static final String ARCHIVO_METEO = "Archivo meteorológico";

    /** Productos casi en tiempo real que ofrece FIRMS para la región; el _SP lo elige solo FuenteRemota. */
    public static final List<String> PRODUCTOS_FIRMS = List.of("VIIRS_SNPP_NRT", "VIIRS_NOAA20_NRT", "VIIRS_NOAA21_NRT", "MODIS_NRT");

    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final ServicioSincronizacion sincronizacion = new ServicioSincronizacion();

    /**
     * Armo las cuatro filas de la pantalla. Los archivos los reviso en el momento porque es instantáneo;
     * los servicios remotos quedan "SIN_PROBAR" hasta que el operador toque "Probar conexión", así abrir
     * la pestaña no depende de internet.
     */
    public List<FuenteConfigurada> fuentes() throws SQLException {
        Map<String, String> p;
        List<Sincronizacion> ultimas;
        try (Connection cn = ConexionBD.obtener()) {
            p = new ConfiguracionDAO(cn).parametros();
            ultimas = new SincronizacionDAO(cn).listarUltimas(200);
        }
        String clave = ConexionBD.propiedad("firms.clave", "");
        String origenClave = "config/sarif.local.properties";
        if (clave.isBlank()) {
            clave = p.getOrDefault("firms_clave", "");
            origenClave = "parámetro firms_clave";
        }
        String producto = p.getOrDefault("firms_producto", "VIIRS_SNPP_NRT");
        String configFirms = "Producto " + producto + " · clave "
                + (clave.isBlank() ? "no configurada" : enmascarar(clave) + " (" + origenClave + ")");

        List<FuenteConfigurada> filas = new ArrayList<>();
        filas.add(new FuenteConfigurada(FIRMS, "Satelital (focos)", FuenteRemota.SERVIDOR_FIRMS, configFirms,
                ultima(ultimas, "FOCOS", OrigenDatos.REMOTA), "SIN_PROBAR", "Todavía no se probó la conexión."));
        filas.add(new FuenteConfigurada(OPEN_METEO, "Meteorológica", FuenteRemota.SERVIDOR_OPEN_METEO,
                "Sin clave (uso no comercial) · temperatura, humedad, viento, precipitación y humedad del suelo", ultima(ultimas, "METEO", OrigenDatos.REMOTA),
                "SIN_PROBAR", "Todavía no se probó la conexión."));
        String cliente = ConexionBD.propiedad("copernicus.cliente", "");
        boolean conSecreto = !ConexionBD.propiedad("copernicus.secreto", "").isBlank();
        filas.add(new FuenteConfigurada(SENTINEL, "Satelital (NDVI, informativo)", FuenteSentinel.SERVIDOR,
                cliente.isBlank() || !conSecreto ? "OAuth2 · credenciales no configuradas (copernicus.cliente y copernicus.secreto)"
                        : "OAuth2 · cliente " + enmascarar(cliente) + " (config/sarif.local.properties)",
                ultima(ultimas, "NDVI", OrigenDatos.REMOTA), "SIN_PROBAR", "Todavía no se probó la conexión."));
        filas.add(archivo(ARCHIVO_FOCOS, "Respaldo sin conexión (focos)", "archivo.focos",
                "datos/focos_jornada_20260120.csv", ultima(ultimas, "FOCOS", OrigenDatos.ARCHIVO)));
        filas.add(archivo(ARCHIVO_METEO, "Respaldo sin conexión (meteorología)", "archivo.meteo",
                "datos/meteo_20260120.csv", ultima(ultimas, "METEO", OrigenDatos.ARCHIVO)));
        return filas;
    }

    /**
     * Pruebo una fuente y devuelvo la fila con el resultado. Hace consultas por internet, así que el
     * controlador la llama desde un hilo aparte para no congelar la pantalla.
     */
    public FuenteConfigurada probar(FuenteConfigurada fuente) throws SQLException {
        try {
            return switch (fuente.nombre()) {
                case FIRMS -> fuente.conEstado("CONECTADA", sincronizacion.fuenteRemota().probarSatelital());
                case OPEN_METEO -> fuente.conEstado("CONECTADA", sincronizacion.fuenteRemota().probarMeteorologica());
                case SENTINEL -> fuente.conEstado("CONECTADA", sincronizacion.fuenteSentinel().probar());
                default -> archivo(fuente.nombre(), fuente.tipo(),
                        fuente.nombre().equals(ARCHIVO_FOCOS) ? "archivo.focos" : "archivo.meteo",
                        fuente.direccion(), fuente.ultimaSincronizacion());
            };
        } catch (FuenteNoDisponibleException e) {
            return fuente.conEstado("SIN_CONEXION", e.getMessage());
        }
    }

    /** Valores actuales de la configuración editable: producto, minutos y días. */
    public Map<String, String> configuracion() throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            ConfiguracionDAO conf = new ConfiguracionDAO(cn);
            return Map.of(
                    "firms_producto", conf.parametro("firms_producto", "VIIRS_SNPP_NRT"),
                    "sincronizacion_minutos", conf.parametro("sincronizacion_minutos", "180"),
                    "dias_sincronizacion", conf.parametro("dias_sincronizacion", "2"));
        }
    }

    /**
     * Guardo la configuración de la sincronización en la tabla parametro. Los tres valores van en una
     * transacción: o se guardan todos o ninguno. Solo el administrador cambia la configuración.
     */
    public void guardarConfiguracion(String producto, int minutos, int dias, int idUsuario) throws SQLException, ValidacionException {
        Permisos.exigir(idUsuario, Permiso.CONFIGURAR);
        List<String> errores = validarConfiguracion(producto, minutos, dias);
        if (!errores.isEmpty()) {
            throw new ValidacionException(errores);
        }
        try (Connection cn = ConexionBD.obtener()) {
            cn.setAutoCommit(false);
            try {
                ConfiguracionDAO conf = new ConfiguracionDAO(cn);
                conf.guardarParametro("firms_producto", producto);
                conf.guardarParametro("sincronizacion_minutos", String.valueOf(minutos));
                conf.guardarParametro("dias_sincronizacion", String.valueOf(dias));
                cn.commit();
            } catch (SQLException e) {
                cn.rollback();
                throw e;
            }
        }
    }

    /**
     * Reglas de la configuración. El intervalo mínimo es de 15 minutos porque los satélites no pasan más
     * seguido y no tiene sentido gastar consultas de la clave; el máximo, un día. Los días de focos los
     * limito a 10 (FuenteRemota los parte en tramos de 5).
     */
    public static List<String> validarConfiguracion(String producto, int minutos, int dias) {
        List<String> errores = new ArrayList<>();
        if (producto == null || !PRODUCTOS_FIRMS.contains(producto)) {
            errores.add("Debe elegir un producto satelital de la lista.");
        }
        if (minutos < 15 || minutos > 1440) {
            errores.add("El intervalo de la sincronización programada debe ser de 15 a 1440 minutos.");
        }
        if (dias < 1 || dias > 10) {
            errores.add("Los días de focos por sincronización deben ser de 1 a 10.");
        }
        return errores;
    }

    /** Muestro solo los últimos 4 caracteres de la clave, para poder reconocerla sin exponerla en pantalla. */
    static String enmascarar(String clave) {
        String c = clave.trim();
        return c.length() <= 4 ? "••••" : "••••" + c.substring(c.length() - 4);
    }

    // Reviso el archivo configurado: si existe, cuento las filas de datos (sin el encabezado) y muestro su fecha.
    private static FuenteConfigurada archivo(String nombre, String tipo, String propiedad, String porDefecto, String ultima) {
        Path ruta = Path.of(ConexionBD.propiedad(propiedad, porDefecto));
        String config = "Propiedad " + propiedad + " de config/sarif.properties";
        if (!Files.isRegularFile(ruta)) {
            return new FuenteConfigurada(nombre, tipo, ruta.toString(), config, ultima, "FALTANTE",
                    "No se encontró el archivo: si falla el servicio remoto no va a haber respaldo.");
        }
        try (Stream<String> lineas = Files.lines(ruta)) {
            long filas = Math.max(0, lineas.count() - 1);
            String modificado = LocalDateTime.ofInstant(Files.getLastModifiedTime(ruta).toInstant(), ZoneId.systemDefault())
                    .format(FECHA_HORA);
            return new FuenteConfigurada(nombre, tipo, ruta.toString(), config, ultima, "DISPONIBLE",
                    filas + " filas de datos, modificado el " + modificado + ".");
        } catch (IOException | UncheckedIOException e) {
            return new FuenteConfigurada(nombre, tipo, ruta.toString(), config, ultima, "FALTANTE",
                    "El archivo existe pero no se pudo leer: " + e.getMessage());
        }
    }

    // Última sincronización de un tipo y origen, para la columna de la tabla.
    private static String ultima(List<Sincronizacion> ultimas, String tipo, OrigenDatos origen) {
        return ultimas.stream()
                .filter(s -> s.tipo().equals(tipo) && s.origen() == origen)
                .findFirst()
                .map(s -> s.inicio().format(FECHA_HORA) + " (" + s.resultado() + ")")
                .orElse("Nunca");
    }
}
