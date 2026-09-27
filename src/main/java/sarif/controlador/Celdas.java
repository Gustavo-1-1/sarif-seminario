package sarif.controlador;

import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.util.Callback;

/**
 * Fábrica de celdas de tabla que muestran un nivel o estado con color y con texto.
 * <p>
 * Muestro siempre las dos cosas a propósito, por accesibilidad: una persona con daltonismo (o una
 * pantalla con mal contraste) no distingue bien un naranja de un rojo, pero sí lee "ALTO" o "EXTREMO".
 * Así la información nunca depende solo del color. La uso en el tablero, zonas, despliegue,
 * sincronización y alertas, por eso la dejo como clase utilitaria del paquete.
 */
final class Celdas {

    // Constructor privado: es una clase utilitaria con métodos estáticos, no tiene sentido instanciarla.
    private Celdas() {
    }

    /**
     * Devuelve una fábrica de celdas con una etiqueta de color; la clase CSS es el prefijo seguido del
     * valor (por ejemplo, nivel-ALTO). Los colores de cada clase están definidos en sarif.css, así que
     * si agregás un valor nuevo, acordate de sumar su regla en la hoja de estilos.
     */
    static <S> Callback<TableColumn<S, String>, TableCell<S, String>> etiqueta(String prefijoCss) {
        return columna -> new TableCell<>() {
            @Override
            protected void updateItem(String valor, boolean vacia) {
                super.updateItem(valor, vacia);
                // Las celdas se reciclan al hacer scroll, así que siempre limpio el contenido anterior.
                // Si la fila existe pero no tiene valor, muestro un guion para que no parezca un error.
                if (vacia || valor == null) {
                    setGraphic(null);
                    setText(vacia ? null : "—");
                    return;
                }
                // Pongo el texto dentro de un Label para poder darle fondo redondeado con CSS.
                Label etiqueta = new Label(valor);
                etiqueta.getStyleClass().addAll("etiqueta", prefijoCss + valor);
                setText(null);
                setGraphic(etiqueta);
            }
        };
    }
}
