package sarif.modelo;

/**
 * Tipo de marca operativa que la guardia pone en el mapa. La letra y el color son los del símbolo que se
 * dibuja; los nombres coinciden con los valores de la columna tipo de la tabla marca_mapa.
 */
public enum TipoMarca {
    PUESTO_COMANDO("Puesto de comando", "C", "#0d47a1"),
    PUNTO_AGUA("Punto de agua", "A", "#0288d1"),
    ACCESO("Acceso o camino", "V", "#6d4c41"),
    EVACUACION("Punto de evacuación", "E", "#2e7d32"),
    PELIGRO("Peligro", "!", "#c62828"),
    OTRA("Otra marca", "•", "#546e7a");

    private final String texto;
    private final String letra;
    private final String color;

    TipoMarca(String texto, String letra, String color) {
        this.texto = texto;
        this.letra = letra;
        this.color = color;
    }

    public String texto() {
        return texto;
    }

    public String letra() {
        return letra;
    }

    public String color() {
        return color;
    }

    @Override
    public String toString() {
        return texto;
    }
}
