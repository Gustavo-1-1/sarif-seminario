package sarif.datos;

import sarif.modelo.RegistroNdvi;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Acceso a los NDVI de Sentinel-2 por zona (RC05). Igual que MeteoDAO, recibo la conexión para que el
 * servicio maneje la transacción de todo el lote.
 */
public class NdviDAO extends DaoBase {

    public NdviDAO(Connection cn) {
        super(cn);
    }

    /**
     * Guardo el NDVI; si ya tenía el de esa zona e imagen, lo actualizo. Devuelvo true si el registro era
     * nuevo: con ON DUPLICATE KEY UPDATE, MySQL informa 1 fila afectada al insertar y 2 al actualizar.
     */
    public boolean guardar(RegistroNdvi r) throws SQLException {
        return actualizar("INSERT INTO registro_ndvi (id_zona, fecha_imagen, ndvi_medio, pixeles_validos, porcentaje_valido) "
                        + "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE ndvi_medio = VALUES(ndvi_medio), "
                        + "pixeles_validos = VALUES(pixeles_validos), porcentaje_valido = VALUES(porcentaje_valido), "
                        + "fecha_hora_carga = NOW()",
                r.idZona(), r.fechaImagen(), ConexionBD.decimal(r.ndviMedio(), 3), r.pixelesValidos(),
                ConexionBD.decimal(r.porcentajeValido(), 1)) == 1;
    }

    /** NDVI más reciente de la zona con imagen hasta la fecha indicada (para revisar jornadas pasadas). */
    public Optional<RegistroNdvi> ultimo(int idZona, LocalDate hasta) throws SQLException {
        return consultarUno("SELECT id_zona, fecha_imagen, ndvi_medio, pixeles_validos, porcentaje_valido FROM registro_ndvi "
                        + "WHERE id_zona = ? AND fecha_imagen <= ? ORDER BY fecha_imagen DESC LIMIT 1",
                rs -> new RegistroNdvi(rs.getInt(1), rs.getObject(2, LocalDate.class), rs.getDouble(3), rs.getInt(4),
                        rs.getDouble(5)),
                idZona, hasta);
    }
}
