package sarif.servicio;

import sarif.datos.ConexionBD;
import sarif.datos.UsuarioDAO;
import sarif.modelo.Permiso;
import sarif.modelo.Rol;
import sarif.modelo.Usuario;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Administración de usuarios (pestaña "Usuarios", solo para el administrador) y cambio de la clave propia
 * (cualquier usuario, desde la barra superior).
 * <p>
 * Dos reglas para no dejar el sistema sin salida: nadie puede desactivarse ni quitarse el rol de
 * administrador a sí mismo, y siempre tiene que quedar al menos un administrador activo. Sin eso, un
 * error de un clic dejaría a SARIF sin nadie que pueda dar de alta usuarios.
 */
public class ServicioUsuarios {

    /** Largo mínimo de la clave. No pido símbolos ni mayúsculas: el largo es lo que más la hace difícil de adivinar. */
    public static final int LARGO_MINIMO_CLAVE = 8;

    public List<Usuario> listar(int idAdministrador) throws SQLException, ValidacionException {
        Permisos.exigir(idAdministrador, Permiso.ADMINISTRAR_USUARIOS);
        try (Connection cn = ConexionBD.obtener()) {
            return new UsuarioDAO(cn).listar();
        }
    }

    /** Alta de usuario con su clave inicial; le conviene cambiarla en el primer ingreso. Devuelve el id. */
    public int crear(Usuario nuevo, String clave, int idAdministrador) throws SQLException, ValidacionException {
        Permisos.exigir(idAdministrador, Permiso.ADMINISTRAR_USUARIOS);
        List<String> errores = validar(nuevo);
        errores.addAll(validarClave(clave));
        try (Connection cn = ConexionBD.obtener()) {
            UsuarioDAO dao = new UsuarioDAO(cn);
            if (errores.isEmpty() && dao.existeNombreUsuario(nuevo.nombreUsuario().trim())) {
                errores.add("Ya existe un usuario con el nombre " + nuevo.nombreUsuario().trim() + ".");
            }
            if (!errores.isEmpty()) {
                throw new ValidacionException(errores);
            }
            String sal = ServicioAutenticacion.nuevaSal();
            Usuario limpio = new Usuario(0, nuevo.nombreUsuario().trim(), nuevo.nombre().trim(), nuevo.apellido().trim(),
                    nuevo.rol(), true);
            return dao.insertar(limpio, ServicioAutenticacion.hash(sal, clave), sal);
        }
    }

    public void cambiarRol(int idUsuario, Rol rol, int idAdministrador) throws SQLException, ValidacionException {
        Permisos.exigir(idAdministrador, Permiso.ADMINISTRAR_USUARIOS);
        if (rol == null) {
            throw new ValidacionException("Debe elegir un rol.");
        }
        try (Connection cn = ConexionBD.obtener()) {
            UsuarioDAO dao = new UsuarioDAO(cn);
            Usuario u = existente(dao, idUsuario);
            if (u.rol().equals(rol.name())) {
                throw new ValidacionException(u.nombreUsuario() + " ya tiene el rol " + rol.texto() + ".");
            }
            if (Rol.ADMINISTRADOR.name().equals(u.rol())) {
                controlarQueQuedeAdministrador(dao, u, idAdministrador, "quitarse el rol de administrador");
            }
            dao.cambiarRol(idUsuario, rol.name());
        }
    }

    public void cambiarActivo(int idUsuario, boolean activo, int idAdministrador) throws SQLException, ValidacionException {
        Permisos.exigir(idAdministrador, Permiso.ADMINISTRAR_USUARIOS);
        try (Connection cn = ConexionBD.obtener()) {
            UsuarioDAO dao = new UsuarioDAO(cn);
            Usuario u = existente(dao, idUsuario);
            if (u.activo() == activo) {
                throw new ValidacionException(u.nombreUsuario() + (activo ? " ya está activo." : " ya está desactivado."));
            }
            if (!activo && Rol.ADMINISTRADOR.name().equals(u.rol())) {
                controlarQueQuedeAdministrador(dao, u, idAdministrador, "desactivarse");
            }
            dao.cambiarActivo(idUsuario, activo);
        }
    }

    /** El administrador le pone una clave nueva a alguien que se la olvidó (no necesita la anterior). */
    public void restablecerClave(int idUsuario, String clave, int idAdministrador) throws SQLException, ValidacionException {
        Permisos.exigir(idAdministrador, Permiso.ADMINISTRAR_USUARIOS);
        List<String> errores = validarClave(clave);
        if (!errores.isEmpty()) {
            throw new ValidacionException(errores);
        }
        try (Connection cn = ConexionBD.obtener()) {
            UsuarioDAO dao = new UsuarioDAO(cn);
            existente(dao, idUsuario);
            String sal = ServicioAutenticacion.nuevaSal();
            dao.cambiarClave(idUsuario, ServicioAutenticacion.hash(sal, clave), sal);
        }
    }

    /** Cualquier usuario cambia su propia clave; pido la actual para que nadie la cambie en una sesión ajena. */
    public void cambiarMiClave(int idUsuario, String actual, String nueva, String repetida) throws SQLException, ValidacionException {
        List<String> errores = validarClave(nueva);
        if (nueva != null && !nueva.equals(repetida)) {
            errores.add("La clave nueva y su repetición no coinciden.");
        }
        if (nueva != null && nueva.equals(actual)) {
            errores.add("La clave nueva tiene que ser distinta de la actual.");
        }
        try (Connection cn = ConexionBD.obtener()) {
            UsuarioDAO dao = new UsuarioDAO(cn);
            Optional<UsuarioDAO.Credencial> c = dao.credencial(idUsuario);
            if (c.isEmpty() || actual == null || !ServicioAutenticacion.hash(c.get().sal(), actual).equals(c.get().claveHash())) {
                errores.add(0, "La clave actual no es correcta.");
            }
            if (!errores.isEmpty()) {
                throw new ValidacionException(errores);
            }
            String sal = ServicioAutenticacion.nuevaSal();
            dao.cambiarClave(idUsuario, ServicioAutenticacion.hash(sal, nueva), sal);
        }
    }

    /**
     * Reglas del alta. El nombre de usuario va en minúsculas, sin espacios ni tildes, porque es lo que se
     * escribe en la pantalla de ingreso; los largos son los de las columnas de la tabla usuario.
     */
    public static List<String> validar(Usuario u) {
        List<String> errores = new ArrayList<>();
        String nombreUsuario = u.nombreUsuario() == null ? "" : u.nombreUsuario().trim();
        if (!nombreUsuario.matches("[a-z0-9._]{3,30}")) {
            errores.add("El nombre de usuario debe tener de 3 a 30 caracteres: minúsculas, números, punto o guion bajo.");
        }
        if (u.nombre() == null || u.nombre().isBlank() || u.nombre().trim().length() > 60) {
            errores.add("El nombre es obligatorio (hasta 60 caracteres).");
        }
        if (u.apellido() == null || u.apellido().isBlank() || u.apellido().trim().length() > 60) {
            errores.add("El apellido es obligatorio (hasta 60 caracteres).");
        }
        if (Rol.desde(u.rol()) == null) {
            errores.add("Debe elegir un rol.");
        }
        return errores;
    }

    public static List<String> validarClave(String clave) {
        List<String> errores = new ArrayList<>();
        if (clave == null || clave.length() < LARGO_MINIMO_CLAVE) {
            errores.add("La clave debe tener al menos " + LARGO_MINIMO_CLAVE + " caracteres.");
        } else if (clave.isBlank() || !clave.equals(clave.strip())) {
            errores.add("La clave no puede empezar ni terminar con espacios.");
        }
        return errores;
    }

    private static Usuario existente(UsuarioDAO dao, int idUsuario) throws SQLException, ValidacionException {
        return dao.buscar(idUsuario).orElseThrow(() -> new ValidacionException("El usuario ya no existe."));
    }

    private static void controlarQueQuedeAdministrador(UsuarioDAO dao, Usuario u, int idAdministrador, String accion)
            throws SQLException, ValidacionException {
        if (u.id() == idAdministrador) {
            throw new ValidacionException("Un administrador no puede " + accion + " a sí mismo: pídaselo a otro administrador.");
        }
        if (u.activo() && dao.contarAdministradoresActivos() <= 1) {
            throw new ValidacionException(u.nombreUsuario() + " es el único administrador activo y SARIF no puede quedar sin administradores.");
        }
    }
}
