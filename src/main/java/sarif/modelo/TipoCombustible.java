package sarif.modelo;

/**
 * Tipo de combustible vegetal con su factor de inflamabilidad (entre 0 y 1), que uso en la componente
 * de combustible del índice. Es un catálogo de la base, por eso lo modelé como record y no como enum.
 */
public record TipoCombustible(int id, String nombre, double factorInflamabilidad) {

    // Lo sobrescribo para que el ComboBox de la pantalla de zonas (relevamiento) muestre el nombre.
    @Override
    public String toString() {
        return nombre;
    }
}
