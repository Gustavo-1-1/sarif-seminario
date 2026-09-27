package sarif.datos;

import sarif.modelo.Usuario;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
public class UsuarioDAO {

    /**
     * Datos de ingreso de un usuario: el hash de la clave y la sal. Los uso solo para verificar la clave;
     * después en la sesión circula únicamente el Usuario, que no los tiene.
     */
    public record Credencial(Usuario usuario, String claveHash, String sal) {
    }

    private static final String COLUMNAS = "SELECT u.id_usuario, u.nombre_usuario, u.nombre, u.apellido, r.nombre, u.activo "
            + "FROM usuario u JOIN rol r ON r.id_rol = u.id_rol ";

    private final Connection cn;

    public UsuarioDAO(Connection cn) {
        this.cn = cn;
    }

    /** Busca los datos de ingreso por nombre de usuario (RFS01), junto con su rol. */
    public Optional<Credencial> buscarCredencial(String nombreUsuario) throws SQLException {
        String sql = "SELECT u.id_usuario, u.nombre_usuario, u.nombre, u.apellido, r.nombre, u.activo, u.clave_hash, u.sal "
                + "FROM usuario u JOIN rol r ON r.id_rol = u.id_rol WHERE u.nombre_usuario = ?";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, nombreUsuario);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Credencial(usuario(rs), rs.getString(7), rs.getString(8))) : Optional.empty();
            }
        }
    }

    /** Hash y sal del usuario por id, para verificar la clave actual cuando alguien cambia la suya. */
    public Optional<Credencial> credencial(int idUsuario) throws SQLException {
        String sql = "SELECT u.id_usuario, u.nombre_usuario, u.nombre, u.apellido, r.nombre, u.activo, u.clave_hash, u.sal "
                + "FROM usuario u JOIN rol r ON r.id_rol = u.id_rol WHERE u.id_usuario = ?";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, idUsuario);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Credencial(usuario(rs), rs.getString(7), rs.getString(8))) : Optional.empty();
            }
        }
    }

    public Optional<Usuario> buscar(int idUsuario) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement(COLUMNAS + "WHERE u.id_usuario = ?")) {
            ps.setInt(1, idUsuario);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(usuario(rs)) : Optional.empty();
            }
        }
    }

    public List<Usuario> listar() throws SQLException {
        List<Usuario> lista = new ArrayList<>();
        try (PreparedStatement ps = cn.prepareStatement(COLUMNAS + "ORDER BY u.activo DESC, u.apellido, u.nombre");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lista.add(usuario(rs));
            }
        }
        return lista;
    }

    public boolean existeNombreUsuario(String nombreUsuario) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement("SELECT 1 FROM usuario WHERE nombre_usuario = ?")) {
            ps.setString(1, nombreUsuario);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Cuántos administradores activos hay; el servicio no deja quedarse sin ninguno. */
    public int contarAdministradoresActivos() throws SQLException {
        String sql = "SELECT COUNT(*) FROM usuario u JOIN rol r ON r.id_rol = u.id_rol WHERE u.activo AND r.nombre = 'ADMINISTRADOR'";
        try (PreparedStatement ps = cn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Da de alta el usuario; el rol lo busco por nombre con una subconsulta. Devuelve el id generado. */
    public int insertar(Usuario u, String claveHash, String sal) throws SQLException {
        String sql = "INSERT INTO usuario (nombre_usuario, nombre, apellido, clave_hash, sal, id_rol, activo) "
                + "VALUES (?, ?, ?, ?, ?, (SELECT id_rol FROM rol WHERE nombre = ?), TRUE)";
        try (PreparedStatement ps = cn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, u.nombreUsuario());
            ps.setString(2, u.nombre());
            ps.setString(3, u.apellido());
            ps.setString(4, claveHash);
            ps.setString(5, sal);
            ps.setString(6, u.rol());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public void cambiarRol(int idUsuario, String rol) throws SQLException {
        actualizar("UPDATE usuario SET id_rol = (SELECT id_rol FROM rol WHERE nombre = ?) WHERE id_usuario = ?", rol, idUsuario);
    }

    public void cambiarActivo(int idUsuario, boolean activo) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement("UPDATE usuario SET activo = ? WHERE id_usuario = ?")) {
            ps.setBoolean(1, activo);
            ps.setInt(2, idUsuario);
            ps.executeUpdate();
        }
    }

    /** Guarda una clave nueva. Cambio también la sal, así el hash nuevo no se parece en nada al anterior. */
    public void cambiarClave(int idUsuario, String claveHash, String sal) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement("UPDATE usuario SET clave_hash = ?, sal = ? WHERE id_usuario = ?")) {
            ps.setString(1, claveHash);
            ps.setString(2, sal);
            ps.setInt(3, idUsuario);
            ps.executeUpdate();
        }
    }

    private void actualizar(String sql, String texto, int id) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, texto);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    private static Usuario usuario(ResultSet rs) throws SQLException {
        return new Usuario(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6));
    }
}
