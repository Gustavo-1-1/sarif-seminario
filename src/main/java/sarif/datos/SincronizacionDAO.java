package sarif.datos;

import sarif.modelo.OrigenDatos;
import sarif.modelo.Sincronizacion;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Registro de auditoría de las sincronizaciones (CU07, CU08, RFS08). Cada lote que incorporo queda
 * asentado con su origen, el resultado y cuántos registros leí y cuántos eran nuevos; también dejo
 * registradas las fallidas con el motivo. Las sentencias son preparadas, para evitar inyección SQL.
 */
public class SincronizacionDAO extends DaoBase {

    // Recibo la conexión del servicio: el registro de la sincronización va en la misma transacción que el lote.
    public SincronizacionDAO(Connection cn) {
        super(cn);
    }

    /**
     * Registra el inicio de una sincronización y devuelve su identificador (los focos lo referencian).
     * La creo primero con resultado OK y cantidades en cero, y la completo al final con finalizar();
     * si algo falla, el rollback del lote se lleva también esta fila.
     */
    public int iniciar(String tipo, OrigenDatos origen, Integer idUsuario) throws SQLException {
        return insertar(tipo, origen, "OK", null, idUsuario);
    }

    /** Completa la sincronización con la hora de fin y las cantidades procesadas. */
    public void finalizar(int id, int leidos, int nuevos, String detalle) throws SQLException {
        actualizar("UPDATE sincronizacion SET fecha_hora_fin = ?, registros_leidos = ?, registros_nuevos = ?, detalle = ? "
                + "WHERE id_sincronizacion = ?", LocalDateTime.now(), leidos, nuevos, recortar(detalle), id);
    }

    /**
     * Asienta una sincronización fallida con el motivo. Se llama después de revertir el lote,
     * para que el registro de la falla quede guardado aunque los datos no se hayan incorporado.
     */
    public void registrarFallida(String tipo, OrigenDatos origen, Integer idUsuario, String motivo) throws SQLException {
        insertar(tipo, origen, "FALLIDA", motivo, idUsuario);
    }

    /** Devuelve las últimas sincronizaciones, de la más nueva a la más vieja, para el historial de la pantalla. */
    public List<Sincronizacion> listarUltimas(int cantidad) throws SQLException {
        return consultar("SELECT id_sincronizacion, tipo, origen, fecha_hora_inicio, resultado, registros_leidos, "
                        + "registros_nuevos, detalle FROM sincronizacion ORDER BY id_sincronizacion DESC LIMIT ?",
                rs -> new Sincronizacion(rs.getInt(1), rs.getString(2), OrigenDatos.valueOf(rs.getString(3)),
                        rs.getObject(4, LocalDateTime.class), rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getString(8)),
                cantidad);
    }

    /**
     * Inserción común para iniciar() y registrarFallida(); las cantidades arrancan en cero. Una fallida ya
     * terminó, así que le pongo la hora de fin; una que recién empieza la deja en NULL. El usuario es opcional.
     */
    private int insertar(String tipo, OrigenDatos origen, String resultado, String detalle, Integer idUsuario)
            throws SQLException {
        LocalDateTime ahora = LocalDateTime.now();
        return (int) insertarConClave("INSERT INTO sincronizacion (tipo, origen, fecha_hora_inicio, fecha_hora_fin, resultado, "
                        + "registros_leidos, registros_nuevos, detalle, id_usuario) VALUES (?, ?, ?, ?, ?, 0, 0, ?, ?)",
                tipo, origen, ahora, "FALLIDA".equals(resultado) ? ahora : null, resultado, recortar(detalle), idUsuario);
    }

    // Recorto el detalle a 250 caracteres para que entre en la columna y un mensaje de error largo no haga fallar el INSERT.
    private static String recortar(String texto) {
        return texto == null || texto.length() <= 250 ? texto : texto.substring(0, 250);
    }
}
