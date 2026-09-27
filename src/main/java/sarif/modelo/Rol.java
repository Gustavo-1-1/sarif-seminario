package sarif.modelo;

import java.util.EnumSet;
import java.util.Set;

/**
 * Roles de SARIF con lo que cada uno puede hacer. Los nombres son los de la tabla rol (sql/02_datos.sql),
 * así el nombre que viene de la base se convierte directo con valueOf.
 * <p>
 * El reparto sale de la descripción de cada rol en la base y de los procesos del organismo del AP1:
 * <ul>
 *   <li>el operador opera la guardia (registra, sincroniza, consulta, atiende alertas, genera reportes);</li>
 *   <li>el jefe de guardia además decide los cambios de nivel de alerta y confirma las asignaciones de
 *       recursos, que en el proceso actual son decisiones suyas;</li>
 *   <li>el administrador puede todo: además de lo del jefe, configura el sistema y administra los
 *       usuarios. Así, en una guardia chica donde una misma persona cumple los dos papeles, no tiene que
 *       cambiar de usuario para decidir un nivel. Igual cada cambio de nivel y cada asignación quedan
 *       registrados con el usuario que los hizo.</li>
 * </ul>
 * Uso EnumSet porque es la colección pensada para conjuntos de valores de un enum.
 */
public enum Rol {

    ADMINISTRADOR("Administrador", EnumSet.allOf(Permiso.class)),
    JEFE_GUARDIA("Jefe de guardia", EnumSet.of(Permiso.OPERAR, Permiso.CAMBIAR_NIVEL_ALERTA, Permiso.ASIGNAR_RECURSOS)),
    OPERADOR("Operador", EnumSet.of(Permiso.OPERAR));

    private final String texto;
    private final Set<Permiso> permisos;

    Rol(String texto, Set<Permiso> permisos) {
        this.texto = texto;
        this.permisos = permisos;
    }

    public boolean puede(Permiso permiso) {
        return permisos.contains(permiso);
    }

    /** Nombre para mostrar en pantalla ("Jefe de guardia" en lugar de JEFE_GUARDIA). */
    public String texto() {
        return texto;
    }

    /**
     * Convierte el nombre guardado en la base. Si alguien cargó en la tabla un rol que el programa no
     * conoce, devuelvo null y ese usuario no tiene ningún permiso (es más seguro que darle alguno).
     */
    public static Rol desde(String nombre) {
        try {
            return nombre == null ? null : valueOf(nombre.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Si el rol con ese nombre tiene el permiso; un rol desconocido no tiene ninguno. */
    public static boolean permite(String nombre, Permiso permiso) {
        Rol rol = desde(nombre);
        return rol != null && rol.puede(permiso);
    }

    @Override
    public String toString() {
        return texto;
    }
}
