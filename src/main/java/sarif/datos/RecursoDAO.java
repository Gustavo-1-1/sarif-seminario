package sarif.datos;

import sarif.modelo.Asignacion;
import sarif.modelo.EstadoRecurso;
import sarif.modelo.Recurso;
import sarif.modelo.TipoRecurso;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Acceso a los recursos de combate y a sus asignaciones (CU10 y CU11). Listo los recursos disponibles
 * para armar la sugerencia de despliegue y, cuando el operador la confirma, registro la asignación y
 * cambio el estado del recurso. Uso sentencias preparadas para evitar inyección SQL.
 */
public class RecursoDAO {

    // Dejo el SELECT común en una constante para no repetir las columnas en cada consulta.
    private static final String SELECT = "SELECT id_recurso, denominacion, tipo, dotacion, estado, id_zona_base FROM recurso ";

    private final Connection cn;

    // Recibo la conexión por constructor: en CU11 la asignación y el cambio de estado del recurso
    // tienen que ir en la misma transacción, y eso lo maneja el servicio.
    public RecursoDAO(Connection cn) {
        this.cn = cn;
    }

    public List<Recurso> listar() throws SQLException {
        return consultar(SELECT + "ORDER BY id_recurso");
    }

    /** Solo los recursos en estado DISPONIBLE, que son los únicos que puedo sugerir para un despliegue (CU10). */
    public List<Recurso> listarDisponibles() throws SQLException {
        return consultar(SELECT + "WHERE estado = 'DISPONIBLE' ORDER BY id_recurso");
    }

    /** Cambia el estado del recurso (por ejemplo, a ASIGNADO al confirmar la asignación en CU11). */
    public void cambiarEstado(int idRecurso, EstadoRecurso estado) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement("UPDATE recurso SET estado = ? WHERE id_recurso = ?")) {
            ps.setString(1, estado.name());
            ps.setInt(2, idRecurso);
            ps.executeUpdate();
        }
    }

    /**
     * Registra la asignación confirmada (CU11) con el usuario que la decidió y el índice que la fundamentó.
     * El motivo de la sugerencia lo guardo como observación, así queda escrito por qué se asignó.
     */
    public void registrarAsignacion(Asignacion a, int idUsuario, LocalDate fecha) throws SQLException {
        String sql = "INSERT INTO asignacion (id_recurso, id_zona, id_usuario, id_indice, fecha, observaciones) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, a.recurso().id());
            ps.setInt(2, a.idZona());
            ps.setInt(3, idUsuario);
            // El índice es una referencia opcional: si no hay, guardo NULL.
            if (a.idIndice() == null) {
                ps.setNull(4, Types.INTEGER);
            } else {
                ps.setInt(4, a.idIndice());
            }
            ps.setObject(5, fecha);
            ps.setString(6, a.motivo());
            ps.executeUpdate();
        }
    }

    /** Indica si ya hay un recurso con esa denominación (la base lo impide con un UNIQUE; lo consulto para avisar antes). */
    public boolean existeDenominacion(String denominacion) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement("SELECT COUNT(*) FROM recurso WHERE denominacion = ?")) {
            ps.setString(1, denominacion.trim());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** Da de alta un recurso de combate (CU03). Siempre nace DISPONIBLE; la zona de base es opcional. */
    public void insertar(Recurso r) throws SQLException {
        String sql = "INSERT INTO recurso (denominacion, tipo, dotacion, estado, id_zona_base) VALUES (?, ?, ?, 'DISPONIBLE', ?)";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, r.denominacion().trim());
            ps.setString(2, r.tipo().name());
            ps.setInt(3, r.dotacion());
            if (r.idZonaBase() == null) {
                ps.setNull(4, Types.INTEGER);
            } else {
                ps.setInt(4, r.idZonaBase());
            }
            ps.executeUpdate();
        }
    }

    // Total de asignaciones; lo uso en las pruebas de integración.
    public int contarAsignaciones() throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement("SELECT COUNT(*) FROM asignacion"); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // Método común para los dos listados. Acá concateno el SQL, pero solo con texto fijo mío
    // (nunca con datos que escribe el usuario), así que no hay riesgo de inyección.
    private List<Recurso> consultar(String sql) throws SQLException {
        List<Recurso> lista = new ArrayList<>();
        try (PreparedStatement ps = cn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                // La zona de base puede ser NULL; getInt devolvería 0, por eso consulto wasNull().
                int base = rs.getInt("id_zona_base");
                Integer idBase = rs.wasNull() ? null : base;
                lista.add(new Recurso(rs.getInt("id_recurso"), rs.getString("denominacion"),
                        TipoRecurso.valueOf(rs.getString("tipo")), rs.getInt("dotacion"),
                        EstadoRecurso.valueOf(rs.getString("estado")), idBase));
            }
        }
        return lista;
    }
}
