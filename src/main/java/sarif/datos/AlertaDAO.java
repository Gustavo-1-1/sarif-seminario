package sarif.datos;

import sarif.modelo.Alerta;
import sarif.modelo.Confianza;
import sarif.modelo.EstadoAlerta;
import sarif.modelo.MedioNotificacion;
import sarif.modelo.NivelAlerta;
import sarif.modelo.Notificacion;
import sarif.modelo.TipoAlerta;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Acceso a la tabla alerta: registro las alertas que genera el sistema (por focos y por índice de
 * riesgo), las listo para la pantalla de alertas, registro su notificación, actualizo su estado y
 * cuento las pendientes para el aviso de la ventana principal.
 * Como en todos los DAO, las sentencias son preparadas (las arma DaoBase con los valores en orden),
 * así no concateno valores en el SQL y evito la inyección SQL.
 */
public class AlertaDAO extends DaoBase {

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

    // Recibo la conexión por constructor en vez de abrirla acá: así el servicio controla la transacción
    // y puede grabar el foco y sus alertas juntos, o revertir todo si algo falla.
    public AlertaDAO(Connection cn) {
        super(cn);
    }

    /**
     * Inserta la alerta y devuelve el id que le asignó la base. La fecha y hora no la mando:
     * la completa MySQL con el valor por defecto de la columna. Cada tipo de alerta usa columnas distintas;
     * las que no corresponden llegan en null y DaoBase las guarda como NULL.
     */
    public int insertar(Alerta a) throws SQLException {
        return (int) insertarConClave("INSERT INTO alerta (id_foco, id_activo, id_indice, tipo, distancia_km, descripcion, "
                        + "estado, id_nivel_alerta_sugerido) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                a.idFoco(), a.idActivo(), a.idIndice(), a.tipo(), decimal(a.distanciaKm(), 2),
                recortar(a.descripcion()), a.estado(), a.nivelSugerido() == null ? null : a.nivelSugerido().id());
    }

    /** Registra la alerta de nivel elevado que sale del cambio de nivel indicado; queda PENDIENTE. */
    public int insertarPorCambioNivel(int idCambio, String descripcion) throws SQLException {
        return (int) insertarConClave("INSERT INTO alerta (id_cambio, tipo, descripcion) VALUES (?, ?, ?)",
                idCambio, TipoAlerta.NIVEL_ELEVADO, recortar(descripcion));
    }

    /**
     * Me dice si ya registré esta alerta de riesgo (mismo índice, tipo y nivel sugerido). Así, al recalcular
     * el índice del día no se repite; pero si el riesgo sube y cambia la sugerencia, sí sale una nueva.
     * Uso el operador {@code <=>} de MySQL porque compara bien los NULL (sin sugerencia).
     */
    public boolean existeDeRiesgo(Alerta a) throws SQLException {
        return existe("SELECT COUNT(*) FROM alerta WHERE id_indice = ? AND tipo = ? AND id_nivel_alerta_sugerido <=> ?",
                a.idIndice(), a.tipo(), a.nivelSugerido() == null ? null : a.nivelSugerido().id());
    }

    /** Devuelve todas las alertas, de la más nueva a la más vieja. */
    public List<Alerta> listar() throws SQLException {
        return consultar(LISTADO + "ORDER BY a.id_alerta DESC", AlertaDAO::alerta);
    }

    /** Alertas registradas después de la indicada (id mayor); las uso para el aviso de alertas nuevas. */
    public List<Alerta> listarPosteriores(int idAlerta) throws SQLException {
        return consultar(LISTADO + "WHERE a.id_alerta > ? ORDER BY a.id_alerta DESC", AlertaDAO::alerta, idAlerta);
    }

    /**
     * Pasa la alerta a NOTIFICADA y guarda quién avisó, a quién y por qué medio; la fecha y hora la pone
     * MySQL. El WHERE exige que siga PENDIENTE: si otro operador la notificó mientras tanto, no la piso y
     * devuelvo false para que el servicio lo informe.
     */
    public boolean notificar(int idAlerta, int idUsuario, String destinatario, MedioNotificacion medio) throws SQLException {
        return actualizar("UPDATE alerta SET estado = 'NOTIFICADA', id_usuario_notifica = ?, fecha_hora_notificacion = NOW(), "
                + "notificado_a = ?, medio_notificacion = ? WHERE id_alerta = ? AND estado = 'PENDIENTE'",
                idUsuario, destinatario, medio, idAlerta) == 1;
    }

    /**
     * Cierra o descarta la alerta y guarda quién lo hizo; la hora la pone MySQL. Acá no valido la
     * transición: eso lo hace el servicio con EstadoAlerta.puedePasarA antes de llamar a este método.
     * El WHERE exige que la alerta siga en el estado que vio el operador, para no pisar un cambio
     * que otro usuario hizo mientras tanto; en ese caso devuelvo false.
     */
    public boolean resolver(int idAlerta, EstadoAlerta anterior, EstadoAlerta nuevo, int idUsuario) throws SQLException {
        return actualizar("UPDATE alerta SET estado = ?, id_usuario_resolucion = ?, fecha_hora_resolucion = NOW() "
                + "WHERE id_alerta = ? AND estado = ?", nuevo, idUsuario, idAlerta, anterior) == 1;
    }

    /** Cantidad de alertas PENDIENTES, para el contador de la pestaña Alertas. */
    public int contarPendientes() throws SQLException {
        return entero("SELECT COUNT(*) FROM alerta WHERE estado = ?", EstadoAlerta.PENDIENTE);
    }

    /** Id de la última alerta registrada (0 si no hay ninguna); me sirve para detectar las nuevas. */
    public int ultimoId() throws SQLException {
        return entero("SELECT COALESCE(MAX(id_alerta), 0) FROM alerta");
    }

    // Cantidad total de alertas; me sirve para verificar lo que se insertó (igual que el contar() de los otros DAO).
    public int contar() throws SQLException {
        return entero("SELECT COUNT(*) FROM alerta");
    }

    // La columna admite 250 caracteres; con nombres de zona largos corto el texto en vez de fallar.
    private static String recortar(String descripcion) {
        return descripcion.substring(0, Math.min(250, descripcion.length()));
    }

    // getInt/getLong/getDouble devuelven 0 si la columna es NULL; con getObject distingo el NULL real.
    private static Alerta alerta(ResultSet rs) throws SQLException {
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
}
