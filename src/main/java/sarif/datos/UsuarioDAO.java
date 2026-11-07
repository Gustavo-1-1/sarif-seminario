package sarif.datos;

import sarif.modelo.Usuario;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a las tablas usuario y rol: el ingreso (RFS01) y la administración de usuarios que hace el
 * administrador. Todas las consultas son sentencias preparadas; en el ingreso es especialmente importante,
 * porque el nombre de usuario lo escribe la persona en la pantalla de login.
 * <p>
 * Los usuarios no se borran: se desactivan, porque sus registros quedan referenciados en relevamientos,
 * asignaciones, cambios de nivel y sincronizaciones (por eso la FK de la base es ON DELETE RESTRICT).
 */
public class UsuarioDAO extends DaoBase {

    /**
     * Datos de ingreso de un usuario: el hash de la clave y la sal. Los uso solo para verificar la clave;
     * después en la sesión circula únicamente el Usuario, que no los tiene.
     */
    public record Credencial(Usuario usuario, String claveHash, String sal) {
    }

    private static final String COLUMNAS = "SELECT u.id_usuario, u.nombre_usuario, u.nombre, u.apellido, r.nombre, u.activo "
            + "FROM usuario u JOIN rol r ON r.id_rol = u.id_rol ";
    private static final String CREDENCIAL = "SELECT u.id_usuario, u.nombre_usuario, u.nombre, u.apellido, r.nombre, u.activo, "
            + "u.clave_hash, u.sal FROM usuario u JOIN rol r ON r.id_rol = u.id_rol ";

    public UsuarioDAO(Connection cn) {
        super(cn);
    }

    /** Busca los datos de ingreso por nombre de usuario (RFS01), junto con su rol. */
    public Optional<Credencial> buscarCredencial(String nombreUsuario) throws SQLException {
        return consultarUno(CREDENCIAL + "WHERE u.nombre_usuario = ?", UsuarioDAO::leerCredencial, nombreUsuario);
    }

    /** Hash y sal del usuario por id, para verificar la clave actual cuando alguien cambia la suya. */
    public Optional<Credencial> credencial(int idUsuario) throws SQLException {
        return consultarUno(CREDENCIAL + "WHERE u.id_usuario = ?", UsuarioDAO::leerCredencial, idUsuario);
    }

    public Optional<Usuario> buscar(int idUsuario) throws SQLException {
        return consultarUno(COLUMNAS + "WHERE u.id_usuario = ?", UsuarioDAO::usuario, idUsuario);
    }

    public List<Usuario> listar() throws SQLException {
        return consultar(COLUMNAS + "ORDER BY u.activo DESC, u.apellido, u.nombre", UsuarioDAO::usuario);
    }

    public boolean existeNombreUsuario(String nombreUsuario) throws SQLException {
        return existe("SELECT COUNT(*) FROM usuario WHERE nombre_usuario = ?", nombreUsuario);
    }

    /** Cuántos administradores activos hay; el servicio no deja quedarse sin ninguno. */
    public int contarAdministradoresActivos() throws SQLException {
        return entero("SELECT COUNT(*) FROM usuario u JOIN rol r ON r.id_rol = u.id_rol "
                + "WHERE u.activo AND r.nombre = 'ADMINISTRADOR'");
    }

    /** Da de alta el usuario; el rol lo busco por nombre con una subconsulta. Devuelve el id generado. */
    public int insertar(Usuario u, String claveHash, String sal) throws SQLException {
        return (int) insertarConClave("INSERT INTO usuario (nombre_usuario, nombre, apellido, clave_hash, sal, id_rol, activo) "
                        + "VALUES (?, ?, ?, ?, ?, (SELECT id_rol FROM rol WHERE nombre = ?), TRUE)",
                u.nombreUsuario(), u.nombre(), u.apellido(), claveHash, sal, u.rol());
    }

    public void cambiarRol(int idUsuario, String rol) throws SQLException {
        actualizar("UPDATE usuario SET id_rol = (SELECT id_rol FROM rol WHERE nombre = ?) WHERE id_usuario = ?", rol, idUsuario);
    }

    public void cambiarActivo(int idUsuario, boolean activo) throws SQLException {
        actualizar("UPDATE usuario SET activo = ? WHERE id_usuario = ?", activo, idUsuario);
    }

    /** Guarda una clave nueva. Cambio también la sal, así el hash nuevo no se parece en nada al anterior. */
    public void cambiarClave(int idUsuario, String claveHash, String sal) throws SQLException {
        actualizar("UPDATE usuario SET clave_hash = ?, sal = ? WHERE id_usuario = ?", claveHash, sal, idUsuario);
    }

    private static Usuario usuario(ResultSet rs) throws SQLException {
        return new Usuario(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6));
    }

    private static Credencial leerCredencial(ResultSet rs) throws SQLException {
        return new Credencial(usuario(rs), rs.getString(7), rs.getString(8));
    }
}
