package sarif.datos;

import sarif.modelo.Confianza;
import sarif.modelo.FocoCalor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Acceso a la tabla foco_calor: verifico si un foco ya estaba, lo inserto durante la sincronización (CU07)
 * o la importación histórica (RFS08) y cuento focos para la componente histórica del índice (CU09).
 * Uso sentencias preparadas para no concatenar valores en el SQL (evito inyección SQL y problemas de formato).
 */
public class FocoDAO {

    private final Connection cn;

    // Recibo la conexión por constructor para que el servicio maneje la transacción de todo el lote.
    public FocoDAO(Connection cn) {
        this.cn = cn;
    }

    /**
     * Busca el foco por su identidad natural: posición, fecha y hora de adquisición y satélite
     * (FIRMS no da un id propio). Así no repito focos si sincronizo dos veces el mismo período.
     * Las coordenadas las mando como BigDecimal con 5 decimales, la misma escala de la columna DECIMAL,
     * porque comparar un double con = podría fallar por errores de redondeo.
     */
    public boolean existe(FocoCalor f) throws SQLException {
        String sql = "SELECT COUNT(*) FROM foco_calor "
                + "WHERE latitud = ? AND longitud = ? AND fecha_hora_utc = ? AND satelite = ?";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setBigDecimal(1, ConexionBD.decimal(f.latitud(), 5));
            ps.setBigDecimal(2, ConexionBD.decimal(f.longitud(), 5));
            ps.setObject(3, f.fechaHoraUtc());
            ps.setString(4, f.satelite());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /**
     * Inserta el foco asociado a la sincronización que lo trajo (para la auditoría) y devuelve el id
     * generado, que después necesito para registrar las alertas que ese foco dispare.
     */
    public long insertar(FocoCalor f, int idSincronizacion) throws SQLException {
        String sql = "INSERT INTO foco_calor (id_zona, id_sincronizacion, latitud, longitud, fecha_hora_utc, "
                + "satelite, instrumento, potencia_frp_mw, confianza) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        // Pido las claves generadas para leer el id autoincremental con getGeneratedKeys().
        try (PreparedStatement ps = cn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, f.idZona());
            ps.setInt(2, idSincronizacion);
            ps.setBigDecimal(3, ConexionBD.decimal(f.latitud(), 5));
            ps.setBigDecimal(4, ConexionBD.decimal(f.longitud(), 5));
            ps.setObject(5, f.fechaHoraUtc());
            ps.setString(6, f.satelite());
            ps.setString(7, f.instrumento());
            // La potencia (FRP) puede no venir en el archivo: en ese caso guardo NULL.
            if (f.potenciaFrpMw() == null) {
                ps.setNull(8, java.sql.Types.DECIMAL);
            } else {
                ps.setBigDecimal(8, ConexionBD.decimal(f.potenciaFrpMw(), 2));
            }
            ps.setString(9, f.confianza().name());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Cuenta los focos de la zona en la ventana estacional: los días alrededor de la misma fecha
     * en cada una de las temporadas anteriores (componente histórica del índice, CU09).
     * Por ejemplo, con ventana de 15 días y 5 temporadas, para el 20/01/2026 cuento del 05/01 al 04/02
     * de 2025, 2024, ..., 2021. Reutilizo la misma sentencia preparada cambiando solo los parámetros.
     */
    public int contarVentanaHistorica(int idZona, LocalDate fecha, int ventanaDias, int temporadas) throws SQLException {
        String sql = "SELECT COUNT(*) FROM foco_calor WHERE id_zona = ? AND fecha_hora_utc >= ? AND fecha_hora_utc < ?";
        int total = 0;
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            for (int k = 1; k <= temporadas; k++) {
                LocalDate centro = fecha.minusYears(k);
                ps.setInt(1, idZona);
                ps.setObject(2, centro.minusDays(ventanaDias).atStartOfDay());
                // Uso "< día siguiente a las 00:00" en lugar de "<= último día" para incluir todo ese día completo.
                ps.setObject(3, centro.plusDays(ventanaDias + 1L).atStartOfDay());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    total += rs.getInt(1);
                }
            }
        }
        return total;
    }

    /**
     * Focos detectados entre dos fechas (incluidas, según la fecha UTC de adquisición), de todas las zonas.
     * Los usan las alertas de riesgo (focos recientes de cada zona) y el mapa de riesgo.
     */
    public List<FocoCalor> listarEntre(LocalDate desde, LocalDate hasta) throws SQLException {
        String sql = "SELECT id_foco, id_zona, latitud, longitud, fecha_hora_utc, satelite, instrumento, potencia_frp_mw, "
                + "confianza FROM foco_calor WHERE fecha_hora_utc >= ? AND fecha_hora_utc < ? ORDER BY fecha_hora_utc";
        List<FocoCalor> lista = new ArrayList<>();
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setObject(1, desde.atStartOfDay());
            ps.setObject(2, hasta.plusDays(1).atStartOfDay());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Double frp = rs.getObject("potencia_frp_mw") == null ? null : rs.getDouble("potencia_frp_mw");
                    lista.add(new FocoCalor(rs.getLong("id_foco"), rs.getInt("id_zona"), rs.getDouble("latitud"),
                            rs.getDouble("longitud"), rs.getObject("fecha_hora_utc", LocalDateTime.class),
                            rs.getString("satelite"), rs.getString("instrumento"), frp,
                            Confianza.valueOf(rs.getString("confianza"))));
                }
            }
        }
        return lista;
    }

    // Total de focos registrados; lo uso en las pruebas de integración para comprobar que no se duplican.
    public int contar() throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement("SELECT COUNT(*) FROM foco_calor"); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
