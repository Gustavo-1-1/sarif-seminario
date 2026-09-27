package sarif.fuentes;

/**
 * La uso para avisar que la fuente no respondió, respondió con error o devolvió un formato inesperado
 * (flujo alternativo S2 del CU07). La hice chequeada (extiende Exception) a propósito, para que el
 * compilador me obligue a manejarla en el servicio y registrar la sincronización como fallida.
 */
public class FuenteNoDisponibleException extends Exception {

    public FuenteNoDisponibleException(String mensaje) {
        super(mensaje);
    }

    /** Conservo la causa original (por ejemplo, la IOException) para poder depurar el problema. */
    public FuenteNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
