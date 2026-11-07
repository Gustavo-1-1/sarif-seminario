package sarif.datos;

import sarif.modelo.Asignacion;
import sarif.modelo.EstadoRecurso;
import sarif.modelo.Recurso;
import sarif.modelo.TipoRecurso;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * Acceso a los recursos de combate y a sus asignaciones (CU10 y CU11). Listo los recursos disponibles
 * para armar la sugerencia de despliegue y, cuando el operador la confirma, registro la asignación y
 * cambio el estado del recurso. Las sentencias preparadas y el cierre de recursos los resuelve DaoBase.
 */
public class RecursoDAO extends DaoBase {

    // Dejo el SELECT común en una constante para no repetir las columnas en cada consulta.
    private static final String SELECT = "SELECT id_recurso, denominacion, tipo, dotacion, estado, id_zona_base FROM recurso ";

    // Recibo la conexión por constructor: en CU11 la asignación y el cambio de estado del recurso
    // tienen que ir en la misma transacción, y eso lo maneja el servicio.
    public RecursoDAO(Connection cn) {
        super(cn);
    }

    public List<Recurso> listar() throws SQLException {
        return consultar(SELECT + "ORDER BY id_recurso", RecursoDAO::recurso);
    }

    /** Solo los recursos en estado DISPONIBLE, que son los únicos que puedo sugerir para un despliegue (CU10). */
    public List<Recurso> listarDisponibles() throws SQLException {
        return consultar(SELECT + "WHERE estado = ? ORDER BY id_recurso", RecursoDAO::recurso, EstadoRecurso.DISPONIBLE);
    }

    /** Cambia el estado del recurso (por ejemplo, a ASIGNADO al confirmar la asignación en CU11). */
    public void cambiarEstado(int idRecurso, EstadoRecurso estado) throws SQLException {
        actualizar("UPDATE recurso SET estado = ? WHERE id_recurso = ?", estado, idRecurso);
    }

    /**
     * Registra la asignación confirmada (CU11) con el usuario que la decidió y el índice que la fundamentó.
     * El motivo de la sugerencia lo guardo como observación, así queda escrito por qué se asignó.
     * El índice es una referencia opcional: si no hay, DaoBase lo guarda como NULL.
     */
    public void registrarAsignacion(Asignacion a, int idUsuario, LocalDate fecha) throws SQLException {
        actualizar("INSERT INTO asignacion (id_recurso, id_zona, id_usuario, id_indice, fecha, observaciones) "
                + "VALUES (?, ?, ?, ?, ?, ?)", a.recurso().id(), a.idZona(), idUsuario, a.idIndice(), fecha, a.motivo());
    }

    /** Indica si ya hay un recurso con esa denominación (la base lo impide con un UNIQUE; lo consulto para avisar antes). */
    public boolean existeDenominacion(String denominacion) throws SQLException {
        return existe("SELECT COUNT(*) FROM recurso WHERE denominacion = ?", denominacion.trim());
    }

    /** Da de alta un recurso de combate (CU03). Siempre nace DISPONIBLE; la zona de base es opcional. */
    public void insertar(Recurso r) throws SQLException {
        actualizar("INSERT INTO recurso (denominacion, tipo, dotacion, estado, id_zona_base) VALUES (?, ?, ?, ?, ?)",
                r.denominacion().trim(), r.tipo(), r.dotacion(), EstadoRecurso.DISPONIBLE, r.idZonaBase());
    }

    // Total de asignaciones; lo uso en las pruebas de integración.
    public int contarAsignaciones() throws SQLException {
        return entero("SELECT COUNT(*) FROM asignacion");
    }

    // La zona de base puede ser NULL; getInt devolvería 0, por eso la leo con getObject(..., Integer.class).
    private static Recurso recurso(ResultSet rs) throws SQLException {
        return new Recurso(rs.getInt("id_recurso"), rs.getString("denominacion"),
                TipoRecurso.valueOf(rs.getString("tipo")), rs.getInt("dotacion"),
                EstadoRecurso.valueOf(rs.getString("estado")), rs.getObject("id_zona_base", Integer.class));
    }
}
