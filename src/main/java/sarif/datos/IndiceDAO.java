package sarif.datos;

import sarif.modelo.IndiceRiesgo;
import sarif.modelo.NivelRiesgo;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a los índices de riesgo diarios (CU09). Guardo un índice por zona y por día, con sus tres
 * componentes, y los consulto por fecha para el tablero y para sugerir despliegues (CU10).
 * Todas las consultas son sentencias preparadas, para evitar inyección SQL.
 */
public class IndiceDAO extends DaoBase {

    private static final String SELECT = "SELECT i.id_indice, i.id_zona, z.nombre, i.fecha, i.comp_historica, "
            + "i.comp_meteorologica, i.comp_combustible, i.valor_final, n.nombre "
            + "FROM indice_riesgo i JOIN zona_vigilancia z ON z.id_zona = i.id_zona "
            + "JOIN nivel_riesgo n ON n.id_nivel_riesgo = i.id_nivel_riesgo ";

    // La conexión me la pasa el servicio, que es el que decide cuándo confirmar o revertir la transacción.
    public IndiceDAO(Connection cn) {
        super(cn);
    }

    /**
     * Registra el índice; si ya existía el de la zona y la fecha, lo reemplaza. Para eso uso
     * ON DUPLICATE KEY UPDATE sobre la clave única (id_zona, fecha): si recalculo el índice del día
     * (por ejemplo, porque llegó meteorología nueva) se pisa el registro en vez de dar error o duplicarse.
     * También actualizo fecha_hora_calculo para saber cuándo fue el último cálculo.
     * Redondeo a la escala de cada columna DECIMAL (3 decimales las componentes, 2 el valor final).
     */
    public void guardar(IndiceRiesgo i, NivelRiesgo nivel) throws SQLException {
        actualizar("INSERT INTO indice_riesgo (id_zona, fecha, comp_historica, comp_meteorologica, comp_combustible, "
                        + "valor_final, id_nivel_riesgo) VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE comp_historica = VALUES(comp_historica), "
                        + "comp_meteorologica = VALUES(comp_meteorologica), comp_combustible = VALUES(comp_combustible), "
                        + "valor_final = VALUES(valor_final), id_nivel_riesgo = VALUES(id_nivel_riesgo), "
                        + "fecha_hora_calculo = NOW()",
                i.idZona(), i.fecha(), ConexionBD.decimal(i.compHistorica(), 3), ConexionBD.decimal(i.compMeteorologica(), 3),
                ConexionBD.decimal(i.compCombustible(), 3), ConexionBD.decimal(i.valorFinal(), 2), nivel.id());
    }

    /** Índices de la fecha con el nombre de la zona y del nivel, ordenados de mayor a menor riesgo. */
    public List<IndiceRiesgo> listarPorFecha(LocalDate fecha) throws SQLException {
        return consultar(SELECT + "WHERE i.fecha = ? ORDER BY i.valor_final DESC", IndiceDAO::indice, fecha);
    }

    /**
     * Índice de una zona en una fecha, con su id y el nombre del nivel. Lo uso para generar las alertas de
     * riesgo, que comparan el índice del día con el del día anterior.
     */
    public Optional<IndiceRiesgo> obtener(int idZona, LocalDate fecha) throws SQLException {
        return consultarUno(SELECT + "WHERE i.id_zona = ? AND i.fecha = ?", IndiceDAO::indice, idZona, fecha);
    }

    // Total de índices registrados; lo uso en las pruebas de integración.
    public int contar() throws SQLException {
        return entero("SELECT COUNT(*) FROM indice_riesgo");
    }

    private static IndiceRiesgo indice(ResultSet rs) throws SQLException {
        return new IndiceRiesgo(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getObject(4, LocalDate.class),
                rs.getDouble(5), rs.getDouble(6), rs.getDouble(7), rs.getDouble(8), rs.getString(9));
    }
}
