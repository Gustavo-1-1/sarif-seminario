package sarif.datos;

import sarif.modelo.OrigenDatos;
import sarif.modelo.Sincronizacion;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Registro de auditoría de las sincronizaciones (CU07, CU08, RFS08). Cada lote que incorporo queda
 * asentado con su origen, el resultado y cuántos registros leí y cuántos eran nuevos; también dejo
 * registradas las fallidas con el motivo. Uso sentencias preparadas para evitar inyección SQL.
 */
public class SincronizacionDAO {

    private final Connection cn;

    // Recibo la conexión del servicio: el registro de la sincronización va en la misma transacción que el lote.
    public SincronizacionDAO(Connection cn) {
        this.cn = cn;
    }

    /**
     * Registra el inicio de una sincronización y devuelve su identificador (los focos lo referencian).
     * La creo primero con resultado OK y cantidades en cero, y la completo al final con finalizar();
     * si algo falla, el rollback del lote se lleva también esta fila.
     */
    public int iniciar(String tipo, OrigenDatos origen, Integer idUsuario) throws SQLException {
        return insertar(tipo, origen, "OK", 0, 0, null, idUsuario);
    }

    /** Completa la sincronización con la hora de fin y las cantidades procesadas. */
    public void finalizar(int id, int leidos, int nuevos, String detalle) throws SQLException {
        String sql = "UPDATE sincronizacion SET fecha_hora_fin = ?, registros_leidos = ?, registros_nuevos = ?, detalle = ? "
                + "WHERE id_sincronizacion = ?";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setObject(1, LocalDateTime.now());
            ps.setInt(2, leidos);
            ps.setInt(3, nuevos);
            ps.setString(4, recortar(detalle));
            ps.setInt(5, id);
            ps.executeUpdate();
        }
    }

    /**
     * Asienta una sincronización fallida con el motivo. Se llama después de revertir el lote,
     * para que el registro de la falla quede guardado aunque los datos no se hayan incorporado.
     */
    public void registrarFallida(String tipo, OrigenDatos origen, Integer idUsuario, String motivo) throws SQLException {
        insertar(tipo, origen, "FALLIDA", 0, 0, motivo, idUsuario);
    }

    /** Devuelve las últimas sincronizaciones, de la más nueva a la más vieja, para el historial de la pantalla. */
    public List<Sincronizacion> listarUltimas(int cantidad) throws SQLException {
        String sql = "SELECT id_sincronizacion, tipo, origen, fecha_hora_inicio, resultado, registros_leidos, "
                + "registros_nuevos, detalle FROM sincronizacion ORDER BY id_sincronizacion DESC LIMIT ?";
        List<Sincronizacion> lista = new ArrayList<>();
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, cantidad);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new Sincronizacion(rs.getInt(1), rs.getString(2), OrigenDatos.valueOf(rs.getString(3)),
                            rs.getObject(4, LocalDateTime.class), rs.getString(5), rs.getInt(6), rs.getInt(7),
                            rs.getString(8)));
                }
            }
        }
        return lista;
    }

    // Inserción común para iniciar() y registrarFallida(). Devuelvo el id generado con getGeneratedKeys().
    private int insertar(String tipo, OrigenDatos origen, String resultado, int leidos, int nuevos, String detalle,
                         Integer idUsuario) throws SQLException {
        String sql = "INSERT INTO sincronizacion (tipo, origen, fecha_hora_inicio, fecha_hora_fin, resultado, "
                + "registros_leidos, registros_nuevos, detalle, id_usuario) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = cn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            LocalDateTime ahora = LocalDateTime.now();
            ps.setString(1, tipo);
            ps.setString(2, origen.name());
            ps.setObject(3, ahora);
            // Una fallida ya terminó, así que le pongo la hora de fin; una que recién empieza la deja en NULL.
            ps.setObject(4, "FALLIDA".equals(resultado) ? ahora : null);
            ps.setString(5, resultado);
            ps.setInt(6, leidos);
            ps.setInt(7, nuevos);
            ps.setString(8, recortar(detalle));
            // El usuario es opcional (la columna admite NULL), así que si no viene lo guardo como NULL.
            if (idUsuario == null) {
                ps.setNull(9, Types.INTEGER);
            } else {
                ps.setInt(9, idUsuario);
            }
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // Recorto el detalle a 250 caracteres para que entre en la columna y un mensaje de error largo no haga fallar el INSERT.
    private static String recortar(String texto) {
        return texto == null || texto.length() <= 250 ? texto : texto.substring(0, 250);
    }
}
