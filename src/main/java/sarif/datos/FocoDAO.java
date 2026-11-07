package sarif.datos;

import sarif.modelo.Confianza;
import sarif.modelo.FocoCalor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Acceso a la tabla foco_calor: verifico si un foco ya estaba, lo inserto durante la sincronización (CU07)
 * o la importación histórica (RFS08) y cuento focos para la componente histórica del índice (CU09).
 * Las sentencias son preparadas para no concatenar valores en el SQL (evito inyección SQL y problemas de formato).
 */
public class FocoDAO extends DaoBase {

    // Recibo la conexión por constructor para que el servicio maneje la transacción de todo el lote.
    public FocoDAO(Connection cn) {
        super(cn);
    }

    /**
     * Busca el foco por su identidad natural: posición, fecha y hora de adquisición y satélite
     * (FIRMS no da un id propio). Así no repito focos si sincronizo dos veces el mismo período.
     * Las coordenadas las mando como BigDecimal con 5 decimales, la misma escala de la columna DECIMAL,
     * porque comparar un double con = podría fallar por errores de redondeo.
     */
    public boolean existe(FocoCalor f) throws SQLException {
        return existe("SELECT COUNT(*) FROM foco_calor WHERE latitud = ? AND longitud = ? AND fecha_hora_utc = ? AND satelite = ?",
                ConexionBD.decimal(f.latitud(), 5), ConexionBD.decimal(f.longitud(), 5), f.fechaHoraUtc(), f.satelite());
    }

    /**
     * Inserta el foco asociado a la sincronización que lo trajo (para la auditoría) y devuelve el id
     * generado, que después necesito para registrar las alertas que ese foco dispare.
     * La potencia (FRP) puede no venir en el archivo: en ese caso se guarda NULL.
     */
    public long insertar(FocoCalor f, int idSincronizacion) throws SQLException {
        return insertarConClave("INSERT INTO foco_calor (id_zona, id_sincronizacion, latitud, longitud, fecha_hora_utc, "
                        + "satelite, instrumento, potencia_frp_mw, confianza) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                f.idZona(), idSincronizacion, ConexionBD.decimal(f.latitud(), 5), ConexionBD.decimal(f.longitud(), 5),
                f.fechaHoraUtc(), f.satelite(), f.instrumento(), decimal(f.potenciaFrpMw(), 2), f.confianza());
    }

    /**
     * Cuenta los focos de la zona en la ventana estacional: los días alrededor de la misma fecha
     * en cada una de las temporadas anteriores (componente histórica del índice, CU09).
     * Por ejemplo, con ventana de 15 días y 5 temporadas, para el 20/01/2026 cuento del 05/01 al 04/02
     * de 2025, 2024, ..., 2021. Acá no uso los métodos de DaoBase porque reutilizo la misma sentencia
     * preparada en las cinco vueltas, cambiando solo los parámetros.
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
        } catch (SQLException e) {
            throw DatosException.traducir(e);
        }
        return total;
    }

    /**
     * Focos detectados entre dos fechas (incluidas, según la fecha UTC de adquisición), de todas las zonas.
     * Los usan las alertas de riesgo (focos recientes de cada zona) y el mapa de riesgo.
     */
    public List<FocoCalor> listarEntre(LocalDate desde, LocalDate hasta) throws SQLException {
        return consultar("SELECT id_foco, id_zona, latitud, longitud, fecha_hora_utc, satelite, instrumento, potencia_frp_mw, "
                        + "confianza FROM foco_calor WHERE fecha_hora_utc >= ? AND fecha_hora_utc < ? ORDER BY fecha_hora_utc",
                FocoDAO::foco, desde.atStartOfDay(), hasta.plusDays(1).atStartOfDay());
    }

    // Total de focos registrados; lo uso en las pruebas de integración para comprobar que no se duplican.
    public int contar() throws SQLException {
        return entero("SELECT COUNT(*) FROM foco_calor");
    }

    private static FocoCalor foco(ResultSet rs) throws SQLException {
        Double frp = rs.getObject("potencia_frp_mw") == null ? null : rs.getDouble("potencia_frp_mw");
        return new FocoCalor(rs.getLong("id_foco"), rs.getInt("id_zona"), rs.getDouble("latitud"),
                rs.getDouble("longitud"), rs.getObject("fecha_hora_utc", LocalDateTime.class),
                rs.getString("satelite"), rs.getString("instrumento"), frp,
                Confianza.valueOf(rs.getString("confianza")));
    }
}
