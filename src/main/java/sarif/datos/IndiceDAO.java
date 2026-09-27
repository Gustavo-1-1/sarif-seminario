package sarif.datos;

import sarif.modelo.IndiceRiesgo;
import sarif.modelo.NivelRiesgo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a los índices de riesgo diarios (CU09). Guardo un índice por zona y por día, con sus tres
 * componentes, y los consulto por fecha para el tablero y para sugerir despliegues (CU10).
 * Uso sentencias preparadas en todas las consultas para evitar inyección SQL.
 */
public class IndiceDAO {

    private final Connection cn;

    // La conexión me la pasa el servicio, que es el que decide cuándo confirmar o revertir la transacción.
    public IndiceDAO(Connection cn) {
        this.cn = cn;
    }

    /**
     * Registra el índice; si ya existía el de la zona y la fecha, lo reemplaza. Para eso uso
     * ON DUPLICATE KEY UPDATE sobre la clave única (id_zona, fecha): si recalculo el índice del día
     * (por ejemplo, porque llegó meteorología nueva) se pisa el registro en vez de dar error o duplicarse.
     * También actualizo fecha_hora_calculo para saber cuándo fue el último cálculo.
     */
    public void guardar(IndiceRiesgo i, NivelRiesgo nivel) throws SQLException {
        String sql = "INSERT INTO indice_riesgo (id_zona, fecha, comp_historica, comp_meteorologica, comp_combustible, "
                + "valor_final, id_nivel_riesgo) VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE comp_historica = VALUES(comp_historica), "
                + "comp_meteorologica = VALUES(comp_meteorologica), comp_combustible = VALUES(comp_combustible), "
                + "valor_final = VALUES(valor_final), id_nivel_riesgo = VALUES(id_nivel_riesgo), "
                + "fecha_hora_calculo = NOW()";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, i.idZona());
            ps.setObject(2, i.fecha());
            // Redondeo a la escala de cada columna DECIMAL (3 decimales las componentes, 2 el valor final).
            ps.setBigDecimal(3, ConexionBD.decimal(i.compHistorica(), 3));
            ps.setBigDecimal(4, ConexionBD.decimal(i.compMeteorologica(), 3));
            ps.setBigDecimal(5, ConexionBD.decimal(i.compCombustible(), 3));
            ps.setBigDecimal(6, ConexionBD.decimal(i.valorFinal(), 2));
            ps.setInt(7, nivel.id());
            ps.executeUpdate();
        }
    }

    /** Índices de la fecha con el nombre de la zona y del nivel, ordenados de mayor a menor riesgo. */
    public List<IndiceRiesgo> listarPorFecha(LocalDate fecha) throws SQLException {
        String sql = "SELECT i.id_indice, i.id_zona, z.nombre, i.fecha, i.comp_historica, i.comp_meteorologica, "
                + "i.comp_combustible, i.valor_final, n.nombre "
                + "FROM indice_riesgo i JOIN zona_vigilancia z ON z.id_zona = i.id_zona "
                + "JOIN nivel_riesgo n ON n.id_nivel_riesgo = i.id_nivel_riesgo "
                + "WHERE i.fecha = ? ORDER BY i.valor_final DESC";
        List<IndiceRiesgo> lista = new ArrayList<>();
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setObject(1, fecha);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new IndiceRiesgo(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getObject(4, LocalDate.class),
                            rs.getDouble(5), rs.getDouble(6), rs.getDouble(7), rs.getDouble(8), rs.getString(9)));
                }
            }
        }
        return lista;
    }

    /**
     * Índice de una zona en una fecha, con su id y el nombre del nivel. Lo uso para generar las alertas de
     * riesgo, que comparan el índice del día con el del día anterior.
     */
    public Optional<IndiceRiesgo> obtener(int idZona, LocalDate fecha) throws SQLException {
        String sql = "SELECT i.id_indice, i.id_zona, z.nombre, i.fecha, i.comp_historica, i.comp_meteorologica, "
                + "i.comp_combustible, i.valor_final, n.nombre "
                + "FROM indice_riesgo i JOIN zona_vigilancia z ON z.id_zona = i.id_zona "
                + "JOIN nivel_riesgo n ON n.id_nivel_riesgo = i.id_nivel_riesgo "
                + "WHERE i.id_zona = ? AND i.fecha = ?";
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setInt(1, idZona);
            ps.setObject(2, fecha);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new IndiceRiesgo(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getObject(4, LocalDate.class),
                        rs.getDouble(5), rs.getDouble(6), rs.getDouble(7), rs.getDouble(8), rs.getString(9)));
            }
        }
    }

    // Total de índices registrados; lo uso en las pruebas de integración.
    public int contar() throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement("SELECT COUNT(*) FROM indice_riesgo"); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
