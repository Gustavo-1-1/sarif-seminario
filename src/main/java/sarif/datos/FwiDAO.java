package sarif.datos;

import sarif.modelo.RegistroFwi;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a los índices FWI que SARIF trae de GWIS. Igual que NdviDAO, recibo la conexión para que el
 * servicio maneje la transacción de todo el lote.
 */
public class FwiDAO extends DaoBase {

    private static final String SELECT = "SELECT id_zona, fecha, valor, modelo FROM registro_fwi ";

    public FwiDAO(Connection cn) {
        super(cn);
    }

    /**
     * Guardo el FWI del día; si ya tenía el de esa zona y fecha, lo reemplazo, porque el pronóstico del
     * servicio se corrige cada día. Devuelvo true si el registro era nuevo: con ON DUPLICATE KEY UPDATE,
     * MySQL informa 1 fila afectada al insertar y 2 al actualizar.
     */
    public boolean guardar(RegistroFwi r) throws SQLException {
        return actualizar("INSERT INTO registro_fwi (id_zona, fecha, valor, modelo) VALUES (?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE valor = VALUES(valor), modelo = VALUES(modelo), fecha_hora_carga = NOW()",
                r.idZona(), r.fecha(), ConexionBD.decimal(r.valor(), 2), r.modelo()) == 1;
    }

    /** FWI más reciente de la zona hasta la fecha indicada (para revisar jornadas pasadas). */
    public Optional<RegistroFwi> ultimo(int idZona, LocalDate hasta) throws SQLException {
        return consultarUno(SELECT + "WHERE id_zona = ? AND fecha <= ? ORDER BY fecha DESC LIMIT 1", FwiDAO::fwi, idZona, hasta);
    }

    /** FWI día por día de una zona, en orden, para acompañar el pronóstico extendido. */
    public List<RegistroFwi> entre(int idZona, LocalDate desde, LocalDate hasta) throws SQLException {
        return consultar(SELECT + "WHERE id_zona = ? AND fecha BETWEEN ? AND ? ORDER BY fecha", FwiDAO::fwi, idZona, desde, hasta);
    }

    private static RegistroFwi fwi(ResultSet rs) throws SQLException {
        return new RegistroFwi(rs.getInt(1), rs.getObject(2, LocalDate.class), rs.getDouble(3), rs.getString(4));
    }
}
