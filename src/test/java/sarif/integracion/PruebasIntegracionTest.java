package sarif.integracion;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import sarif.datos.AlertaDAO;
import sarif.datos.ConexionBD;
import sarif.datos.ConfiguracionDAO;
import sarif.datos.FocoDAO;
import sarif.datos.IndiceDAO;
import sarif.datos.RecursoDAO;
import sarif.datos.RelevamientoDAO;
import sarif.datos.SincronizacionDAO;
import sarif.datos.ZonaDAO;
import sarif.fuentes.FuenteArchivo;
import sarif.fuentes.FuenteDeDatos;
import sarif.fuentes.FuenteNoDisponibleException;
import sarif.modelo.ActivoProtegido;
import sarif.modelo.Alerta;
import sarif.modelo.Asignacion;
import sarif.modelo.EstadoAlerta;
import sarif.modelo.EstadoRecurso;
import sarif.modelo.EventoGuardia;
import sarif.modelo.FilaDesempeno;
import sarif.modelo.FilaReporteZona;
import sarif.modelo.FocoCalor;
import sarif.modelo.IndiceRiesgo;
import sarif.modelo.MarcaMapa;
import sarif.modelo.MedioNotificacion;
import sarif.modelo.NivelAlerta;
import sarif.modelo.OrigenDatos;
import sarif.modelo.PosicionRecurso;
import sarif.modelo.Recurso;
import sarif.modelo.RegistroMeteo;
import sarif.modelo.Rol;
import sarif.modelo.TipoAlerta;
import sarif.modelo.TipoCombustible;
import sarif.modelo.TipoEvento;
import sarif.modelo.TipoMarca;
import sarif.modelo.TipoRecurso;
import sarif.modelo.Usuario;
import sarif.modelo.Vertice;
import sarif.modelo.Zona;
import sarif.servicio.ServicioAlertas;
import sarif.servicio.ServicioAutenticacion;
import sarif.servicio.ServicioDespliegue;
import sarif.servicio.ServicioRecursos;
import sarif.servicio.ServicioReportes;
import sarif.servicio.ServicioPronostico;
import sarif.servicio.ServicioRiesgo;
import sarif.servicio.ServicioSincronizacion;
import sarif.servicio.ServicioUbicaciones;
import sarif.servicio.ServicioUsuarios;
import sarif.servicio.ServicioZonas;
import sarif.servicio.ValidacionException;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pruebas de integración contra MySQL, en el orden del circuito de la guardia.
 * Requieren una base recién creada con sql/01_esquema.sql y sql/02_datos.sql (ver ejecutar_pruebas.sh);
 * si la base no está disponible o ya fue usada, se omiten.
 *
 * <p>A diferencia de las pruebas unitarias, acá ejercito el sistema completo: servicios, DAO, la base real
 * y los archivos CSV de la carpeta datos/ (la fuente de archivo del modo sin conexión). Recorro la jornada
 * del 20/01/2026 en el mismo orden en que la haría la guardia: ingreso (PI-01), alta de zona (PI-02),
 * importación del histórico (PI-03), sincronización de focos (PI-04 y PI-05), sincronización meteorológica
 * (PI-06), cálculo del índice con sus alertas de riesgo (PI-07), despliegue de recursos (PI-08), cambio del
 * nivel de alerta (PI-09), reportes, altas de activo y recurso y carga manual de meteorología (PI-10) y, al
 * final, aplicación del nivel que sugiere una alerta de riesgo con la notificación de alertas (PI-11),
 * administración de usuarios (PI-12), la cronología de la guardia que junta todos esos hitos (PI-13) y las
 * ubicaciones de recursos y marcas que se ponen desde el mapa (PI-14).
 * PI-08, PI-09 y PI-12 comprueban además que el servicio rechace lo que el rol no permite.</p>
 *
 * <p>El orden importa porque cada prueba deja datos que usa la siguiente (por ejemplo, el índice necesita
 * el histórico y la meteorología ya cargados), por eso fijo el orden con {@code @Order}. Y como verifico
 * cantidades exactas (360 focos leídos, 5 alertas, índice de 79,13 en Caviahue, etc.), la base tiene que
 * estar recién creada: si querés volver a correrlas, recreá la base con ejecutar_pruebas.sh. Cuando MySQL
 * no está levantado o la base ya fue usada, las pruebas se omiten (no fallan), así las unitarias se pueden
 * correr en cualquier máquina.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PruebasIntegracionTest {

    /** Fecha de la jornada de prueba y archivos de datos que uso en lugar de los servicios remotos. */
    private static final LocalDate JORNADA = LocalDate.of(2026, 1, 20);
    private static final Path HISTORICO = Path.of("datos", "focos_historicos_2020_2025.csv");
    private static final FuenteArchivo ARCHIVO = new FuenteArchivo(
            Path.of("datos", "focos_jornada_20260120.csv"), Path.of("datos", "meteo_20260120.csv"));
    /** Ids de los usuarios de sql/02_datos.sql: admin (administrador), mruiz (jefa de guardia) y pnahuel (operador). */
    private static final int ADMINISTRADOR = 1;
    private static final int JEFA_GUARDIA = 2;
    private static final int OPERADOR = 3;

    private final ServicioSincronizacion sincronizacion = new ServicioSincronizacion();

    /**
     * Antes de todo compruebo las dos condiciones para correr estas pruebas. Uso assumeTrue y no assertTrue
     * para que, si no se cumplen, las pruebas queden omitidas en lugar de fallidas. La base recién creada la
     * reconozco porque tiene exactamente los 3 focos manuales que carga sql/02_datos.sql.
     */
    @BeforeAll
    static void verificarBase() throws SQLException {
        assumeTrue(ConexionBD.disponible(), "Base de datos no disponible: se omiten las pruebas de integración.");
        try (Connection cn = ConexionBD.obtener()) {
            assumeTrue(new FocoDAO(cn).contar() == 3,
                    "La base no está recién creada: ejecutar sql/01_esquema.sql y sql/02_datos.sql.");
        }
    }

    @Test
    @Order(1)
    @DisplayName("PI-01 Ingreso con clave incorrecta, usuario inexistente y credenciales válidas")
    void ingreso() throws Exception {
        ServicioAutenticacion auth = new ServicioAutenticacion();
        // Clave incorrecta y usuario inexistente se rechazan igual, con ValidacionException, para no
        // darle pistas a quien intente adivinar usuarios.
        assertThrows(ValidacionException.class, () -> auth.ingresar("pnahuel", "incorrecta"));
        assertThrows(ValidacionException.class, () -> auth.ingresar("nadie", "sarif2026"));
        // Con las credenciales correctas obtengo el usuario con su rol y su nombre completo, que es lo
        // que la aplicación guarda en la sesión.
        Usuario u = auth.ingresar("pnahuel", "sarif2026");
        assertEquals("OPERADOR", u.rol());
        assertEquals("Pedro Nahuel", u.nombreCompleto());
    }

    @Test
    @Order(2)
    @DisplayName("PI-02 Alta transaccional de zona; datos inválidos, nombre repetido y área superpuesta no registran nada")
    void altaDeZona() throws Exception {
        ServicioZonas servicio = new ServicioZonas();
        TipoCombustible pastizal = servicio.tiposCombustible().stream()
                .filter(t -> t.nombre().equals("Pastizal")).findFirst().orElseThrow();
        // El catálogo incluye los combustibles fósiles de la actividad petrolera, con el factor máximo.
        assertEquals(10, servicio.tiposCombustible().size());
        assertEquals(1.0, servicio.tiposCombustible().stream().filter(t -> t.nombre().startsWith("Gas natural"))
                .findFirst().orElseThrow().factorInflamabilidad(), 1e-9);
        int zonasAntes = servicio.listarZonas().size();

        // Alta válida, lejos de las zonas existentes. Aunque la pido con vigilancia activa, tiene que
        // quedar inactiva (la vigilancia se activa aparte, CU04), y en la misma transacción se tienen que
        // registrar su relevamiento inicial y el nivel de alerta NORMAL.
        Zona nueva = new Zona(0, "Zona de integración", "Alta de prueba", -45.0, -44.8, -70.0, -69.8, true);
        int id = servicio.registrarZona(nueva, pastizal, 12, JORNADA, JEFA_GUARDIA);
        try (Connection cn = ConexionBD.obtener()) {
            Zona registrada = new ZonaDAO(cn).listar().stream().filter(z -> z.getId() == id).findFirst().orElseThrow();
            assertFalse(registrada.isVigilanciaActiva());
            assertTrue(new RelevamientoDAO(cn).vigente(id, JORNADA).isPresent());
            assertEquals("NORMAL", new ConfiguracionDAO(cn).nivelAlertaVigente(id).orElseThrow().nombre());
        }

        // Tres altas que se tienen que rechazar: latitudes invertidas, un nombre que ya existe y un
        // rectángulo que se superpone con Caviahue. Al final cuento las zonas para confirmar que solo
        // quedó registrada la válida (los intentos fallidos no dejaron nada a medias).
        Zona invertida = new Zona(0, "Otra zona", null, -44.0, -44.5, -70.0, -69.8, false);
        assertThrows(ValidacionException.class, () -> servicio.registrarZona(invertida, pastizal, 12, JORNADA, JEFA_GUARDIA));
        Zona repetida = new Zona(0, "Villa La Angostura", null, -46.0, -45.8, -70.0, -69.8, false);
        assertThrows(ValidacionException.class, () -> servicio.registrarZona(repetida, pastizal, 12, JORNADA, JEFA_GUARDIA));
        Zona superpuesta = new Zona(0, "Superpuesta con Caviahue", null, -37.90, -37.60, -71.10, -70.80, false);
        assertThrows(ValidacionException.class, () -> servicio.registrarZona(superpuesta, pastizal, 12, JORNADA, JEFA_GUARDIA));
        assertEquals(zonasAntes + 1, servicio.listarZonas().size());

        // Zona poligonal (un triángulo) pegada a la anterior por el sur: solo comparte con ella parte del borde
        // de latitud -45.0, así que no se superpone. Al leerla de la base tiene que volver con sus tres vértices en orden
        // y con el rectángulo que la encierra; un punto del rectángulo que queda fuera del triángulo no es de la zona.
        Zona triangulo = new Zona();
        triangulo.setNombre("Triángulo de integración");
        triangulo.setVertices(List.of(new Vertice(-45.2, -70.0), new Vertice(-45.0, -70.0), new Vertice(-45.0, -69.8)));
        int idTriangulo = servicio.registrarZona(triangulo, pastizal, 12, JORNADA, JEFA_GUARDIA);
        try (Connection cn = ConexionBD.obtener()) {
            Zona leida = new ZonaDAO(cn).listar().stream().filter(z -> z.getId() == idTriangulo).findFirst().orElseThrow();
            assertEquals(triangulo.getVertices(), leida.getVertices());
            assertEquals(-45.2, leida.getLatitudMin(), 1e-9);
            assertTrue(leida.contiene(-45.05, -69.95));
            assertFalse(leida.contiene(-45.15, -69.85));
        }
        // Un polígono que se mete en Caviahue se rechaza igual que un rectángulo.
        Zona poligonoSuperpuesto = new Zona();
        poligonoSuperpuesto.setNombre("Polígono sobre Caviahue");
        poligonoSuperpuesto.setVertices(List.of(new Vertice(-38.0, -71.3), new Vertice(-37.85, -71.1), new Vertice(-38.0, -70.9)));
        assertThrows(ValidacionException.class, () -> servicio.registrarZona(poligonoSuperpuesto, pastizal, 12, JORNADA, JEFA_GUARDIA));

        // Se eliminan las zonas de prueba para no alterar las pruebas siguientes (los vértices se borran en cascada).
        try (Connection cn = ConexionBD.obtener()) {
            new ZonaDAO(cn).eliminar(id);
            new ZonaDAO(cn).eliminar(idTriangulo);
        }
    }

    @Test
    @Order(3)
    @DisplayName("PI-03 Importación histórica: 360 leídos, 257 nuevos, 103 fuera de zonas; la reimportación no duplica")
    void importacionHistorica() throws Exception {
        // El archivo que genera herramientas/GeneradorDatos.java tiene 360 focos: 257 caen en alguna zona
        // y se guardan, y 103 están fuera de todas y se descartan.
        ServicioSincronizacion.Resultado r = sincronizacion.importarHistorico(HISTORICO, OPERADOR);
        assertTrue(r.exitosa());
        assertEquals(360, r.leidos());
        assertEquals(257, r.nuevos());
        assertTrue(r.detalle().contains("fuera de zonas 103"), r.detalle());

        // Si importo el mismo archivo otra vez, no entra ningún foco nuevo: la clave única de la
        // detección hace que los 257 se cuenten como duplicados.
        ServicioSincronizacion.Resultado otra = sincronizacion.importarHistorico(HISTORICO, OPERADOR);
        assertEquals(0, otra.nuevos());
        assertTrue(otra.detalle().contains("duplicados 257"), otra.detalle());
    }

    @Test
    @Order(4)
    @DisplayName("PI-04 Sincronización desde archivo: 3 focos nuevos, 1 duplicado, 5 alertas")
    void sincronizacionFocos() throws Exception {
        // Sincronizo los focos de los últimos 2 días desde el archivo de la jornada. De los 30 focos
        // leídos, 3 son nuevos, 1 es el foco de Aluminé que la guardia ya había cargado a mano en
        // sql/02_datos.sql (duplicado) y 26 no caen en ninguna zona activa.
        ServicioSincronizacion.Resultado r = sincronizacion.sincronizarFocos(ARCHIVO, 2, JORNADA, OPERADOR);
        assertTrue(r.exitosa(), r.detalle());
        assertEquals(30, r.leidos());
        assertEquals(3, r.nuevos());
        assertEquals("Leídos 30, nuevos 3, duplicados 1, fuera de zona 26, alertas 5", r.detalle());
        try (Connection cn = ConexionBD.obtener()) {
            // Los 3 focos nuevos generan 3 alertas de foco en zona y 2 de activo en riesgo. Reviso que
            // estén las dos distancias esperadas: 3,17 km a Barrio Lolog y 2,72 km a Villa Caviahue.
            List<Alerta> alertas = new AlertaDAO(cn).listar();
            assertEquals(5, alertas.size());
            List<Double> distancias = alertas.stream().filter(a -> a.distanciaKm() != null).map(Alerta::distanciaKm).toList();
            assertTrue(distancias.contains(3.17) && distancias.contains(2.72), distancias.toString());
        }
    }

    @Test
    @Order(5)
    @DisplayName("PI-05 Fuente no disponible: sincronización registrada como fallida y focos sin cambios")
    void fuenteNoDisponible() throws Exception {
        // Simulo un servicio caído con una fuente que siempre lanza FuenteNoDisponibleException, así no
        // dependo de cortar la red para probar este caso.
        FuenteDeDatos caida = new FuenteDeDatos() {
            @Override
            public List<FocoCalor> obtenerFocos(Zona zona, int dias, LocalDate hasta) throws FuenteNoDisponibleException {
                throw new FuenteNoDisponibleException("Servicio satelital sin respuesta (prueba).");
            }

            @Override
            public List<RegistroMeteo> obtenerMeteo(Zona zona, LocalDate fecha) throws FuenteNoDisponibleException {
                throw new FuenteNoDisponibleException("Servicio meteorológico sin respuesta (prueba).");
            }

            @Override
            public OrigenDatos getOrigen() {
                return OrigenDatos.REMOTA;
            }
        };
        int focosAntes;
        try (Connection cn = ConexionBD.obtener()) {
            focosAntes = new FocoDAO(cn).contar();
        }
        // La sincronización tiene que terminar sin excepción pero informada como no exitosa, sin agregar
        // focos, y quedar registrada en la auditoría como FALLIDA.
        ServicioSincronizacion.Resultado r = sincronizacion.sincronizarFocos(caida, 2, JORNADA, OPERADOR);
        assertFalse(r.exitosa());
        try (Connection cn = ConexionBD.obtener()) {
            assertEquals(focosAntes, new FocoDAO(cn).contar());
            assertEquals("FALLIDA", new SincronizacionDAO(cn).listarUltimas(1).get(0).resultado());
        }
    }

    @Test
    @Order(6)
    @DisplayName("PI-06 Sincronización meteorológica: 15 registros para 5 zonas y 3 días")
    void sincronizacionMeteo() throws Exception {
        // Del archivo meteorológico se leen 3 días (19, 20 y 21 de enero) para cada una de las 5 zonas
        // con vigilancia activa; Junín de los Andes, inactiva, no se sincroniza.
        ServicioSincronizacion.Resultado r = sincronizacion.sincronizarMeteo(ARCHIVO, JORNADA, OPERADOR);
        assertTrue(r.exitosa(), r.detalle());
        assertEquals(15, r.leidos());

        // El vector del viento se guarda y se lee con el pronóstico de la zona: Caviahue desde el 19/01, con el
        // observado del 20 (y no el pronóstico que ya estaba en 02_datos.sql) y viento del oesnoroeste.
        ServicioPronostico.Pronostico p = new ServicioPronostico().pronostico(4, JORNADA);
        assertEquals(3, p.dias().size());
        RegistroMeteo delDia = p.dias().get(1);
        assertFalse(delDia.esPronostico());
        assertEquals(292, delDia.direccionVientoGrados());
        assertEquals(73.1, delDia.rafagaMaxKmh(), 1e-9);
        assertNull(delDia.humedadSueloM3m3());
        assertTrue(p.dias().get(2).esPronostico());
    }

    @Test
    @Order(7)
    @DisplayName("PI-07 Índice de 5 zonas; el recálculo reemplaza sin duplicar; una fecha sin datos informa las 5 zonas")
    void indiceDeRiesgo() throws Exception {
        ServicioRiesgo servicio = new ServicioRiesgo();
        // Calculo el índice de la jornada con los datos reales de la base y comparo zona por zona con los
        // valores que presento en el informe (Caviahue, la más alta, en nivel EXTREMO con 79,13).
        ServicioRiesgo.ResultadoCalculo r = servicio.calcularIndices(JORNADA);
        assertEquals(5, r.indices().size());
        Map<String, Double> valores = r.indices().stream()
                .collect(Collectors.toMap(IndiceRiesgo::nombreZona, IndiceRiesgo::valorFinal));
        assertEquals(79.13, valores.get("Caviahue - Copahue"), 1e-9);
        assertEquals(73.78, valores.get("San Martín de los Andes - Lolog"), 1e-9);
        assertEquals(70.40, valores.get("Aluminé - Lanín Norte"), 1e-9);
        assertEquals(64.45, valores.get("Villa Pehuenia - Moquehue"), 1e-9);
        assertEquals(41.34, valores.get("Villa La Angostura"), 1e-9);

        // La sincronización meteorológica de PI-06 ya había calculado el índice; que sigan siendo 5 filas
        // muestra que el recálculo reemplaza los índices del día en lugar de duplicarlos.
        try (Connection cn = ConexionBD.obtener()) {
            assertEquals(5, new IndiceDAO(cn).contar());
            // Alertas de riesgo generadas solas al calcular el índice (ya en PI-06): las 4 zonas en ALTO o
            // EXTREMO suben de nivel (no había índice del 19/01) y 3 de ellas tienen un foco confiable. Villa
            // Pehuenia no tiene alerta combinada porque su foco es de confianza baja. El recálculo de arriba
            // no las repite: siguen siendo 7 y por eso r.alertasRiesgo() da 0.
            List<Alerta> riesgo = new AlertaDAO(cn).listar().stream().filter(a -> a.tipo().esDeRiesgo()).toList();
            assertEquals(7, riesgo.size());
            assertEquals(0, r.alertasRiesgo());
            assertEquals(4, riesgo.stream().filter(a -> a.tipo() == TipoAlerta.RIESGO_ELEVADO).count());
            assertTrue(riesgo.stream().noneMatch(a -> a.tipo() == TipoAlerta.FOCO_CON_RIESGO_ALTO
                    && a.nombreZona().equals("Villa Pehuenia - Moquehue")));
            // Caviahue (EXTREMO, vigente ATENCION): el riesgo elevado sugiere ALERTA y el foco, EMERGENCIA.
            Alerta caviahue = riesgo.stream().filter(a -> a.tipo() == TipoAlerta.FOCO_CON_RIESGO_ALTO
                    && a.nombreZona().equals("Caviahue - Copahue")).findFirst().orElseThrow();
            assertEquals("EMERGENCIA", caviahue.nivelSugerido().nombre());
        }
        // Para una fecha sin datos meteorológicos no se calcula nada y se informan las 5 zonas sin datos.
        ServicioRiesgo.ResultadoCalculo sinDatos = servicio.calcularIndices(LocalDate.of(2026, 3, 1));
        assertEquals(0, sinDatos.indices().size());
        assertEquals(5, sinDatos.zonasSinDatos().size());
    }

    @Test
    @Order(8)
    @DisplayName("PI-08 Sugerencia sin el recurso fuera de servicio; la confirmación registra y marca los recursos")
    void despliegue() throws Exception {
        ServicioDespliegue servicio = new ServicioDespliegue();
        // De los 8 recursos de sql/02_datos.sql se sugieren 7: la Brigada Pehuenia está fuera de servicio
        // y no se tiene que proponer. El helicóptero va a Caviahue, la única zona en nivel EXTREMO.
        List<Asignacion> sugerencia = servicio.sugerir(JORNADA);
        assertEquals(7, sugerencia.size());
        assertTrue(sugerencia.stream().noneMatch(a -> a.recurso().denominacion().equals("Brigada Pehuenia")));
        assertTrue(sugerencia.stream().anyMatch(a -> a.recurso().denominacion().equals("Helicóptero HB-1")
                && a.nombreZona().equals("Caviahue - Copahue")));

        // Confirmar es decisión del jefe de guardia (o del administrador): al operador se lo rechaza sin escribir nada.
        assertThrows(ValidacionException.class, () -> servicio.confirmar(sugerencia, OPERADOR, JORNADA));
        try (Connection cn = ConexionBD.obtener()) {
            assertEquals(0, new RecursoDAO(cn).contarAsignaciones());
        }

        // Al confirmar, la jefa de guardia registra las 7 asignaciones y los 7 recursos pasan a ASIGNADO
        // (todo en una sola transacción, CU11).
        servicio.confirmar(sugerencia, JEFA_GUARDIA, JORNADA);
        try (Connection cn = ConexionBD.obtener()) {
            RecursoDAO recursos = new RecursoDAO(cn);
            assertEquals(7, recursos.contarAsignaciones());
            long asignados = recursos.listar().stream().filter(x -> x.estado() == EstadoRecurso.ASIGNADO).count();
            assertEquals(7, asignados);
        }
    }

    @Test
    @Order(9)
    @DisplayName("PI-09 Fundamento vacío y nivel repetido son rechazados; el cambio válido queda en el historial")
    void cambioNivelAlerta() throws Exception {
        ServicioZonas servicio = new ServicioZonas();
        List<NivelAlerta> niveles = servicio.nivelesAlerta();
        NivelAlerta atencion = niveles.stream().filter(n -> n.nombre().equals("ATENCION")).findFirst().orElseThrow();
        NivelAlerta alerta = niveles.stream().filter(n -> n.nombre().equals("ALERTA")).findFirst().orElseThrow();

        // El cambio de nivel lo decide el jefe de guardia: al operador se lo rechaza aunque el cambio sea válido.
        assertThrows(ValidacionException.class,
                () -> servicio.cambiarNivelAlerta(4, alerta, "Índice EXTREMO", OPERADOR));
        assertEquals("ATENCION", servicio.nivelAlertaVigente(4).orElseThrow().nombre());

        // Caviahue (zona 4) está en ATENCION desde el 12/01. Se rechazan un cambio con fundamento en
        // blanco y un "cambio" al mismo nivel que ya tiene.
        assertThrows(ValidacionException.class, () -> servicio.cambiarNivelAlerta(4, alerta, "   ", JEFA_GUARDIA));
        assertThrows(ValidacionException.class,
                () -> servicio.cambiarNivelAlerta(4, atencion, "Se mantiene la atención", JEFA_GUARDIA));
        // El cambio válido a ALERTA, con fundamento, queda en el historial y pasa a ser el nivel vigente.
        servicio.cambiarNivelAlerta(4, alerta, "Índice EXTREMO y foco a 2,72 km de Villa Caviahue", JEFA_GUARDIA);
        assertEquals("ALERTA", servicio.nivelAlertaVigente(4).orElseThrow().nombre());
        // Como el nivel subió, queda una alerta "Nivel de alerta elevado" pendiente para registrar a quién se avisó.
        List<Alerta> elevadas = new ServicioAlertas().listar().stream().filter(a -> a.tipo() == TipoAlerta.NIVEL_ELEVADO).toList();
        assertEquals(1, elevadas.size());
        assertEquals("Caviahue - Copahue", elevadas.get(0).nombreZona());
        assertEquals(EstadoAlerta.PENDIENTE, elevadas.get(0).estado());
        assertTrue(elevadas.get(0).descripcion().contains("de ATENCION a ALERTA"), elevadas.get(0).descripcion());
    }

    @Test
    @Order(10)
    @DisplayName("PI-10 Reportes de la temporada, alta de activo y recurso, y carga manual de meteorología")
    void reportesYAltas() throws Exception {
        // Reportes (CU13) desde el inicio de la temporada hasta la jornada. Caviahue tiene su índice de
        // 79,13 del 20/01 y al menos el foco de alta confianza de esa madrugada.
        ServicioReportes reportes = new ServicioReportes();
        LocalDate inicio = ServicioReportes.inicioTemporada(JORNADA);
        FilaReporteZona caviahue = reportes.historicoPorZona(inicio, JORNADA).stream()
                .filter(f -> f.zona().equals("Caviahue - Copahue")).findFirst().orElseThrow();
        assertTrue(caviahue.focos() >= 1);
        assertEquals(1, caviahue.diasConIndice());
        assertEquals(79.13, caviahue.indiceMaximo(), 1e-9);
        // Desempeño: aparecen los cuatro niveles aunque no todos se hayan dado, se evaluaron los 5 días-zona
        // del 20/01 y el único EXTREMO (Caviahue) tuvo focos ese día.
        List<FilaDesempeno> desempeno = reportes.desempeno(inicio, JORNADA);
        assertEquals(4, desempeno.size());
        assertEquals(5, desempeno.stream().mapToInt(FilaDesempeno::diasZona).sum());
        FilaDesempeno extremo = desempeno.stream().filter(f -> f.nivel().equals("EXTREMO")).findFirst().orElseThrow();
        assertEquals(1, extremo.diasZona());
        assertEquals(1, extremo.diasConFocos());
        // Un período invertido se rechaza sin consultar la base.
        assertThrows(ValidacionException.class, () -> reportes.desempeno(JORNADA, inicio));

        // Alta de activo protegido (CU02): se registra y un segundo alta con el mismo nombre en la zona se rechaza.
        ServicioZonas zonas = new ServicioZonas();
        ActivoProtegido refugio = new ActivoProtegido(0, 4, "Refugio Copahue", "INFRAESTRUCTURA", -37.82, -71.10, 3);
        zonas.registrarActivo(refugio);
        assertTrue(zonas.listarActivos().stream().anyMatch(a -> a.nombre().equals("Refugio Copahue")));
        assertThrows(ValidacionException.class, () -> zonas.registrarActivo(refugio));

        // Alta de recurso (CU03): entra DISPONIBLE; no se puede repetir la denominación ni marcarlo ASIGNADO a mano.
        ServicioRecursos recursos = new ServicioRecursos();
        recursos.registrarRecurso(new Recurso(0, "Brigada Copahue", TipoRecurso.TERRESTRE, 10, null, 4));
        Recurso nuevo = recursos.listar().stream()
                .filter(x -> x.denominacion().equals("Brigada Copahue")).findFirst().orElseThrow();
        assertEquals(EstadoRecurso.DISPONIBLE, nuevo.estado());
        assertThrows(ValidacionException.class,
                () -> recursos.registrarRecurso(new Recurso(0, "Brigada Copahue", TipoRecurso.TERRESTRE, 8, null, null)));
        assertThrows(ValidacionException.class, () -> recursos.cambiarEstado(nuevo, EstadoRecurso.ASIGNADO));

        // Carga manual de meteorología (RFS12): un valor imposible se rechaza; uno válido para el 22/01 se
        // guarda y recalcula el índice de ese día, que solo puede salir para Caviahue (única zona con datos).
        LocalDate dia22 = JORNADA.plusDays(2);
        assertThrows(ValidacionException.class,
                () -> sincronizacion.cargarMeteoManual(new RegistroMeteo(4, dia22, 32, 150, 30, 0, false), OPERADOR));
        ServicioSincronizacion.Resultado r = sincronizacion.cargarMeteoManual(
                new RegistroMeteo(4, dia22, 32, 15, 40, 0, false), OPERADOR);
        assertTrue(r.exitosa(), r.detalle());
        try (Connection cn = ConexionBD.obtener()) {
            List<IndiceRiesgo> indices = new IndiceDAO(cn).listarPorFecha(dia22);
            assertEquals(1, indices.size());
            assertEquals(4, indices.get(0).idZona());
        }
    }

    @Test
    @Order(11)
    @DisplayName("PI-11 Nivel sugerido por una alerta de riesgo y notificación registrada: a quién, medio, quién y cuándo")
    void aplicarNivelSugerido() throws Exception {
        // Aluminé pasó a ALTO con nivel vigente NORMAL, así que su alerta de riesgo elevado sugiere ATENCION.
        ServicioAlertas alertas = new ServicioAlertas();
        Alerta alumine = alertas.listar().stream()
                .filter(a -> a.tipo() == TipoAlerta.RIESGO_ELEVADO && a.nombreZona().equals("Aluminé - Lanín Norte"))
                .findFirst().orElseThrow();
        assertEquals("ATENCION", alumine.nivelSugerido().nombre());
        // Sin fundamento no se aplica (la validación es la del CU12).
        assertThrows(ValidacionException.class, () -> alertas.aplicarSugerencia(alumine, " ", JEFA_GUARDIA));
        // Con fundamento se registra el cambio a nombre de la jefa de guardia y la alerta pasa a NOTIFICADA.
        alertas.aplicarSugerencia(alumine, "Índice ALTO 70,40 (alerta automática)", JEFA_GUARDIA);
        assertEquals("ATENCION", new ServicioZonas().nivelAlertaVigente(2).orElseThrow().nombre());
        // Aplicar la sugerencia no genera otra alerta de nivel elevado: el aviso es la misma alerta de riesgo.
        assertTrue(alertas.listar().stream().noneMatch(a -> a.tipo() == TipoAlerta.NIVEL_ELEVADO && a.idZona() == 2));
        Alerta actualizada = alertas.listar().stream().filter(a -> a.id().equals(alumine.id())).findFirst().orElseThrow();
        assertEquals(EstadoAlerta.NOTIFICADA, actualizada.estado());
        // La notificación queda a nombre de la jefa, con medio SISTEMA porque el aviso es el nivel publicado.
        assertEquals(MedioNotificacion.SISTEMA, actualizada.notificacion().medio());
        assertEquals("Marcela Ruiz", actualizada.notificacion().usuario());
        assertTrue(actualizada.notificacion().destinatario().contains("ATENCION"));
        // Aplicarla de nuevo se rechaza: la zona ya está en ATENCION.
        assertThrows(ValidacionException.class,
                () -> alertas.aplicarSugerencia(actualizada, "Otra vez", JEFA_GUARDIA));

        // Notificación a mano de una alerta pendiente: sin decir a quién se avisó no se puede, ni pasándola
        // a NOTIFICADA por el cambio de estado común; el medio SISTEMA tampoco lo elige el operador.
        Alerta pendiente = alertas.listar().stream().filter(a -> a.estado() == EstadoAlerta.PENDIENTE)
                .findFirst().orElseThrow();
        assertNull(pendiente.notificacion());
        assertThrows(ValidacionException.class, () -> alertas.cambiarEstado(pendiente, EstadoAlerta.NOTIFICADA, OPERADOR));
        assertThrows(ValidacionException.class,
                () -> alertas.notificar(pendiente, " ", MedioNotificacion.RADIO, OPERADOR));
        assertThrows(ValidacionException.class,
                () -> alertas.notificar(pendiente, "Guardia", MedioNotificacion.SISTEMA, OPERADOR));
        // El operador sí puede notificar: queda registrado a quién, por qué medio, quién y cuándo.
        alertas.notificar(pendiente, "  Defensa Civil de la zona  ", MedioNotificacion.RADIO, OPERADOR);
        Alerta notificada = alertas.listar().stream().filter(a -> a.id().equals(pendiente.id())).findFirst().orElseThrow();
        assertEquals(EstadoAlerta.NOTIFICADA, notificada.estado());
        assertEquals("Defensa Civil de la zona", notificada.notificacion().destinatario());
        assertEquals(MedioNotificacion.RADIO, notificada.notificacion().medio());
        assertEquals("Pedro Nahuel", notificada.notificacion().usuario());
        assertTrue(notificada.notificacion().fechaHora() != null);
        // Otro operador con la lista vieja (la alerta todavía PENDIENTE en su pantalla) no la pisa.
        assertThrows(ValidacionException.class,
                () -> alertas.notificar(pendiente, "Otro destinatario", MedioNotificacion.TELEFONO, JEFA_GUARDIA));
        // Cerrarla conserva quién la notificó.
        alertas.cambiarEstado(notificada, EstadoAlerta.CERRADA, OPERADOR);
        // Con la lista vieja (todavía NOTIFICADA) no se puede descartar lo que otro ya cerró.
        assertThrows(ValidacionException.class, () -> alertas.cambiarEstado(notificada, EstadoAlerta.DESCARTADA, JEFA_GUARDIA));
        Alerta cerrada = alertas.listar().stream().filter(a -> a.id().equals(pendiente.id())).findFirst().orElseThrow();
        assertEquals(EstadoAlerta.CERRADA, cerrada.estado());
        assertEquals("Pedro Nahuel", cerrada.notificacion().usuario());
        // El contador de pendientes del aviso cuenta solo las PENDIENTES.
        try (Connection cn = ConexionBD.obtener()) {
            long pendientes = new AlertaDAO(cn).listar().stream().filter(a -> a.estado() == EstadoAlerta.PENDIENTE).count();
            assertEquals(pendientes, alertas.contarPendientes());
        }
    }

    @Test
    @Order(12)
    @DisplayName("PI-12 Usuarios: solo el administrador los gestiona; alta, rol, clave y desactivación")
    void administracionUsuarios() throws Exception {
        ServicioUsuarios usuarios = new ServicioUsuarios();
        ServicioAutenticacion auth = new ServicioAutenticacion();
        // Solo el administrador ve y gestiona usuarios; a los otros roles se los rechaza.
        assertThrows(ValidacionException.class, () -> usuarios.listar(OPERADOR));
        assertThrows(ValidacionException.class, () -> usuarios.listar(JEFA_GUARDIA));
        assertEquals(3, usuarios.listar(ADMINISTRADOR).size());

        // Alta de un operador nuevo: con datos inválidos informa todos los errores juntos; el alta válida
        // permite ingresar con la clave inicial, y el nombre de usuario no se puede repetir.
        ValidacionException invalido = assertThrows(ValidacionException.class, () -> usuarios.crear(
                new Usuario(0, "Juan Pérez", "", "Pérez", "OPERADOR", true), "corta", ADMINISTRADOR));
        assertEquals(3, invalido.getErrores().size());
        Usuario juan = new Usuario(0, "jperez", "Juan", "Pérez", "OPERADOR", true);
        int idJuan = usuarios.crear(juan, "brigada2026", ADMINISTRADOR);
        assertEquals("OPERADOR", auth.ingresar("jperez", "brigada2026").rol());
        assertThrows(ValidacionException.class, () -> usuarios.crear(juan, "brigada2026", ADMINISTRADOR));

        // Cambio de rol: pasa a jefe de guardia y desde ese momento puede decidir niveles (el servicio lee el rol vigente).
        usuarios.cambiarRol(idJuan, Rol.JEFE_GUARDIA, ADMINISTRADOR);
        assertEquals("JEFE_GUARDIA", auth.ingresar("jperez", "brigada2026").rol());

        // Cambio de la clave propia: exige la actual correcta y que la nueva se repita igual.
        assertThrows(ValidacionException.class, () -> usuarios.cambiarMiClave(idJuan, "otra", "nueva2026x", "nueva2026x"));
        assertThrows(ValidacionException.class, () -> usuarios.cambiarMiClave(idJuan, "brigada2026", "nueva2026x", "distinta"));
        usuarios.cambiarMiClave(idJuan, "brigada2026", "nueva2026x", "nueva2026x");
        assertThrows(ValidacionException.class, () -> auth.ingresar("jperez", "brigada2026"));
        auth.ingresar("jperez", "nueva2026x");

        // Restablecer la clave (el administrador, sin la anterior) y desactivar: el desactivado no ingresa.
        usuarios.restablecerClave(idJuan, "temporal2026", ADMINISTRADOR);
        auth.ingresar("jperez", "temporal2026");
        usuarios.cambiarActivo(idJuan, false, ADMINISTRADOR);
        assertThrows(ValidacionException.class, () -> auth.ingresar("jperez", "temporal2026"));

        // El único administrador no puede desactivarse ni quitarse el rol: SARIF quedaría sin administradores.
        assertThrows(ValidacionException.class, () -> usuarios.cambiarActivo(ADMINISTRADOR, false, ADMINISTRADOR));
        assertThrows(ValidacionException.class, () -> usuarios.cambiarRol(ADMINISTRADOR, Rol.OPERADOR, ADMINISTRADOR));
        // Y un usuario desactivado pierde sus permisos aunque su rol los tenga.
        assertThrows(ValidacionException.class, () -> new ServicioZonas().cambiarNivelAlerta(4,
                new ServicioZonas().nivelesAlerta().get(0), "Prueba", idJuan));
    }

    @Test
    @Order(13)
    @DisplayName("PI-13 Cronología de la guardia: alertas, notificación, cierre, niveles y asignaciones en orden")
    void cronologiaGuardia() throws Exception {
        ServicioReportes reportes = new ServicioReportes();
        // Los hitos de las pruebas anteriores se registraron con la hora real; los niveles iniciales, en enero.
        LocalDate desde = LocalDate.of(2025, 7, 1);
        LocalDate hoy = LocalDate.now();
        List<EventoGuardia> eventos = reportes.cronologia(desde, hoy, 0);
        Map<TipoEvento, Long> porTipo = eventos.stream().collect(Collectors.groupingBy(EventoGuardia::tipo, Collectors.counting()));
        // Las 7 asignaciones de PI-08; la notificación y el cierre de PI-11; los cambios de nivel de PI-09 y PI-11.
        assertEquals(7L, porTipo.get(TipoEvento.ASIGNACION));
        assertTrue(porTipo.get(TipoEvento.ALERTA_GENERADA) > 0);
        assertEquals(2L, porTipo.get(TipoEvento.ALERTA_NOTIFICADA));
        assertEquals(1L, porTipo.get(TipoEvento.ALERTA_CERRADA));
        assertTrue(porTipo.get(TipoEvento.CAMBIO_NIVEL) >= 2);
        // En orden de fecha y hora.
        for (int k = 1; k < eventos.size(); k++) {
            assertFalse(eventos.get(k).fechaHora().isBefore(eventos.get(k - 1).fechaHora()), "cronología fuera de orden");
        }
        // La notificación a mano queda con quién avisó, a quién, por qué medio y el tiempo desde la alerta.
        EventoGuardia aviso = eventos.stream().filter(e -> e.tipo() == TipoEvento.ALERTA_NOTIFICADA
                && e.usuario().equals("Pedro Nahuel")).findFirst().orElseThrow();
        assertTrue(aviso.detalle().contains("Defensa Civil de la zona") && aviso.detalle().contains("(Radio)"), aviso.detalle());
        assertTrue(aviso.minutosDesdeAlerta() >= 0);
        // El cambio de Caviahue a ALERTA (PI-09) muestra el nivel nuevo y quién lo decidió.
        EventoGuardia cambio = eventos.stream().filter(e -> e.tipo() == TipoEvento.CAMBIO_NIVEL
                && e.zona().equals("Caviahue - Copahue") && "ALERTA".equals(e.nivelAlerta())).findFirst().orElseThrow();
        assertEquals("Marcela Ruiz", cambio.usuario());
        // Las asignaciones de Caviahue (PI-08) llevan el nivel vigente en ese momento: ATENCION desde enero, o
        // ALERTA si el cambio de PI-09 cayó en el mismo segundo (la base guarda la hora al segundo).
        assertTrue(eventos.stream().filter(e -> e.zona().equals("Caviahue - Copahue") && e.tipo() == TipoEvento.ASIGNACION)
                .allMatch(e -> List.of("ATENCION", "ALERTA").contains(e.nivelAlerta())));

        // El filtro por zona deja solo los hitos de esa zona.
        List<EventoGuardia> alumine = reportes.cronologia(desde, hoy, 2);
        assertFalse(alumine.isEmpty());
        assertTrue(alumine.stream().allMatch(e -> e.zona().equals("Aluminé - Lanín Norte")));
        // Un período sin hitos da una cronología vacía, y el resumen lo refleja.
        List<EventoGuardia> vacio = reportes.cronologia(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 31), 0);
        assertTrue(vacio.isEmpty());
        assertEquals(0, ServicioReportes.resumir(vacio).alertas());
        // El resumen del período completo coincide con el conteo.
        assertEquals(7, ServicioReportes.resumir(eventos).asignaciones());
    }

    @Test
    @Order(14)
    @DisplayName("PI-14 Mapa: ubicar recursos desplegados, poner y quitar marcas, y su registro en la cronología")
    void ubicacionesYMarcas() throws Exception {
        ServicioUbicaciones ubicaciones = new ServicioUbicaciones();
        // Los recursos asignados en PI-08 son los que se pueden ubicar.
        List<Recurso> asignados = ubicaciones.recursosAsignados();
        assertFalse(asignados.isEmpty());
        Recurso desplegado = asignados.get(0);
        // Dos ubicaciones del mismo recurso: quedan las dos, pero el mapa muestra solo la última.
        ubicaciones.ubicarRecurso(desplegado.id(), -37.87, -71.05, null, OPERADOR);
        ubicaciones.ubicarRecurso(desplegado.id(), -37.8420, -71.0600, "Flanco norte", OPERADOR);
        List<PosicionRecurso> delRecurso = ubicaciones.desplegados().stream()
                .filter(p -> p.idRecurso() == desplegado.id()).toList();
        assertEquals(1, delRecurso.size());
        assertEquals(-37.842, delRecurso.get(0).latitud(), 1e-6);
        assertEquals("Caviahue - Copahue", delRecurso.get(0).zona());
        assertEquals("Flanco norte", delRecurso.get(0).observaciones());
        // Un recurso que no está asignado no se ubica.
        new ServicioRecursos().listar().stream().filter(r -> r.estado() != EstadoRecurso.ASIGNADO).findFirst()
                .ifPresent(libre -> assertThrows(ValidacionException.class,
                        () -> ubicaciones.ubicarRecurso(libre.id(), -37.84, -71.06, null, OPERADOR)));

        // Una marca en Zapala, fuera de las zonas: se registra sin zona y con quién la puso.
        int idMarca = ubicaciones.agregarMarca(TipoMarca.PUESTO_COMANDO, "Base operativa Zapala", -38.90, -70.06, JEFA_GUARDIA);
        MarcaMapa marca = ubicaciones.marcas().stream().filter(m -> m.id() == idMarca).findFirst().orElseThrow();
        assertNull(marca.zona());
        assertEquals("Marcela Ruiz", marca.usuario());
        // Un usuario que no existe no puede marcar, y una descripción vacía se rechaza.
        assertThrows(ValidacionException.class, () -> ubicaciones.agregarMarca(TipoMarca.PELIGRO, "Derrumbe", -37.8, -71.0, 9999));
        assertThrows(ValidacionException.class, () -> ubicaciones.agregarMarca(TipoMarca.PELIGRO, " ", -37.8, -71.0, OPERADOR));
        // Quitarla: deja de verse en el mapa y un segundo intento se rechaza.
        ubicaciones.quitarMarca(idMarca, OPERADOR);
        assertTrue(ubicaciones.marcas().stream().noneMatch(m -> m.id() == idMarca));
        assertThrows(ValidacionException.class, () -> ubicaciones.quitarMarca(idMarca, OPERADOR));

        // La cronología de hoy tiene las dos ubicaciones y la marca puesta y quitada, fuera de las zonas.
        ServicioReportes reportes = new ServicioReportes();
        List<EventoGuardia> hoy = reportes.cronologia(LocalDate.now(), LocalDate.now(), 0);
        assertEquals(2, hoy.stream().filter(e -> e.tipo() == TipoEvento.RECURSO_UBICADO).count());
        EventoGuardia agregada = hoy.stream().filter(e -> e.tipo() == TipoEvento.MARCA_AGREGADA).findFirst().orElseThrow();
        assertEquals("Fuera de las zonas", agregada.zona());
        assertTrue(agregada.detalle().startsWith("Puesto de comando: Base operativa Zapala"), agregada.detalle());
        assertEquals(1, hoy.stream().filter(e -> e.tipo() == TipoEvento.MARCA_QUITADA).count());
        // Filtrando por Caviahue quedan las ubicaciones (con el nivel vigente) y no la marca de Zapala.
        List<EventoGuardia> caviahue = reportes.cronologia(LocalDate.now(), LocalDate.now(), 4);
        assertTrue(caviahue.stream().filter(e -> e.tipo() == TipoEvento.RECURSO_UBICADO).allMatch(e -> e.nivelAlerta() != null));
        assertEquals(2, caviahue.stream().filter(e -> e.tipo() == TipoEvento.RECURSO_UBICADO).count());
        assertTrue(caviahue.stream().noneMatch(e -> e.tipo() == TipoEvento.MARCA_AGREGADA));
    }
}
