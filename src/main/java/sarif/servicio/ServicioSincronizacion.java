package sarif.servicio;

import sarif.datos.AlertaDAO;
import sarif.datos.ConexionBD;
import sarif.datos.ConfiguracionDAO;
import sarif.datos.FocoDAO;
import sarif.datos.FwiDAO;
import sarif.datos.MeteoDAO;
import sarif.datos.NdviDAO;
import sarif.datos.SincronizacionDAO;
import sarif.datos.ZonaDAO;
import sarif.fuentes.FuenteArchivo;
import sarif.fuentes.FuenteDeDatos;
import sarif.fuentes.FuenteFwi;
import sarif.fuentes.FuenteNoDisponibleException;
import sarif.fuentes.FuenteRemota;
import sarif.fuentes.FuenteSentinel;
import sarif.modelo.Alerta;
import sarif.modelo.FocoCalor;
import sarif.modelo.OrigenDatos;
import sarif.modelo.RegistroFwi;
import sarif.modelo.RegistroMeteo;
import sarif.modelo.RegistroNdvi;
import sarif.modelo.Sincronizacion;
import sarif.modelo.Zona;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Implementa el CU07 Sincronizar focos satelitales (con sus extensiones CU14 y CU15), la importación
 * histórica (RFS08) y el CU08 Sincronizar datos meteorológicos.
 * Trabajo siempre con el tipo abstracto FuenteDeDatos, así este servicio no sabe si los datos vienen
 * de internet o de un archivo (RNF06). Cada lote lo proceso en una transacción: si falla la fuente o
 * la base, revierto todo el lote y dejo asentada la sincronización como FALLIDA con el motivo.
 */
public class ServicioSincronizacion {

    /**
     * Resultado de una sincronización, para mostrárselo al operador. usoRespaldo indica que la fuente remota
     * falló y los datos salieron del archivo (flujo S2 del CU07), así la pantalla lo puede avisar.
     */
    public record Resultado(boolean exitosa, int leidos, int nuevos, String detalle, boolean usoRespaldo) {

        public Resultado(boolean exitosa, int leidos, int nuevos, String detalle) {
            this(exitosa, leidos, nuevos, detalle, false);
        }
    }

    // Archivos de ejemplo de la jornada del 20/01/2026, por si config/sarif.properties no los define.
    private static final String ARCHIVO_FOCOS = "datos/focos_jornada_20260120.csv";
    private static final String ARCHIVO_METEO = "datos/meteo_20260120.csv";

    private final GeneradorAlertas generadorAlertas = new GeneradorAlertas();
    private final ServicioRiesgo servicioRiesgo = new ServicioRiesgo();
    private final ServicioAlertas servicioAlertas = new ServicioAlertas();

    /**
     * CU07: traigo los focos de los últimos {@code dias} días para cada zona activa, guardo los nuevos
     * y genero las alertas del CU14 y CU15. Al final cuento leídos, nuevos, duplicados y fuera de zona.
     */
    public Resultado sincronizarFocos(FuenteDeDatos fuente, int dias, LocalDate hasta, Integer idUsuario) throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            // 1) Abro la transacción para que el lote entero se guarde o se revierta junto.
            cn.setAutoCommit(false);
            try {
                // 2) Registro el inicio de la sincronización; el id lo uso para asociarle cada foco.
                SincronizacionDAO sincronizaciones = new SincronizacionDAO(cn);
                int idSinc = sincronizaciones.iniciar("FOCOS", fuente.getOrigen(), idUsuario);
                ZonaDAO zonas = new ZonaDAO(cn);
                FocoDAO focos = new FocoDAO(cn);
                AlertaDAO alertas = new AlertaDAO(cn);

                // 3) Recorro cada zona activa y le pido a la fuente sus focos.
                int leidos = 0, nuevos = 0, duplicados = 0, fueraDeZona = 0, cantidadAlertas = 0;
                for (Zona zona : zonas.listarActivas()) {
                    for (FocoCalor foco : fuente.obtenerFocos(zona, dias, hasta)) {
                        leidos++;
                        if (!zona.contiene(foco.latitud(), foco.longitud())) {
                            // FuenteArchivo no filtra por área, así que verifico acá que el foco caiga en la zona.
                            fueraDeZona++;
                        } else if (focos.existe(foco)) {
                            // Si ya lo tenía (de una sincronización anterior), lo cuento como duplicado y no lo guardo.
                            duplicados++;
                        } else {
                            // 4) Foco nuevo: lo guardo asociado a la zona y a esta sincronización.
                            FocoCalor nuevo = foco.conZona(zona.getId());
                            long idFoco = focos.insertar(nuevo, idSinc);
                            nuevos++;
                            // Rearmo el foco con el id que le dio la base, porque la alerta tiene que referenciarlo.
                            FocoCalor registrado = new FocoCalor(idFoco, zona.getId(), nuevo.latitud(), nuevo.longitud(),
                                    nuevo.fechaHoraUtc(), nuevo.satelite(), nuevo.instrumento(), nuevo.potenciaFrpMw(),
                                    nuevo.confianza());
                            // 5) Evalúo las alertas (CU14 y CU15) y las guardo dentro de la misma transacción.
                            for (Alerta alerta : generadorAlertas.evaluar(registrado, zona, zonas.activos(zona.getId()))) {
                                alertas.insertar(alerta);
                                cantidadAlertas++;
                            }
                        }
                    }
                }
                // 6) Si alguna zona ya tiene índice del día, cruzo los focos nuevos con ese índice (foco en
                //    zona de riesgo alto). Si todavía no se calculó, estas alertas salen al calcularlo.
                if (nuevos > 0) {
                    cantidadAlertas += servicioAlertas.generarAlertasDeRiesgo(cn, hasta);
                }
                // 7) Cierro la sincronización con el resumen y confirmo la transacción.
                String detalle = String.format("Leídos %d, nuevos %d, duplicados %d, fuera de zona %d, alertas %d",
                        leidos, nuevos, duplicados, fueraDeZona, cantidadAlertas);
                sincronizaciones.finalizar(idSinc, leidos, nuevos, detalle);
                cn.commit();
                return new Resultado(true, leidos, nuevos, detalle);
            } catch (FuenteNoDisponibleException | SQLException e) {
                // Si la fuente falla (flujo S2) o la base da error, revierto todo el lote y registro la falla.
                return registrarFalla(cn, "FOCOS", fuente.getOrigen(), idUsuario, e);
            }
        }
    }

    /**
     * RFS08: importo un archivo histórico de focos (el que alimenta la componente H del índice).
     * A diferencia del CU07, cada foco lo asigno a la zona que lo contiene, esté activa o no, y no genero
     * alertas, porque son focos viejos.
     */
    public Resultado importarHistorico(Path archivo, Integer idUsuario) throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            // 1) Abro la transacción.
            cn.setAutoCommit(false);
            try {
                // 2) Leo el archivo completo, sin filtrar por fechas.
                List<FocoCalor> leidos = new FuenteArchivo(archivo, null).obtenerTodosLosFocos();
                SincronizacionDAO sincronizaciones = new SincronizacionDAO(cn);
                int idSinc = sincronizaciones.iniciar("FOCOS", OrigenDatos.ARCHIVO, idUsuario);
                // Traigo todas las zonas (no solo las activas) porque el histórico vale para cualquiera.
                List<Zona> zonas = new ZonaDAO(cn).listar();
                FocoDAO focos = new FocoDAO(cn);

                // 3) Busco la zona de cada foco y lo guardo si no estaba.
                int nuevos = 0, duplicados = 0, fueraDeZonas = 0;
                for (FocoCalor foco : leidos) {
                    Zona zona = zonas.stream().filter(z -> z.contiene(foco.latitud(), foco.longitud())).findFirst().orElse(null);
                    if (zona == null) {
                        // No cae en ninguna zona registrada: no me sirve, lo descarto.
                        fueraDeZonas++;
                    } else if (focos.existe(foco)) {
                        // Si ya lo tenía, lo cuento como duplicado (así puedo importar el mismo archivo dos veces sin problema).
                        duplicados++;
                    } else {
                        focos.insertar(foco.conZona(zona.getId()), idSinc);
                        nuevos++;
                    }
                }
                // 4) Cierro la sincronización con el resumen y confirmo.
                String detalle = String.format("Importación histórica: leídos %d, nuevos %d, duplicados %d, fuera de zonas %d",
                        leidos.size(), nuevos, duplicados, fueraDeZonas);
                sincronizaciones.finalizar(idSinc, leidos.size(), nuevos, detalle);
                cn.commit();
                return new Resultado(true, leidos.size(), nuevos, detalle);
            } catch (FuenteNoDisponibleException | SQLException e) {
                return registrarFalla(cn, "FOCOS", OrigenDatos.ARCHIVO, idUsuario, e);
            }
        }
    }

    /**
     * CU08: guardo los registros meteorológicos de cada zona activa y, si la sincronización terminó bien,
     * recalculo automáticamente el índice de la fecha (CU09), porque la componente M depende de estos datos.
     */
    public Resultado sincronizarMeteo(FuenteDeDatos fuente, LocalDate fecha, Integer idUsuario) throws SQLException {
        return sincronizarMeteo(fuente, fecha, idUsuario, false);
    }

    /**
     * Igual que la anterior; con {@code todasLasZonas} pido también las zonas sin vigilancia activa. Lo usa el
     * pronóstico extendido: sirve justamente para decidir si conviene activar la vigilancia de una zona nueva.
     * El índice se sigue calculando solo para las zonas vigiladas.
     */
    public Resultado sincronizarMeteo(FuenteDeDatos fuente, LocalDate fecha, Integer idUsuario, boolean todasLasZonas)
            throws SQLException {
        Resultado resultado;
        try (Connection cn = ConexionBD.obtener()) {
            // 1) Abro la transacción del lote meteorológico.
            cn.setAutoCommit(false);
            try {
                SincronizacionDAO sincronizaciones = new SincronizacionDAO(cn);
                int idSinc = sincronizaciones.iniciar("METEO", fuente.getOrigen(), idUsuario);
                MeteoDAO meteo = new MeteoDAO(cn);
                // 2) Para cada zona activa (o todas) pido la meteorología y la guardo (si ya existía el registro, el DAO lo actualiza).
                int procesados = 0;
                ZonaDAO zonas = new ZonaDAO(cn);
                for (Zona zona : todasLasZonas ? zonas.listar() : zonas.listarActivas()) {
                    for (RegistroMeteo registro : fuente.obtenerMeteo(zona, fecha)) {
                        meteo.guardar(registro.conZona(zona.getId()), fuente.getOrigen());
                        procesados++;
                    }
                }
                // 3) Cierro la sincronización y confirmo.
                String detalle = "Registros meteorológicos procesados: " + procesados
                        + (todasLasZonas ? " (todas las zonas, también las no vigiladas)" : "");
                sincronizaciones.finalizar(idSinc, procesados, procesados, detalle);
                cn.commit();
                resultado = new Resultado(true, procesados, procesados, detalle);
            } catch (FuenteNoDisponibleException | SQLException e) {
                // Si falló, no tiene sentido recalcular el índice: registro la falla y salgo.
                return registrarFalla(cn, "METEO", fuente.getOrigen(), idUsuario, e);
            }
        }
        // 4) Recién con la conexión anterior cerrada recalculo el índice (CU09), que abre su propia transacción.
        //    Si el cálculo falla por configuración, la sincronización igual fue exitosa: solo lo aviso en el detalle.
        try {
            ServicioRiesgo.ResultadoCalculo calculo = servicioRiesgo.calcularIndices(fecha);
            return new Resultado(true, resultado.leidos(), resultado.nuevos(), resultado.detalle() + ". " + calculo.resumen());
        } catch (ValidacionException e) {
            return new Resultado(true, resultado.leidos(), resultado.nuevos(),
                    resultado.detalle() + ". No se calculó el índice: " + e.getMessage());
        }
    }

    /**
     * RC05: NDVI de Sentinel-2 para cada zona activa, con la imagen despejada más reciente hasta la fecha.
     * Es informativo (no recalcula el índice). Si Copernicus falla, revierto el lote y queda la
     * sincronización FALLIDA, igual que con los otros servicios; no hay archivo de respaldo para el NDVI.
     */
    public Resultado sincronizarNdvi(LocalDate fecha, Integer idUsuario) throws SQLException {
        FuenteSentinel fuente = fuenteSentinel();
        try (Connection cn = ConexionBD.obtener()) {
            cn.setAutoCommit(false);
            try {
                SincronizacionDAO sincronizaciones = new SincronizacionDAO(cn);
                int idSinc = sincronizaciones.iniciar("NDVI", OrigenDatos.REMOTA, idUsuario);
                NdviDAO ndvi = new NdviDAO(cn);
                int zonas = 0;
                int nuevos = 0;
                List<String> sinImagen = new ArrayList<>();
                for (Zona zona : new ZonaDAO(cn).listarActivas()) {
                    zonas++;
                    Optional<RegistroNdvi> r = fuente.obtenerNdvi(zona, fecha);
                    if (r.isEmpty()) {
                        sinImagen.add(zona.getNombre());
                    } else if (ndvi.guardar(r.get())) {
                        nuevos++;
                    }
                }
                String detalle = "NDVI de " + (zonas - sinImagen.size()) + " de " + zonas + " zonas"
                        + (sinImagen.isEmpty() ? "." : "; sin imagen despejada en 30 días: " + String.join(", ", sinImagen) + ".");
                sincronizaciones.finalizar(idSinc, zonas - sinImagen.size(), nuevos,
                        detalle.substring(0, Math.min(250, detalle.length())));
                cn.commit();
                return new Resultado(true, zonas - sinImagen.size(), nuevos, detalle);
            } catch (FuenteNoDisponibleException | SQLException e) {
                return registrarFalla(cn, "NDVI", OrigenDatos.REMOTA, idUsuario, e);
            }
        }
    }

    /**
     * Traduzco el NDVI a palabras para la pantalla. Los cortes son los habituales en la bibliografía de
     * teledetección; en una zona con nieve o roca el NDVI bajo no indica sequía, por eso es solo orientativo.
     */
    public static String describirNdvi(double ndvi) {
        if (ndvi < 0.2) {
            return "suelo desnudo, roca o nieve";
        }
        if (ndvi < 0.4) {
            return "vegetación rala o seca";
        }
        if (ndvi < 0.6) {
            return "vegetación moderada";
        }
        return "vegetación densa y vigorosa";
    }

    /** Último NDVI de la zona con imagen hasta la fecha, para mostrarlo en la pantalla de zonas. */
    public Optional<RegistroNdvi> ndviVigente(int idZona, LocalDate fecha) throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return new NdviDAO(cn).ultimo(idZona, fecha);
        }
    }

    /**
     * FWI de GWIS para cada zona activa, desde el día anterior a la fecha hasta el final del pronóstico
     * extendido, así la pestaña Pronóstico muestra el índice del servicio junto a cada día. Es informativo
     * (no recalcula el índice propio) y no tiene archivo de respaldo, igual que el NDVI. Si el servicio
     * falla, revierto el lote y la sincronización queda FALLIDA con el motivo.
     */
    public Resultado sincronizarFwi(LocalDate fecha, Integer idUsuario) throws SQLException {
        FuenteFwi fuente = new FuenteFwi();
        LocalDate desde = fecha.minusDays(1);
        LocalDate hasta = fecha.plusDays(FuenteRemota.DIAS_PRONOSTICO - 1L);
        try (Connection cn = ConexionBD.obtener()) {
            cn.setAutoCommit(false);
            try {
                SincronizacionDAO sincronizaciones = new SincronizacionDAO(cn);
                int idSinc = sincronizaciones.iniciar("FWI", OrigenDatos.REMOTA, idUsuario);
                FwiDAO fwi = new FwiDAO(cn);
                int zonas = 0, leidos = 0, nuevos = 0;
                List<String> sinDato = new ArrayList<>();
                for (Zona zona : new ZonaDAO(cn).listarActivas()) {
                    zonas++;
                    List<RegistroFwi> dias = fuente.obtenerFwi(zona, desde, hasta);
                    if (dias.isEmpty()) {
                        sinDato.add(zona.getNombre());
                    }
                    for (RegistroFwi r : dias) {
                        leidos++;
                        if (fwi.guardar(r)) {
                            nuevos++;
                        }
                    }
                }
                String detalle = "FWI (" + FuenteFwi.MODELO + ") de " + zonas + " zonas, " + leidos + " días leídos"
                        + (sinDato.isEmpty() ? "." : "; sin dato del servicio: " + String.join(", ", sinDato) + ".");
                sincronizaciones.finalizar(idSinc, leidos, nuevos, detalle.substring(0, Math.min(250, detalle.length())));
                cn.commit();
                return new Resultado(true, leidos, nuevos, detalle);
            } catch (FuenteNoDisponibleException | SQLException e) {
                return registrarFalla(cn, "FWI", OrigenDatos.REMOTA, idUsuario, e);
            }
        }
    }

    /** Último FWI de la zona hasta la fecha, para mostrarlo en la pantalla de zonas. */
    public Optional<RegistroFwi> fwiVigente(int idZona, LocalDate fecha) throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return new FwiDAO(cn).ultimo(idZona, fecha);
        }
    }

    /** Armo la fuente de Sentinel-2 con las credenciales OAuth de config/sarif.local.properties. */
    public FuenteSentinel fuenteSentinel() {
        return new FuenteSentinel(ConexionBD.propiedad("copernicus.cliente", "").trim(),
                ConexionBD.propiedad("copernicus.secreto", "").trim());
    }

    /**
     * CU07 desde la pantalla o la sincronización programada. Si la fuente elegida es la remota y falla,
     * paso sola a la fuente de archivo (flujo S2), siempre que haya un CSV descargado. La falla de la remota
     * igual queda registrada como FALLIDA, así se sabe que hubo un problema con el servicio.
     */
    public Resultado sincronizarFocos(boolean remota, int dias, LocalDate hasta, Integer idUsuario) throws SQLException {
        if (!remota) {
            return sincronizarFocos(fuenteArchivo(), dias, hasta, idUsuario);
        }
        Resultado r = sincronizarFocos(fuenteRemota(), dias, hasta, idUsuario);
        if (r.exitosa()) {
            return r;
        }
        if (!hayArchivo("archivo.focos", ARCHIVO_FOCOS)) {
            return new Resultado(false, 0, 0, r.detalle() + " No hay archivo de focos para usar como respaldo.");
        }
        return conRespaldo(r, sincronizarFocos(fuenteArchivo(), dias, hasta, idUsuario));
    }

    /** CU08 con el mismo criterio de respaldo que sincronizarFocos(boolean, ...). */
    public Resultado sincronizarMeteo(boolean remota, LocalDate fecha, Integer idUsuario) throws SQLException {
        if (!remota) {
            return sincronizarMeteo(fuenteArchivo(), fecha, idUsuario);
        }
        Resultado r = sincronizarMeteo(fuenteRemota(), fecha, idUsuario);
        if (r.exitosa()) {
            return r;
        }
        if (!hayArchivo("archivo.meteo", ARCHIVO_METEO)) {
            return new Resultado(false, 0, 0, r.detalle() + " No hay archivo meteorológico para usar como respaldo.");
        }
        return conRespaldo(r, sincronizarMeteo(fuenteArchivo(), fecha, idUsuario));
    }

    /**
     * RFS12: carga manual de la meteorología de una zona, como respaldo cuando no hay servicio ni archivo.
     * La registro con origen MANUAL, dejo asentada la carga en la auditoría de sincronizaciones y recalculo
     * el índice de esa fecha, igual que después de una sincronización (CU09).
     */
    public Resultado cargarMeteoManual(RegistroMeteo registro, Integer idUsuario) throws SQLException, ValidacionException {
        List<String> errores = validarMeteo(registro);
        if (!errores.isEmpty()) {
            throw new ValidacionException(errores);
        }
        try (Connection cn = ConexionBD.obtener()) {
            cn.setAutoCommit(false);
            try {
                SincronizacionDAO sincronizaciones = new SincronizacionDAO(cn);
                int idSinc = sincronizaciones.iniciar("METEO", OrigenDatos.MANUAL, idUsuario);
                new MeteoDAO(cn).guardar(registro, OrigenDatos.MANUAL);
                sincronizaciones.finalizar(idSinc, 1, 1, "Carga manual de la zona " + registro.idZona() + " para el "
                        + registro.fecha() + (registro.esPronostico() ? " (pronóstico)" : " (observado)"));
                cn.commit();
            } catch (SQLException e) {
                cn.rollback();
                throw e;
            }
        }
        ServicioRiesgo.ResultadoCalculo calculo = servicioRiesgo.calcularIndices(registro.fecha());
        return new Resultado(true, 1, 1, "Registro meteorológico cargado. " + calculo.resumen());
    }

    /**
     * Controlo que los valores cargados a mano sean posibles. Los rangos son amplios a propósito: solo
     * quiero frenar errores de tipeo (una humedad de 300 % o un viento negativo), no juzgar el dato.
     */
    public static List<String> validarMeteo(RegistroMeteo r) {
        List<String> errores = new ArrayList<>();
        if (r.idZona() <= 0) {
            errores.add("Debe elegir la zona.");
        }
        if (r.fecha() == null) {
            errores.add("Debe indicar la fecha.");
        }
        if (r.temperaturaMaxC() < -30 || r.temperaturaMaxC() > 50) {
            errores.add("La temperatura máxima debe estar entre -30 y 50 °C.");
        }
        if (r.humedadMinPct() < 0 || r.humedadMinPct() > 100) {
            errores.add("La humedad mínima debe estar entre 0 y 100 %.");
        }
        if (r.vientoMaxKmh() < 0 || r.vientoMaxKmh() > 200) {
            errores.add("El viento máximo debe estar entre 0 y 200 km/h.");
        }
        if (r.precipitacionMm() < 0 || r.precipitacionMm() > 500) {
            errores.add("La precipitación debe estar entre 0 y 500 mm.");
        }
        // Es una fracción de volumen: un suelo saturado ronda 0,5 m³/m³, así que más de 1 es un error de tipeo.
        if (r.humedadSueloM3m3() != null && (r.humedadSueloM3m3() < 0 || r.humedadSueloM3m3() > 1)) {
            errores.add("La humedad del suelo debe estar entre 0 y 1 m³/m³.");
        }
        return errores;
    }

    /**
     * RFS20: armo los avisos para el tablero cuando la última sincronización de focos o de meteorología
     * falló, así la guardia se entera aunque no esté mirando la pestaña de sincronización.
     */
    public List<String> avisosDeSincronizacion() throws SQLException {
        List<String> avisos = new ArrayList<>();
        List<Sincronizacion> ultimas = ultimasSincronizaciones(50);
        for (String tipo : List.of("FOCOS", "METEO")) {
            ultimas.stream().filter(s -> s.tipo().equals(tipo)).findFirst()
                    .filter(s -> "FALLIDA".equals(s.resultado()))
                    .ifPresent(s -> avisos.add(String.format("La última sincronización %s (%s) falló: %s",
                            tipo.equals("FOCOS") ? "de focos" : "meteorológica",
                            s.inicio().format(DateTimeFormatter.ofPattern("dd/MM HH:mm")), s.detalle())));
        }
        return avisos;
    }

    /** Minutos entre sincronizaciones programadas (parámetro sincronizacion_minutos, 180 por defecto). */
    public int minutosProgramados() throws SQLException {
        return parametroEntero("sincronizacion_minutos", 180);
    }

    /** Días de focos que pide la sincronización programada (parámetro dias_sincronizacion, 2 por defecto). */
    public int diasProgramados() throws SQLException {
        return parametroEntero("dias_sincronizacion", 2);
    }

    /**
     * Armo la fuente de archivo (modo sin conexión, RNF06) con las rutas de config/sarif.properties.
     * Si no están configuradas, uso los archivos de ejemplo de la jornada del 20/01/2026.
     */
    public FuenteDeDatos fuenteArchivo() {
        return new FuenteArchivo(
                Path.of(ConexionBD.propiedad("archivo.focos", ARCHIVO_FOCOS)),
                Path.of(ConexionBD.propiedad("archivo.meteo", ARCHIVO_METEO)));
    }

    /**
     * Armo la fuente remota. La clave de FIRMS la busco primero en la configuración local (firms.clave,
     * que no se sube al repositorio) y, si no está, en la tabla parametro. El producto sale de la base.
     */
    /**
     * Indica si hay una clave de FIRMS configurada (en el archivo local o en la base). La pantalla de
     * sincronización lo usa para arrancar con la fuente remota cuando se puede usar.
     */
    public boolean hayClaveFirms() {
        try {
            return !fuenteRemotaClave().isBlank();
        } catch (SQLException e) {
            return false;
        }
    }

    private String fuenteRemotaClave() throws SQLException {
        String clave = ConexionBD.propiedad("firms.clave", "");
        if (!clave.isBlank()) {
            return clave.trim();
        }
        try (Connection cn = ConexionBD.obtener()) {
            return new ConfiguracionDAO(cn).parametro("firms_clave", "").trim();
        }
    }

    public FuenteRemota fuenteRemota() throws SQLException {
        String clave = fuenteRemotaClave();
        try (Connection cn = ConexionBD.obtener()) {
            return new FuenteRemota(clave, new ConfiguracionDAO(cn).parametro("firms_producto", "VIIRS_SNPP_NRT"));
        }
    }

    /** Devuelvo las últimas sincronizaciones (exitosas y fallidas) para el historial de la pantalla. */
    public List<Sincronizacion> ultimasSincronizaciones(int cantidad) throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return new SincronizacionDAO(cn).listarUltimas(cantidad);
        }
    }

    /**
     * Revierto el lote y asiento la sincronización como fallida con el motivo (flujo alternativo S2 y excepción).
     * Primero hago el rollback, así no queda nada a medias, y después vuelvo al autocommit para que el
     * registro de la falla sí quede guardado aunque todo lo demás se haya revertido.
     */
    private Resultado registrarFalla(Connection cn, String tipo, OrigenDatos origen, Integer idUsuario, Exception e)
            throws SQLException {
        cn.rollback();
        cn.setAutoCommit(true);
        String motivo = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        new SincronizacionDAO(cn).registrarFallida(tipo, origen, idUsuario, motivo);
        return new Resultado(false, 0, 0, "Sincronización fallida; se conservó el estado previo. Motivo: " + motivo);
    }

    /** Junto el motivo de la falla remota con el resultado que obtuve del archivo de respaldo. */
    private static Resultado conRespaldo(Resultado fallida, Resultado respaldo) {
        String detalle = "La fuente remota falló (" + fallida.detalle() + "). Usé la fuente de archivo: " + respaldo.detalle();
        return new Resultado(respaldo.exitosa(), respaldo.leidos(), respaldo.nuevos(), detalle, true);
    }

    /** Indico si el CSV de respaldo configurado existe; sin archivo no tiene sentido intentar el respaldo. */
    private static boolean hayArchivo(String propiedad, String porDefecto) {
        return Files.isRegularFile(Path.of(ConexionBD.propiedad(propiedad, porDefecto)));
    }

    // Leo un parámetro numérico de la base; si alguien cargó un valor que no es un número, uso el valor por defecto.
    private static int parametroEntero(String clave, int porDefecto) throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return Integer.parseInt(new ConfiguracionDAO(cn).parametro(clave, String.valueOf(porDefecto)).trim());
        } catch (NumberFormatException e) {
            return porDefecto;
        }
    }
}
