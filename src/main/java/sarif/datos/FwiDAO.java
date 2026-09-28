package sarif.datos;

import sarif.modelo.RegistroFwi;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a los índices FWI que SARIF trae de GWIS. Igual que NdviDAO, recibo la conexión para que el
 * servicio maneje la transacción de todo el lote.
 */
public class FwiDAO {

    private final Connection cn;

    public FwiDAO(Connection cn) {
        this.cn = cn;
    }

    /**
     * Guardo el FWI del día; si ya tenía el de esa zona y fecha, lo reemplazo, porque el pronóstico del
     * servicio se corrige cada día. Devuelvo true si el registro era nuevo: con ON DUPLICATE KEY UPDATE,
     * MySQL informa 1 fila afectada al insertar y 2 al actualizar.
     */
    public boolean guardar(RegistroFwi r) throws SQLException {
        String sql = "INSERT INTO registro_fwi (id_zona, fecha, valor, modelo) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE valor = VALUES(valor), modelo = VALUES(modelo), fecha_hora_carga = NOW()";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, r.idZona());
            ps.setObject(2, r.fecha());
            ps.setBigDecimal(3, ConexionBD.decimal(r.valor(), 2));
            ps.setString(4, r.modelo());
            return ps.executeUpdate() == 1;
        }
    }

    /** FWI más reciente de la zona hasta la fecha indicada (para revisar jornadas pasadas). */
    public Optional<RegistroFwi> ultimo(int idZona, LocalDate hasta) throws SQLException {
        String sql = "SELECT id_zona, fecha, valor, modelo FROM registro_fwi "
                + "WHERE id_zona = ? AND fecha <= ? ORDER BY fecha DESC LIMIT 1";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, idZona);
            ps.setObject(2, hasta);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(leer(rs)) : Optional.empty();
            }
        }
    }

    /** FWI día por día de una zona, en orden, para acompañar el pronóstico extendido. */
    public List<RegistroFwi> entre(int idZona, LocalDate desde, LocalDate hasta) throws SQLException {
        String sql = "SELECT id_zona, fecha, valor, modelo FROM registro_fwi "
                + "WHERE id_zona = ? AND fecha BETWEEN ? AND ? ORDER BY fecha";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, idZona);
            ps.setObject(2, desde);
            ps.setObject(3, hasta);
            try (ResultSet rs = ps.executeQuery()) {
                List<RegistroFwi> registros = new ArrayList<>();
                while (rs.next()) {
                    registros.add(leer(rs));
                }
                return registros;
            }
        }
    }

    private static RegistroFwi leer(ResultSet rs) throws SQLException {
        return new RegistroFwi(rs.getInt(1), rs.getObject(2, LocalDate.class), rs.getDouble(3), rs.getString(4));
    }
}
