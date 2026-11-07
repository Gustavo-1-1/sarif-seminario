package sarif.datos;

import java.sql.SQLException;

/**
 * Error de MySQL traducido a un mensaje que el operador entiende. Un SQLException trae el texto del servidor en
 * inglés ("Duplicate entry 'x' for key 'uq_nombre'"), que no le sirve a quien está de guardia; acá lo reemplazo
 * por una explicación y conservo el original como causa, junto con el SQLState y el código de error de MySQL.
 * <p>
 * Extiende SQLException a propósito: todo el código que ya atrapaba SQLException (los rollback de los servicios
 * y los catch de los controladores) la sigue atrapando sin cambios, y quien necesita el detalle técnico lo tiene
 * en getCause().
 */
public class DatosException extends SQLException {

    /*
     * Códigos de error de MySQL que el sistema puede provocar y el mensaje para cada uno. Son dos arreglos
     * paralelos: MENSAJES[i] explica el código CODIGOS[i]. Uso arreglos porque es una tabla de tamaño fijo,
     * conocida al compilar, que no cambia mientras el programa corre; recorrerla es más barato que armar un mapa.
     * Las claves foráneas tienen dos códigos cada una: MySQL manda 1217 y 1216 en lugar de 1451 y 1452 cuando el
     * usuario no tiene permiso para ver el detalle de la restricción, que es el caso de sarif_app.
     */
    private static final int[] CODIGOS = {1045, 1049, 1044, 1142, 1062, 1451, 1217, 1452, 1216, 1048, 1406, 3819, 1205, 1213};
    private static final String[] MENSAJES = {
            "La base rechazó el usuario o la clave. Revise db.usuario y db.clave en config/sarif.local.properties.",
            "No existe la base de datos indicada en db.url. Créela con sql/01_esquema.sql y sql/02_datos.sql.",
            "El usuario de la aplicación no tiene acceso a la base de datos indicada en db.url.",
            "El usuario de la aplicación no tiene permiso para esta operación sobre la tabla.",
            "Ya existe un registro con esos datos: la base no admite valores repetidos en ese campo.",
            "No se puede borrar ni modificar el registro porque otros registros dependen de él.",
            "No se puede borrar ni modificar el registro porque otros registros dependen de él.",
            "El registro relacionado no existe (por ejemplo, la zona o el usuario fueron dados de baja).",
            "El registro relacionado no existe (por ejemplo, la zona o el usuario fueron dados de baja).",
            "Falta un dato obligatorio.",
            "Un dato supera el largo máximo que admite la base.",
            "Un dato está fuera del rango que admite la base.",
            "La base está ocupada por otra operación. Espere unos segundos y vuelva a intentar.",
            "Dos operaciones se bloquearon entre sí y la base canceló esta. Vuelva a intentar."
    };

    // Los errores de conexión no tienen un código de MySQL (el servidor no llegó a responder): los reconozco por la
    // clase "08" del SQLState, que el estándar SQL reserva para las fallas de conexión.
    private static final String CLASE_CONEXION = "08";
    private static final String SIN_CONEXION = "No se pudo conectar con el servidor MySQL. Verifique que el servicio "
            + "esté iniciado y que db.url en config/sarif.properties apunte al equipo correcto.";

    public DatosException(String mensaje, SQLException causa) {
        super(mensaje, causa.getSQLState(), causa.getErrorCode(), causa);
    }

    /**
     * Devuelve la excepción traducida. Si ya estaba traducida la devuelvo tal cual, para que un error que pasa por
     * dos capas no termine con el mensaje envuelto dos veces.
     */
    public static DatosException traducir(SQLException e) {
        if (e instanceof DatosException ya) {
            return ya;
        }
        for (int i = 0; i < CODIGOS.length; i++) {
            if (CODIGOS[i] == e.getErrorCode()) {
                return new DatosException(MENSAJES[i], e);
            }
        }
        if (e.getSQLState() != null && e.getSQLState().startsWith(CLASE_CONEXION)) {
            return new DatosException(SIN_CONEXION, e);
        }
        return new DatosException("La base de datos rechazó la operación (código " + e.getErrorCode() + ").", e);
    }

    /** Mensaje original del servidor o del driver, para mostrarlo como detalle debajo del mensaje principal. */
    public String getDetalleTecnico() {
        return getCause() == null || getCause().getMessage() == null ? "" : getCause().getMessage();
    }
}
