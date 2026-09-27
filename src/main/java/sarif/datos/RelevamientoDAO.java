package sarif.datos;

import sarif.modelo.Relevamiento;
import sarif.modelo.TipoCombustible;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Acceso a los relevamientos de combustible (RFS21). Cada relevamiento es una fila nueva, nunca
 * actualizo uno existente: así conservo el historial y el combustible vigente de la zona se deriva
 * del último relevamiento, en lugar de guardarlo en la zona. Uso sentencias preparadas para evitar inyección SQL.
 */
public class RelevamientoDAO {

    private final Connection cn;

    // Recibo la conexión porque en el alta de zona (CU01) el primer relevamiento va en la misma transacción.
    public RelevamientoDAO(Connection cn) {
        this.cn = cn;
    }

    /** Agrega un relevamiento nuevo de la zona, con el usuario que lo cargó. */
    public void insertar(int idZona, int idTipoCombustible, double cargaTHa, LocalDate fecha, int idUsuario,
                         String observaciones) throws SQLException {
        String sql = "INSERT INTO relevamiento_combustible (id_zona, id_tipo_combustible, carga_t_ha, fecha_relevamiento, "
                + "id_usuario, observaciones) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, idZona);
            ps.setInt(2, idTipoCombustible);
            ps.setBigDecimal(3, ConexionBD.decimal(cargaTHa, 2));
            ps.setObject(4, fecha);
            ps.setInt(5, idUsuario);
            ps.setString(6, observaciones);
            ps.executeUpdate();
        }
    }

    /**
     * Último relevamiento de la zona que no sea posterior a la fecha indicada. Filtro por fecha
     * (y no uso directamente la vista v_combustible_vigente) porque al calcular el índice de un día
     * pasado necesito el combustible que había en ese momento. Si hay dos el mismo día, desempato
     * por id para quedarme con el cargado último.
     */
    public Optional<Relevamiento> vigente(int idZona, LocalDate fecha) throws SQLException {
        String sql = "SELECT r.id_zona, t.id_tipo_combustible, t.nombre, t.factor_inflamabilidad, r.carga_t_ha, "
                + "r.fecha_relevamiento FROM relevamiento_combustible r "
                + "JOIN tipo_combustible t ON t.id_tipo_combustible = r.id_tipo_combustible "
                + "WHERE r.id_zona = ? AND r.fecha_relevamiento <= ? "
                + "ORDER BY r.fecha_relevamiento DESC, r.id_relevamiento DESC LIMIT 1";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, idZona);
            ps.setObject(2, fecha);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                TipoCombustible tipo = new TipoCombustible(rs.getInt(2), rs.getString(3), rs.getDouble(4));
                return Optional.of(new Relevamiento(rs.getInt(1), tipo, rs.getDouble(5), rs.getObject(6, LocalDate.class)));
            }
        }
    }
}
