package sarif.servicio;

import java.util.List;

/**
 * La lanzo cuando los datos ingresados no cumplen las reglas del caso de uso; el mensaje se le muestra
 * tal cual al operador. Guardo también la lista de errores para poder informarlos todos juntos
 * (por ejemplo en el alta de zona del CU01) en vez de hacerlo corregir de a uno.
 */
public class ValidacionException extends Exception {

    private final List<String> errores;

    /** Para un único error. */
    public ValidacionException(String mensaje) {
        super(mensaje);
        this.errores = List.of(mensaje);
    }

    /** Para varios errores: el mensaje queda con uno por línea. */
    public ValidacionException(List<String> errores) {
        super(String.join("\n", errores));
        this.errores = List.copyOf(errores);
    }

    public List<String> getErrores() {
        return errores;
    }
}
