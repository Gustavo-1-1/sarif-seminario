package sarif.servicio;

import sarif.datos.AlertaDAO;
import sarif.datos.ConexionBD;
import sarif.datos.ConfiguracionDAO;
import sarif.datos.RelevamientoDAO;
import sarif.datos.ZonaDAO;
import sarif.modelo.ActivoProtegido;
import sarif.modelo.FilaTablero;
import sarif.modelo.NivelAlerta;
import sarif.modelo.Permiso;
import sarif.modelo.Relevamiento;
import sarif.modelo.TipoCombustible;
import sarif.modelo.Zona;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Agrupa los casos de uso sobre las zonas: CU01 Registrar zona, CU02 Registrar activo protegido,
 * CU04/CU05 Activar y desactivar vigilancia, CU06 Tablero, CU12 Actualizar nivel de alerta y los
 * relevamientos de combustible posteriores al alta (RFS21). Las validaciones de negocio las hago acá
 * (y no en el controlador) para que valgan sin importar desde qué pantalla se llame.
 */
public class ServicioZonas {

    /** Tipos de activo protegido; son los mismos valores del ENUM de la tabla activo_protegido. */
    public static final List<String> TIPOS_ACTIVO = List.of("POBLACION", "INFRAESTRUCTURA", "AREA_PROTEGIDA", "PRODUCTIVO");

    /**
     * CU01: el alta de la zona, su primer relevamiento y su nivel de alerta inicial se confirman juntos
     * o no se registra nada, así nunca queda una zona sin combustible o sin nivel. La zona queda con la
     * vigilancia desactivada hasta que el operador la active (CU04).
     */
    public int registrarZona(Zona zona, TipoCombustible tipo, double carga, LocalDate fechaRelevamiento, int idUsuario)
            throws SQLException, ValidacionException {
        // 1) Junto todos los errores de los datos ingresados (los de la zona y los del relevamiento)
        //    para mostrarlos de una sola vez, en lugar de hacer corregir al operador de a uno.
        List<String> errores = new ArrayList<>(zona.validar());
        if (tipo == null) {
            errores.add("Debe indicar el tipo de combustible.");
        }
        if (carga <= 0) {
            errores.add("La carga de combustible debe ser mayor que cero.");
        }
        if (fechaRelevamiento == null) {
            errores.add("Debe indicar la fecha del relevamiento.");
        }
        if (!errores.isEmpty()) {
            throw new ValidacionException(errores);
        }

        try (Connection cn = ConexionBD.obtener()) {
            // 2) Abro la transacción: zona, relevamiento y nivel inicial van juntos.
            cn.setAutoCommit(false);
            try {
                // 3) Controles que necesitan la base: nombre repetido y superposición con otra zona.
                ZonaDAO zonas = new ZonaDAO(cn);
                if (zonas.existeNombre(zona.getNombre())) {
                    throw new ValidacionException("Ya existe una zona con el nombre " + zona.getNombre() + ".");
                }
                if (zonas.seSuperpone(zona)) {
                    throw new ValidacionException("El área se superpone con una zona ya registrada.");
                }
                // 4) Inserto la zona con la vigilancia apagada.
                zona.setVigilanciaActiva(false);
                int id = zonas.insertar(zona);
                // 5) Registro el relevamiento inicial de combustible (lo usa la componente C del índice).
                new RelevamientoDAO(cn).insertar(id, tipo.id(), carga, fechaRelevamiento, idUsuario, "Relevamiento inicial");
                // 6) Le asigno el primer nivel de alerta de la lista (el más bajo) como nivel inicial.
                ConfiguracionDAO conf = new ConfiguracionDAO(cn);
                NivelAlerta inicial = conf.nivelesAlerta().get(0);
                conf.registrarCambioNivel(id, inicial.id(), "Alta de la zona", idUsuario);
                // 7) Confirmo todo y recién ahí le pongo el id al objeto, para no dejarlo con un id que se revirtió.
                cn.commit();
                zona.setId(id);
                return id;
            } catch (SQLException | ValidacionException e) {
                // Si algo falló, revierto el alta completa.
                cn.rollback();
                throw e;
            }
        }
    }

    /** CU04 y CU05: activo o desactivo la vigilancia de la zona (solo las zonas vigiladas generan alertas del CU14). */
    public void cambiarVigilancia(int idZona, boolean activa) throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            new ZonaDAO(cn).cambiarVigilancia(idZona, activa);
        }
    }

    /**
     * CU12: cambio el nivel de alerta de la zona. El fundamento es obligatorio (hasta 250 caracteres)
     * porque queda como registro de por qué se tomó la decisión, y el nivel debe ser distinto del
     * vigente (flujo alternativo S5). Es una decisión del jefe de guardia, así que controlo el permiso antes
     * que nada (también lo usa "Aplicar nivel sugerido" de la pestaña Alertas).
     * <p>
     * Si el cambio sube el nivel, genero además una alerta NIVEL_ELEVADO, PENDIENTE, para que la guardia
     * registre a quién avisó: subir el nivel de una zona es justamente algo que hay que comunicar. El cambio
     * y la alerta van en la misma transacción.
     */
    public void cambiarNivelAlerta(int idZona, NivelAlerta nuevo, String fundamento, int idUsuario)
            throws SQLException, ValidacionException {
        cambiarNivelAlerta(idZona, nuevo, fundamento, idUsuario, true);
    }

    /**
     * Versión para "Aplicar nivel sugerido": ahí el aviso ya es la alerta de riesgo que sugirió el nivel (el
     * servicio de alertas la deja NOTIFICADA), así que no genero otra alerta por el mismo cambio.
     */
    void cambiarNivelAlerta(int idZona, NivelAlerta nuevo, String fundamento, int idUsuario, boolean alertarSiSube)
            throws SQLException, ValidacionException {
        Permisos.exigir(idUsuario, Permiso.CAMBIAR_NIVEL_ALERTA);
        if (nuevo == null) {
            throw new ValidacionException("Debe elegir el nuevo nivel de alerta.");
        }
        if (fundamento == null || fundamento.isBlank()) {
            throw new ValidacionException("El fundamento del cambio es obligatorio.");
        }
        if (fundamento.trim().length() > 250) {
            throw new ValidacionException("El fundamento no puede superar los 250 caracteres.");
        }
        try (Connection cn = ConexionBD.obtener()) {
            ConfiguracionDAO conf = new ConfiguracionDAO(cn);
            // Flujo S5: si elige el mismo nivel que ya tiene, no registro un cambio que no es tal.
            Optional<NivelAlerta> vigente = conf.nivelAlertaVigente(idZona);
            if (vigente.isPresent() && vigente.get().id() == nuevo.id()) {
                throw new ValidacionException("La zona ya se encuentra en el nivel " + nuevo.nombre() + ".");
            }
            cn.setAutoCommit(false);
            try {
                int idCambio = conf.registrarCambioNivel(idZona, nuevo.id(), fundamento, idUsuario);
                if (alertarSiSube && subeDeNivel(vigente.orElse(null), nuevo)) {
                    String zona = new ZonaDAO(cn).listar().stream().filter(z -> z.getId() == idZona)
                            .map(Zona::getNombre).findFirst().orElse("Zona " + idZona);
                    new AlertaDAO(cn).insertarPorCambioNivel(idCambio, descripcionNivelElevado(zona,
                            vigente.map(NivelAlerta::nombre).orElse(null), nuevo.nombre(), fundamento.trim()));
                }
                cn.commit();
            } catch (SQLException e) {
                cn.rollback();
                throw e;
            }
        }
    }

    /** Sube si el nivel nuevo es de mayor orden que el vigente; una zona sin nivel "sube" a cualquiera salvo al primero. */
    static boolean subeDeNivel(NivelAlerta vigente, NivelAlerta nuevo) {
        return vigente == null ? nuevo.orden() > 1 : nuevo.orden() > vigente.orden();
    }

    static String descripcionNivelElevado(String zona, String anterior, String nuevo, String fundamento) {
        return zona + ": el nivel de alerta subió " + (anterior == null ? "" : "de " + anterior + " ") + "a " + nuevo
                + ". Fundamento: " + fundamento;
    }

    public List<Zona> listarZonas() throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return new ZonaDAO(cn).listar();
        }
    }

    /** CU06: devuelvo el tablero de zonas con el índice y los niveles vigentes de cada una. */
    public List<FilaTablero> tablero() throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return new ZonaDAO(cn).tablero();
        }
    }

    /** Tipos de combustible para el combo del alta de zona. */
    public List<TipoCombustible> tiposCombustible() throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return new ConfiguracionDAO(cn).tiposCombustible();
        }
    }

    /** Niveles de alerta posibles, para el combo del CU12. */
    public List<NivelAlerta> nivelesAlerta() throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return new ConfiguracionDAO(cn).nivelesAlerta();
        }
    }

    public Optional<NivelAlerta> nivelAlertaVigente(int idZona) throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return new ConfiguracionDAO(cn).nivelAlertaVigente(idZona);
        }
    }

    /**
     * CU02: registro un activo protegido. Además de los datos obligatorios, controlo que el punto caiga
     * dentro de la zona elegida: si no, las alertas del CU15 nunca lo tendrían en cuenta como corresponde.
     */
    public void registrarActivo(ActivoProtegido activo) throws SQLException, ValidacionException {
        try (Connection cn = ConexionBD.obtener()) {
            ZonaDAO zonas = new ZonaDAO(cn);
            Zona zona = zonas.listar().stream().filter(z -> z.getId() == activo.idZona()).findFirst().orElse(null);
            List<String> errores = validarActivo(activo, zona);
            if (!errores.isEmpty()) {
                throw new ValidacionException(errores);
            }
            if (zonas.existeActivo(activo.idZona(), activo.nombre())) {
                throw new ValidacionException("La zona ya tiene un activo llamado " + activo.nombre().trim() + ".");
            }
            zonas.insertarActivo(activo);
        }
    }

    /**
     * Reglas del activo protegido. La dejo estática y sin base para probarla con JUnit. La distancia de
     * alerta la limito a 50 km porque más lejos ya no tiene sentido hablar de un foco "cercano".
     */
    public static List<String> validarActivo(ActivoProtegido a, Zona zona) {
        List<String> errores = new ArrayList<>();
        if (zona == null) {
            errores.add("Debe elegir la zona del activo.");
        }
        if (a.nombre() == null || a.nombre().isBlank()) {
            errores.add("El nombre del activo es obligatorio.");
        } else if (a.nombre().trim().length() > 80) {
            errores.add("El nombre no puede superar los 80 caracteres.");
        }
        if (a.tipo() == null || !TIPOS_ACTIVO.contains(a.tipo())) {
            errores.add("Debe elegir el tipo de activo.");
        }
        if (a.distanciaAlertaKm() <= 0 || a.distanciaAlertaKm() > 50) {
            errores.add("La distancia de alerta debe ser mayor que 0 y de hasta 50 km.");
        }
        if (zona != null && !zona.contiene(a.latitud(), a.longitud())) {
            errores.add("La ubicación del activo no está dentro de la zona " + zona.getNombre() + ".");
        }
        return errores;
    }

    /** Activos protegidos de todas las zonas, para la pantalla de activos. */
    public List<ActivoProtegido> listarActivos() throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return new ZonaDAO(cn).listarActivos();
        }
    }

    /**
     * RFS21: registro un relevamiento de combustible nuevo de una zona existente. Nunca piso el anterior:
     * agrego una fila, así queda el historial y el índice de cada día usa el relevamiento vigente en esa fecha.
     */
    public void registrarRelevamiento(int idZona, TipoCombustible tipo, double carga, LocalDate fecha, String observaciones,
                                      int idUsuario) throws SQLException, ValidacionException {
        List<String> errores = new ArrayList<>();
        if (tipo == null) {
            errores.add("Debe indicar el tipo de combustible.");
        }
        if (carga <= 0 || carga > 200) {
            errores.add("La carga de combustible debe ser mayor que cero y de hasta 200 t/ha.");
        }
        if (fecha == null) {
            errores.add("Debe indicar la fecha del relevamiento.");
        } else if (fecha.isAfter(LocalDate.now())) {
            errores.add("La fecha del relevamiento no puede ser posterior a hoy.");
        }
        if (observaciones != null && observaciones.trim().length() > 250) {
            errores.add("Las observaciones no pueden superar los 250 caracteres.");
        }
        if (!errores.isEmpty()) {
            throw new ValidacionException(errores);
        }
        try (Connection cn = ConexionBD.obtener()) {
            new RelevamientoDAO(cn).insertar(idZona, tipo.id(), carga, fecha, idUsuario,
                    observaciones == null || observaciones.isBlank() ? null : observaciones.trim());
        }
    }

    /** Relevamiento de combustible vigente de la zona a una fecha (el último que no sea posterior). */
    public Optional<Relevamiento> combustibleVigente(int idZona, LocalDate fecha) throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return new RelevamientoDAO(cn).vigente(idZona, fecha);
        }
    }
}
