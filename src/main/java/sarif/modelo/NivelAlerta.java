package sarif.modelo;

/**
 * Nivel de alerta operativa de una zona (NORMAL, ATENCION, ALERTA, EMERGENCIA). Lo cambia el operador
 * con un fundamento (CU12). Lo dejé como catálogo en la base (y no como enum) para que se pueda
 * ajustar sin recompilar; el campo orden me sirve para ordenarlos de menor a mayor gravedad.
 */
public record NivelAlerta(int id, String nombre, int orden) {

    // Sobrescribo toString para que los ComboBox de JavaFX muestren directamente el nombre.
    @Override
    public String toString() {
        return nombre;
    }
}
