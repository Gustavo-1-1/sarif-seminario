package sarif.servicio;

import sarif.datos.ConexionBD;
import sarif.datos.ConfiguracionDAO;
import sarif.datos.FocoDAO;
import sarif.datos.IndiceDAO;
import sarif.datos.MeteoDAO;
import sarif.datos.NdviDAO;
import sarif.datos.RecursoDAO;
import sarif.datos.ZonaDAO;
import sarif.fuentes.FuenteNoDisponibleException;
import sarif.fuentes.ProveedorMosaicos;
import sarif.modelo.ActivoProtegido;
import sarif.modelo.FocoCalor;
import sarif.modelo.IndiceRiesgo;
import sarif.modelo.NivelAlerta;
import sarif.modelo.NivelRiesgo;
import sarif.modelo.Recurso;
import sarif.modelo.RegistroMeteo;
import sarif.modelo.RegistroNdvi;
import sarif.modelo.Zona;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Servicio de la pestaña "Mapa de riesgo". Junta en una sola consulta todo lo que se dibuja sobre el mapa
 * para la fecha de trabajo (zonas con su índice, nivel de alerta y NDVI, focos recientes, activos y
 * recursos), le pide los mosaicos del mapa topográfico al proveedor y consigue las imágenes NDVI de
 * Sentinel-2. El controlador del mapa habla solo con este servicio, nunca con la capa de fuentes.
 * <p>
 * Las carpetas de caché (mosaicos e imágenes NDVI) se configuran en config/sarif.properties y están en
 * .gitignore: son datos descargados, no código.
 */
public class ServicioMapa {

    public static final String ATRIBUCION = "Mapa: © OpenTopoMap (CC-BY-SA) · Datos: © colaboradores de "
            + "OpenStreetMap, SRTM · NDVI: Copernicus Sentinel-2 (ESA)";

    /** Zoom máximo del servidor de mosaicos; más allá, el controlador estira los del último nivel. */
    public static final int ZOOM_MAXIMO_MOSAICOS = ProveedorMosaicos.ZOOM_MAXIMO;

    private final ServicioSincronizacion sincronizacion = new ServicioSincronizacion();
    private final ProveedorMosaicos proveedor = new ProveedorMosaicos(
            ConexionBD.propiedad("mapa.url", "https://tile.opentopomap.org/{z}/{x}/{y}.png"),
            Path.of(ConexionBD.propiedad("mapa.cache", "cache/mosaicos")));

    /**
     * Resultado de pedir algo a internet, para que el controlador no tenga que conocer las excepciones
     * de la capa de fuentes: los bytes del PNG, o el motivo por el que no se pudo. Si los dos son null,
     * el servidor no tiene ese dato (por ejemplo, un mosaico que no existe).
     */
    public record Descarga(byte[] datos, String problema) {
        public boolean fallo() {
            return problema != null;
        }
    }

    /** Focos satelitales de toda la región para el mapa, o el motivo por el que FIRMS no los pudo dar. */
    public record FocosRegion(List<FocoCalor> focos, String problema) {
        public boolean fallo() {
            return problema != null;
        }
    }

    /**
     * Rectángulo que le pido a FIRMS para el mapa (oeste, sur, este, norte): Argentina y Chile completos, y de
     * paso Paraguay, Uruguay y el sur de Bolivia y Brasil. FIRMS acepta áreas de ese tamaño en una sola consulta
     * (un día de septiembre trae más de mil focos y tarda unos segundos).
     */
    static final double[] REGION_FOCOS = {-76, -56, -53, -21};

    /** Minutos que reuso los focos de la región antes de volver a pedirlos (FIRMS limita las consultas por clave). */
    private static final long MINUTOS_CACHE_FOCOS = 10;

    // Última consulta de focos de la región: para qué fecha y días fue, cuándo y qué devolvió.
    private String claveCacheFocos;
    private long horaCacheFocos;
    private List<FocoCalor> cacheFocos;

    /**
     * Lo que se dibuja encima del mapa para una fecha. Los mapas van por id de zona; meteo tiene el registro
     * del día (observado o pronóstico) de las zonas que lo tienen, para las flechas de viento.
     */
    public record DatosMapa(List<Zona> zonas, Map<Integer, IndiceRiesgo> indices, Map<Integer, NivelAlerta> nivelesAlerta,
                            Map<String, NivelRiesgo> nivelesRiesgo, Map<Integer, RegistroNdvi> ndvi, List<FocoCalor> focos,
                            List<ActivoProtegido> activos, Map<Integer, Long> recursosPorZona,
                            Map<Integer, RegistroMeteo> meteo) {
    }

    /** Mosaico guardado en el equipo (caché o mapa general del programa), sin usar la red. Null si no está. */
    public byte[] mosaicoGuardado(int z, int x, int y) {
        return proveedor.local(z, x, y);
    }

    /** Descargo el mosaico de OpenTopoMap (y queda guardado en la caché). */
    public Descarga descargarMosaico(int z, int x, int y) {
        try {
            return new Descarga(proveedor.descargar(z, x, y), null);
        } catch (FuenteNoDisponibleException e) {
            return new Descarga(null, e.getMessage());
        }
    }

    /** Carpeta donde quedan los mosaicos descargados, para mostrarla en la pantalla. */
    public Path carpetaMosaicos() {
        return proveedor.getCache();
    }

    /**
     * Datos del día para dibujar. Traigo todas las zonas (también las inactivas, que se ven en gris) y los
     * focos de los últimos {@code diasFocos} días hasta la fecha, incluida.
     */
    public DatosMapa datos(LocalDate fecha, int diasFocos) throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            ZonaDAO zonaDAO = new ZonaDAO(cn);
            ConfiguracionDAO conf = new ConfiguracionDAO(cn);
            NdviDAO ndviDAO = new NdviDAO(cn);
            List<Zona> zonas = zonaDAO.listar();
            Map<Integer, IndiceRiesgo> indices = new IndiceDAO(cn).listarPorFecha(fecha).stream()
                    .collect(Collectors.toMap(IndiceRiesgo::idZona, Function.identity()));
            Map<Integer, NivelAlerta> alertas = new HashMap<>();
            Map<Integer, RegistroNdvi> ndvi = new HashMap<>();
            for (Zona z : zonas) {
                conf.nivelAlertaVigente(z.getId()).ifPresent(n -> alertas.put(z.getId(), n));
                ndviDAO.ultimo(z.getId(), fecha).ifPresent(r -> ndvi.put(z.getId(), r));
            }
            Map<String, NivelRiesgo> niveles = conf.nivelesRiesgo().stream()
                    .collect(Collectors.toMap(NivelRiesgo::nombre, Function.identity()));
            List<FocoCalor> focos = new FocoDAO(cn).listarEntre(fecha.minusDays(diasFocos - 1L), fecha);
            Map<Integer, Long> recursos = new RecursoDAO(cn).listar().stream()
                    .filter(r -> r.idZonaBase() != null)
                    .collect(Collectors.groupingBy(Recurso::idZonaBase, Collectors.counting()));
            return new DatosMapa(zonas, indices, alertas, niveles, ndvi, focos, zonaDAO.listarActivos(), recursos,
                    new MeteoDAO(cn).delDia(fecha));
        }
    }

    /**
     * Focos de calor que detectó el satélite (NASA FIRMS) en toda la región durante los últimos {@code dias}
     * días hasta la fecha. No los guardo en la base: la base solo guarda los focos de las zonas vigiladas, que
     * son los que usa el índice; estos sirven para ver en el mapa qué se está quemando en el resto del país.
     * Hace una consulta por internet, así que el controlador la llama desde un hilo aparte.
     */
    public synchronized FocosRegion focosRegion(LocalDate fecha, int dias) {
        String clave = fecha + "/" + dias;
        long ahora = System.currentTimeMillis();
        if (clave.equals(claveCacheFocos) && ahora - horaCacheFocos < MINUTOS_CACHE_FOCOS * 60_000) {
            return new FocosRegion(cacheFocos, null);
        }
        try {
            List<FocoCalor> focos = sincronizacion.fuenteRemota().obtenerFocosArea(
                    REGION_FOCOS[0], REGION_FOCOS[1], REGION_FOCOS[2], REGION_FOCOS[3], dias, fecha);
            claveCacheFocos = clave;
            horaCacheFocos = ahora;
            cacheFocos = focos;
            return new FocosRegion(focos, null);
        } catch (FuenteNoDisponibleException e) {
            return new FocosRegion(List.of(), e.getMessage());
        } catch (SQLException e) {
            return new FocosRegion(List.of(), "No se pudo leer la configuración de FIRMS en la base: " + e.getMessage());
        }
    }

    /**
     * Imagen NDVI coloreada de la zona para la fecha. Si ya la bajé antes está en la caché y no gasto
     * cuota de Copernicus ni necesito conexión; si no, la pido a Sentinel Hub y la guardo.
     *
     * @param descargar si es false solo miro la caché (para no pedir nada cuando no hay conexión)
     * @return el PNG, vacío si no está en la caché y no se pidió descargar, o el motivo si Copernicus falló
     */
    public Descarga imagenNdvi(Zona zona, LocalDate fecha, boolean descargar) {
        Path archivo = Path.of(ConexionBD.propiedad("ndvi.cache", "cache/ndvi"))
                .resolve("zona_" + zona.getId() + "_" + fecha + ".png");
        try {
            if (Files.isRegularFile(archivo)) {
                return new Descarga(Files.readAllBytes(archivo), null);
            }
        } catch (IOException e) {
            // Si no la puedo leer, la vuelvo a pedir.
        }
        if (!descargar) {
            return new Descarga(null, null);
        }
        byte[] png;
        try {
            png = sincronizacion.fuenteSentinel().imagenNdvi(zona, fecha);
        } catch (FuenteNoDisponibleException e) {
            return new Descarga(null, e.getMessage());
        }
        try {
            Files.createDirectories(archivo.getParent());
            Files.write(archivo, png);
        } catch (IOException e) {
            // Sin caché la imagen se ve igual; la próxima vez se vuelve a pedir.
        }
        return new Descarga(png, null);
    }
}
