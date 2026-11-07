package sarif.datos;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Convierte la fila actual de un ResultSet en un objeto del modelo. Es el único paso que cambia de un DAO a otro
 * cuando leo datos: DaoBase hace el resto (preparar, ejecutar, recorrer y cerrar) y llama a este método una vez
 * por fila. Cada DAO lo implementa con una lambda o con una referencia a su método de lectura, por ejemplo
 * {@code consultar(sql, RecursoDAO::recurso)}.
 * <p>
 * Defino mi propia interfaz en lugar de usar java.util.function.Function porque los getters del ResultSet
 * lanzan SQLException, que es comprobada, y Function no permite declararla.
 *
 * @param <T> tipo del objeto que se arma con cada fila
 */
@FunctionalInterface
public interface MapeadorFila<T> {

    /** Arma el objeto con la fila en la que está posicionado el cursor; no debe llamar a rs.next(). */
    T mapear(ResultSet rs) throws SQLException;
}
