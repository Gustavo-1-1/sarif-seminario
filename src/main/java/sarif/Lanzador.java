package sarif;

/**
 * Lanza la aplicación desde el IDE sin configurar el module-path de JavaFX.
 * <p>
 * Esta clase existe por una particularidad de la JVM: cuando la clase principal extiende
 * {@code Application}, el lanzador de Java exige que los módulos de JavaFX estén en el module-path
 * y, si no los encuentra, corta con "faltan los componentes de tiempo de ejecución de JavaFX".
 * Como Lanzador no extiende Application, esa verificación no se hace y JavaFX se carga desde el
 * classpath normal. Si querés ejecutar SARIF desde el IDE, corré esta clase y no {@link App}.
 */
public class Lanzador {

    /** Solo delega en App.main(); no agrego ninguna lógica acá. */
    public static void main(String[] args) {
        App.main(args);
    }
}
