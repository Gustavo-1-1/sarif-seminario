package sarif.modelo;

/**
 * Usuario de SARIF con su rol. A propósito no incluyo la clave ni la sal: esas quedan solo en
 * ConfiguracionDAO.Credencial, que se usa para verificar el ingreso, así el objeto que circula por la
 * aplicación (la sesión, la lista de usuarios) no lleva datos sensibles. El rol va como texto, igual que
 * en la base; para saber qué puede hacer uso {@link #puede(Permiso)}.
 */
public record Usuario(int id, String nombreUsuario, String nombre, String apellido, String rol, boolean activo) {

    /** Nombre y apellido juntos, para mostrar quién está conectado en la ventana principal. */
    public String nombreCompleto() {
        return nombre + " " + apellido;
    }

    public boolean puede(Permiso permiso) {
        return activo && Rol.permite(rol, permiso);
    }

    /** Rol para mostrar ("Jefe de guardia"); si el rol no es uno de los conocidos, muestro el nombre tal cual. */
    public String rolTexto() {
        Rol r = Rol.desde(rol);
        return r == null ? rol : r.texto();
    }
}
