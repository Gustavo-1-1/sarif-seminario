package sarif.servicio;

import sarif.modelo.Usuario;

import java.time.LocalDate;

/**
 * Guarda los datos de la sesión de la guardia: el usuario que ingresó y la fecha de trabajo.
 * La fecha de trabajo arranca en el día de hoy, pero se puede cambiar para reconstruir o recalcular
 * una jornada anterior. Como es una aplicación de escritorio con un solo usuario a la vez, me alcanza
 * con atributos estáticos.
 */
public final class Sesion {

    private static Usuario usuario;
    private static LocalDate fechaTrabajo = LocalDate.now();

    private Sesion() {
    }

    public static Usuario getUsuario() {
        return usuario;
    }

    /** Lo llama ServicioAutenticacion cuando el ingreso es correcto. */
    public static void iniciar(Usuario u) {
        usuario = u;
    }

    public static LocalDate getFechaTrabajo() {
        return fechaTrabajo;
    }

    public static void setFechaTrabajo(LocalDate fecha) {
        fechaTrabajo = fecha;
    }
}
