package sarif.datos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba unitaria de la traducción de errores de MySQL (PU-39). Armo a mano las SQLException con el SQLState y
 * el código que manda el driver en cada caso, así la prueba corre sin base. Verifico que el mensaje sea el
 * previsto para cada código, que los errores de conexión se reconozcan por el SQLState, que no se pierda la
 * información técnica (código, SQLState y causa) y que traducir dos veces no envuelva el mensaje dos veces.
 */
class DatosExceptionTest {

    @Test
    @DisplayName("PU-39 Traducción de errores de MySQL: duplicado, clave foránea, acceso, conexión y código desconocido")
    void traduceLosErroresPrevistos() {
        SQLException duplicado = new SQLException("Duplicate entry 'Aluminé' for key 'uq_zona_nombre'", "23000", 1062);
        DatosException traducida = DatosException.traducir(duplicado);
        assertTrue(traducida.getMessage().startsWith("Ya existe un registro con esos datos"), traducida.getMessage());
        // Conserva el código, el SQLState y la excepción original, y el detalle técnico es el texto de MySQL.
        assertEquals(1062, traducida.getErrorCode());
        assertEquals("23000", traducida.getSQLState());
        assertSame(duplicado, traducida.getCause());
        assertEquals(duplicado.getMessage(), traducida.getDetalleTecnico());
        // Sigue siendo una SQLException: los catch y los rollback que ya había la siguen atrapando.
        assertInstanceOf(SQLException.class, traducida);

        assertTrue(DatosException.traducir(new SQLException("Cannot delete or update a parent row", "23000", 1451))
                .getMessage().startsWith("No se puede borrar"));
        // Mismo error con el código corto que MySQL usa cuando el usuario no ve el detalle de la restricción.
        assertTrue(DatosException.traducir(new SQLException("Cannot delete or update a parent row", "23000", 1217))
                .getMessage().startsWith("No se puede borrar"));
        assertTrue(DatosException.traducir(new SQLException("Access denied for user 'sarif_app'", "28000", 1045))
                .getMessage().contains("sarif.local.properties"));

        // Servidor detenido: el driver no trae código de MySQL, lo reconozco por la clase 08 del SQLState.
        DatosException sinConexion = DatosException.traducir(new SQLException("Communications link failure", "08S01", 0));
        assertTrue(sinConexion.getMessage().startsWith("No se pudo conectar con el servidor MySQL"));

        // Un código que no está en la tabla da un mensaje genérico que lo informa.
        assertEquals("La base de datos rechazó la operación (código 9999).",
                DatosException.traducir(new SQLException("raro", "HY000", 9999)).getMessage());

        // Traducir una excepción ya traducida devuelve la misma, sin envolverla otra vez.
        assertSame(traducida, DatosException.traducir(traducida));
    }
}
