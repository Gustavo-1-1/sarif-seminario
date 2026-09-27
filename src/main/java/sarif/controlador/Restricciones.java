package sarif.controlador;

import javafx.scene.Node;
import javafx.scene.control.Label;
import sarif.modelo.Permiso;
import sarif.modelo.Rol;
import sarif.modelo.Usuario;
import sarif.servicio.Sesion;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Adapta las pantallas al rol del usuario que ingresó: deshabilita los controles de una acción que su rol
 * no permite y muestra un aviso que dice qué rol la puede hacer. Deshabilito en vez de ocultar para que se
 * entienda que la función existe y a quién hay que pedírsela.
 * <p>
 * Esto es solo la interfaz: el permiso lo vuelve a controlar el servicio antes de escribir en la base.
 */
final class Restricciones {

    private Restricciones() {
    }

    /** Si el usuario de la sesión tiene el permiso. */
    static boolean puede(Permiso permiso) {
        Usuario u = Sesion.getUsuario();
        return u != null && u.puede(permiso);
    }

    /**
     * Habilito o deshabilito los controles según el permiso. El aviso (puede ser null) se muestra solo
     * cuando falta el permiso, y no ocupa lugar cuando no hace falta.
     */
    static void aplicar(Permiso permiso, Label aviso, Node... controles) {
        boolean puede = puede(permiso);
        for (Node control : controles) {
            control.setDisable(!puede);
        }
        if (aviso != null) {
            aviso.setText(puede ? "" : "Solo " + quienes(permiso) + " puede " + permiso.descripcion() + ".");
            aviso.setVisible(!puede);
            aviso.setManaged(!puede);
        }
    }

    /** Roles que tienen el permiso, para el aviso: "el Jefe de guardia", "el Administrador"... */
    static String quienes(Permiso permiso) {
        return Arrays.stream(Rol.values())
                .filter(r -> r.puede(permiso))
                .map(r -> "el " + r.texto())
                .collect(Collectors.joining(" o "));
    }
}
