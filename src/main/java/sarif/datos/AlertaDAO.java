package sarif.datos;

import sarif.modelo.Alerta;
import sarif.modelo.Confianza;
import sarif.modelo.EstadoAlerta;
import sarif.modelo.MedioNotificacion;
import sarif.modelo.NivelAlerta;
import sarif.modelo.Notificacion;
import sarif.modelo.TipoAlerta;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Acceso a la tabla alerta: registro las alertas que genera el sistema (por focos y por índice de
 * riesgo), las listo para la pantalla de alertas, registro su notificación, actualizo su estado y
 * cuento las pendientes para el aviso de la ventana principal.
 * Como en todos los DAO, uso sentencias preparadas (PreparedStatement) para no armar SQL concatenando
 * valores y así evitar inyección SQL.
 */
public class AlertaDAO {

    /**
     * Consulta base del listado. La zona la saco del foco, del índice o del cambio de nivel (la alerta no
     * la guarda), por eso los tres LEFT JOIN y el COALESCE: cada tipo de alerta tiene solo uno de los tres.
     * También traigo el activo comprometido, el nivel de alerta sugerido y, si ya se
     * notificó, quién lo hizo (LEFT JOIN con usuario porque las pendientes no tienen).
     */
    private static final String LISTADO = "SELECT a.id_alerta, a.id_foco, a.id_activo, a.id_indice, a.tipo, a.fecha_hora, "
            + "a.distancia_km, a.descripcion, a.estado, na.id_nivel_alerta, na.nombre AS nivel_sugerido, na.orden, "
            + "z.id_zona, z.nombre AS zona, ap.nombre AS activo, f.confianza, "
            + "a.notificado_a, a.medio_notificacion, a.fecha_hora_notificacion, "
            + "CONCAT(u.nombre, ' ', u.apellido) AS notifico "
            + "FROM alerta a LEFT JOIN foco_calor f ON f.id_foco = a.id_foco "
            + "LEFT JOIN indice_riesgo i ON i.id_indice = a.id_indice "
            + "LEFT JOIN cambio_nivel_alerta c ON c.id_cambio = a.id_cambio "
            + "JOIN zona_vigilancia z ON z.id_zona = COALESCE(f.id_zona, i.id_zona, c.id_zona) "
            + "LEFT JOIN activo_protegido ap ON ap.id_activo = a.id_activo "
            + "LEFT JOIN nivel_alerta na ON na.id_nivel_alerta = a.id_nivel_alerta_sugerido "
            + "LEFT JOIN usuario u ON u.id_usuario = a.id_usuario_notifica ";

    private final Connection cn;

    // Recibo la conexión por constructor en vez de abrirla acá: así el servicio controla la transacción
    // y puede grabar el foco y sus alertas juntos, o revertir todo si algo falla.
    public AlertaDAO(Connection cn) {
        this.cn = cn;
    }

    /**
     * Inserta la alerta y devuelve el id que le asignó la base. La fecha y hora no la mando:
     * la completa MySQL con el valor por defecto de la columna.
     */
    public int insertar(Alerta a) throws SQLException {
        String sql = "INSERT INTO alerta (id_foco, id_activo, id_indice, tipo, distancia_km, descripcion, estado, "
                + "id_nivel_alerta_sugerido) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        // RETURN_GENERATED_KEYS me permite recuperar el id autoincremental con getGeneratedKeys().
        try (PreparedStatement ps = cn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            // Cada tipo de alerta usa columnas distintas; las que no corresponden van como NULL explícito.
            if (a.idFoco() == null) {
                ps.setNull(1, Types.BIGINT);
            } else {
                ps.setLong(1, a.idFoco());
            }
            if (a.idActivo() == null) {
                ps.setNull(2, Types.INTEGER);
            } else {
                ps.setInt(2, a.idActivo());
            }
            if (a.idIndice() == null) {
                ps.setNull(3, Types.INTEGER);
            } else {
                ps.setInt(3, a.idIndice());
            }
            ps.setString(4, a.tipo().name());
            if (a.distanciaKm() == null) {
                ps.setNull(5, Types.DECIMAL);
            } else {
                ps.setBigDecimal(5, ConexionBD.decimal(a.distanciaKm(), 2));
            }
            // La columna admite 250 caracteres; con nombres de zona largos corto el texto en vez de fallar.
            ps.setString(6, a.descripcion().substring(0, Math.min(250, a.descripcion().length())));
            ps.setString(7, a.estado().name());
            if (a.nivelSugerido() == null) {
                ps.setNull(8, Types.TINYINT);
            } else {
                ps.setInt(8, a.nivelSugerido().id());
            }
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Registra la alerta de nivel elevado que sale del cambio de nivel indicado; queda PENDIENTE. */
    public int insertarPorCambioNivel(int idCambio, String descripcion) throws SQLException {
        String sql = "INSERT INTO alerta (id_cambio, tipo, descripcion) VALUES (?, 'NIVEL_ELEVADO', ?)";
        try (PreparedStatement ps = cn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, idCambio);
            ps.setString(2, descripcion.substring(0, Math.min(250, descripcion.length())));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Me dice si ya registré esta alerta de riesgo (mismo índice, tipo y nivel sugerido). Así, al recalcular
     * el índice del día no se repite; pero si el riesgo sube y cambia la sugerencia, sí sale una nueva.
     * Uso el operador {@code <=>} de MySQL porque compara bien los NULL (sin sugerencia).
     */
    public boolean existeDeRiesgo(Alerta a) throws SQLException {
        String sql = "SELECT COUNT(*) FROM alerta WHERE id_indice = ? AND tipo = ? AND id_nivel_alerta_sugerido <=> ?";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, a.idIndice());
            ps.setString(2, a.tipo().name());
            if (a.nivelSugerido() == null) {
                ps.setNull(3, Types.TINYINT);
            } else {
                ps.setInt(3, a.nivelSugerido().id());
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** Devuelve todas las alertas, de la más nueva a la más vieja. */
    public List<Alerta> listar() throws SQLException {
        return consultar(LISTADO + "ORDER BY a.id_alerta DESC", 0);
    }

    /** Alertas registradas después de la indicada (id mayor); las uso para el aviso de alertas nuevas. */
    public List<Alerta> listarPosteriores(int idAlerta) throws SQLException {
        return consultar(LISTADO + "WHERE a.id_alerta > ? ORDER BY a.id_alerta DESC", idAlerta);
    }

    private List<Alerta> consultar(String sql, int parametro) throws SQLException {
        List<Alerta> lista = new ArrayList<>();
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            if (sql.contains("?")) {
                ps.setInt(1, parametro);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(leer(rs));
                }
            }
        }
        return lista;
    }

    // getInt/getLong/getDouble devuelven 0 si la columna es NULL; con getObject distingo el NULL real.
    private Alerta leer(ResultSet rs) throws SQLException {
        Long idFoco = rs.getObject("id_foco") == null ? null : rs.getLong("id_foco");
        Integer idActivo = rs.getObject("id_activo") == null ? null : rs.getInt("id_activo");
        Integer idIndice = rs.getObject("id_indice") == null ? null : rs.getInt("id_indice");
        Double distancia = rs.getObject("distancia_km") == null ? null : rs.getDouble("distancia_km");
        NivelAlerta sugerido = rs.getObject("id_nivel_alerta") == null ? null
                : new NivelAlerta(rs.getInt("id_nivel_alerta"), rs.getString("nivel_sugerido"), rs.getInt("orden"));
        String confianza = rs.getString("confianza");
        Notificacion notificacion = rs.getString("medio_notificacion") == null ? null
                : new Notificacion(rs.getString("notificado_a"), MedioNotificacion.valueOf(rs.getString("medio_notificacion")),
                        rs.getString("notifico"), rs.getObject("fecha_hora_notificacion", LocalDateTime.class));
        return new Alerta(rs.getInt("id_alerta"), idFoco, idActivo, idIndice, TipoAlerta.valueOf(rs.getString("tipo")),
                rs.getObject("fecha_hora", LocalDateTime.class), distancia, rs.getString("descripcion"),
                EstadoAlerta.valueOf(rs.getString("estado")), sugerido, rs.getInt("id_zona"), rs.getString("zona"),
                rs.getString("activo"), confianza == null ? null : Confianza.valueOf(confianza), notificacion);
    }

    /**
     * Pasa la alerta a NOTIFICADA y guarda quién avisó, a quién y por qué medio; la fecha y hora la pone
     * MySQL. El WHERE exige que siga PENDIENTE: si otro operador la notificó mientras tanto, no la piso y
     * devuelvo false para que el servicio lo informe.
     */
    public boolean notificar(int idAlerta, int idUsuario, String destinatario, MedioNotificacion medio) throws SQLException {
        String sql = "UPDATE alerta SET estado = 'NOTIFICADA', id_usuario_notifica = ?, fecha_hora_notificacion = NOW(), "
                + "notificado_a = ?, medio_notificacion = ? WHERE id_alerta = ? AND estado = 'PENDIENTE'";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, idUsuario);
            ps.setString(2, destinatario);
            ps.setString(3, medio.name());
            ps.setInt(4, idAlerta);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Cierra o descarta la alerta y guarda quién lo hizo; la hora la pone MySQL. Acá no valido la
     * transición: eso lo hace el servicio con EstadoAlerta.puedePasarA antes de llamar a este método.
     * El WHERE exige que la alerta siga en el estado que vio el operador, para no pisar un cambio
     * que otro usuario hizo mientras tanto; en ese caso devuelvo false.
     */
    public boolean resolver(int idAlerta, EstadoAlerta anterior, EstadoAlerta nuevo, int idUsuario) throws SQLException {
        String sql = "UPDATE alerta SET estado = ?, id_usuario_resolucion = ?, fecha_hora_resolucion = NOW() "
                + "WHERE id_alerta = ? AND estado = ?";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, nuevo.name());
            ps.setInt(2, idUsuario);
            ps.setInt(3, idAlerta);
            ps.setString(4, anterior.name());
            return ps.executeUpdate() == 1;
        }
    }

    /** Cantidad de alertas PENDIENTES, para el contador de la pestaña Alertas. */
    public int contarPendientes() throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement("SELECT COUNT(*) FROM alerta WHERE estado = 'PENDIENTE'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Id de la última alerta registrada (0 si no hay ninguna); me sirve para detectar las nuevas. */
    public int ultimoId() throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement("SELECT COALESCE(MAX(id_alerta), 0) FROM alerta");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // Cantidad total de alertas; me sirve para verificar lo que se insertó (igual que el contar() de los otros DAO).
    public int contar() throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement("SELECT COUNT(*) FROM alerta"); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
