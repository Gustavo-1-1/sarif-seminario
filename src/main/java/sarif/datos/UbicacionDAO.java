package sarif.datos;

import sarif.modelo.MarcaMapa;
import sarif.modelo.PosicionRecurso;
import sarif.modelo.TipoMarca;
import sarif.modelo.TipoRecurso;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Acceso a lo que la guardia marca en el mapa: las ubicaciones de los recursos desplegados (posicion_recurso)
 * y las marcas operativas (marca_mapa). Como en los demás DAO, las sentencias son preparadas y recibo la conexión
 * del servicio. La zona es opcional en los dos casos (una marca puede quedar fuera de las zonas): si no viene,
 * DaoBase la guarda como NULL.
 */
public class UbicacionDAO extends DaoBase {

    public UbicacionDAO(Connection cn) {
        super(cn);
    }

    /**
     * Agrega una ubicación del recurso solo si está ASIGNADO; la fecha y hora la pone MySQL. El control del
     * estado va en la misma sentencia (INSERT ... SELECT) para que no se cuele un recurso que otro puesto
     * liberó un instante antes. Devuelve false si el recurso no existe o no está asignado.
     */
    public boolean insertarPosicion(int idRecurso, Integer idZona, double latitud, double longitud, String observaciones,
                                    int idUsuario) throws SQLException {
        return actualizar("INSERT INTO posicion_recurso (id_recurso, id_zona, latitud, longitud, observaciones, id_usuario) "
                        + "SELECT id_recurso, ?, ?, ?, ?, ? FROM recurso WHERE id_recurso = ? AND estado = 'ASIGNADO'",
                idZona, ConexionBD.decimal(latitud, 5), ConexionBD.decimal(longitud, 5), observaciones, idUsuario,
                idRecurso) == 1;
    }

    /**
     * Última ubicación de cada recurso que sigue ASIGNADO. Uso el id más alto de cada recurso como "última"
     * (los id crecen con cada ubicación nueva); cuando el recurso vuelve a DISPONIBLE deja de aparecer.
     */
    public List<PosicionRecurso> ultimasDeDesplegados() throws SQLException {
        return consultar("SELECT r.id_recurso, r.denominacion, r.tipo, r.dotacion, p.latitud, p.longitud, z.nombre AS zona, "
                + "p.fecha_hora, CONCAT(u.nombre, ' ', u.apellido) AS usuario, p.observaciones "
                + "FROM posicion_recurso p JOIN recurso r ON r.id_recurso = p.id_recurso "
                + "LEFT JOIN zona_vigilancia z ON z.id_zona = p.id_zona "
                + "JOIN usuario u ON u.id_usuario = p.id_usuario "
                + "WHERE r.estado = 'ASIGNADO' "
                + "AND p.id_posicion = (SELECT MAX(p2.id_posicion) FROM posicion_recurso p2 WHERE p2.id_recurso = p.id_recurso) "
                + "ORDER BY r.denominacion", rs -> new PosicionRecurso(rs.getInt("id_recurso"), rs.getString("denominacion"),
                TipoRecurso.valueOf(rs.getString("tipo")), rs.getInt("dotacion"), rs.getDouble("latitud"),
                rs.getDouble("longitud"), rs.getString("zona"), rs.getObject("fecha_hora", LocalDateTime.class),
                rs.getString("usuario"), rs.getString("observaciones")));
    }

    /** Inserta la marca y devuelve el id que le asignó la base. */
    public int insertarMarca(TipoMarca tipo, String descripcion, double latitud, double longitud, Integer idZona,
                             int idUsuario) throws SQLException {
        return (int) insertarConClave("INSERT INTO marca_mapa (tipo, descripcion, latitud, longitud, id_zona, id_usuario) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                tipo, descripcion, ConexionBD.decimal(latitud, 5), ConexionBD.decimal(longitud, 5), idZona, idUsuario);
    }

    /** Marcas vigentes (sin baja), de la más vieja a la más nueva. */
    public List<MarcaMapa> listarMarcas() throws SQLException {
        return consultar("SELECT m.id_marca, m.tipo, m.descripcion, m.latitud, m.longitud, z.nombre AS zona, m.fecha_hora, "
                + "CONCAT(u.nombre, ' ', u.apellido) AS usuario "
                + "FROM marca_mapa m LEFT JOIN zona_vigilancia z ON z.id_zona = m.id_zona "
                + "JOIN usuario u ON u.id_usuario = m.id_usuario "
                + "WHERE m.fecha_hora_baja IS NULL ORDER BY m.id_marca", rs -> new MarcaMapa(rs.getInt("id_marca"),
                TipoMarca.valueOf(rs.getString("tipo")), rs.getString("descripcion"), rs.getDouble("latitud"),
                rs.getDouble("longitud"), rs.getString("zona"), rs.getObject("fecha_hora", LocalDateTime.class),
                rs.getString("usuario")));
    }

    /** Da de baja la marca a nombre del usuario; devuelve false si no existe o ya estaba quitada. */
    public boolean quitarMarca(int idMarca, int idUsuario) throws SQLException {
        return actualizar("UPDATE marca_mapa SET fecha_hora_baja = NOW(), id_usuario_baja = ? "
                + "WHERE id_marca = ? AND fecha_hora_baja IS NULL", idUsuario, idMarca) == 1;
    }
}
