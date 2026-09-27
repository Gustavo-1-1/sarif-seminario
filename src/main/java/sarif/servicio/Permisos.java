package sarif.servicio;

import sarif.datos.ConexionBD;
import sarif.datos.UsuarioDAO;
import sarif.modelo.Permiso;
import sarif.modelo.Usuario;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Control de permisos en la capa de servicio. Las pantallas ya deshabilitan lo que el rol no puede hacer,
 * pero la regla la vuelvo a controlar acá, antes de escribir en la base: la pantalla es solo la interfaz,
 * y si mañana otro controlador llama al servicio, el permiso se sigue respetando.
 * <p>
 * Leo el rol de la base en cada control (y no de la sesión) porque es el dato vigente: si el administrador
 * le cambió el rol o desactivó a alguien, eso vale desde ese momento.
 */
public final class Permisos {

    private Permisos() {
    }

    /**
     * Lanzo ValidacionException si el usuario no existe, está inactivo o su rol no tiene el permiso.
     * Uso la misma excepción que las validaciones de datos para que las pantallas la muestren igual.
     */
    public static void exigir(int idUsuario, Permiso permiso) throws SQLException, ValidacionException {
        try (Connection cn = ConexionBD.obtener()) {
            exigir(new UsuarioDAO(cn).buscar(idUsuario), permiso);
        }
    }

    static void exigir(Optional<Usuario> usuario, Permiso permiso) throws ValidacionException {
        if (usuario.isEmpty() || !usuario.get().activo()) {
            throw new ValidacionException("El usuario no está habilitado para " + permiso.descripcion() + ".");
        }
        if (!usuario.get().puede(permiso)) {
            throw new ValidacionException("El rol " + usuario.get().rolTexto() + " no permite " + permiso.descripcion() + ".");
        }
    }
}
