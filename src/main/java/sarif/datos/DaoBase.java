package sarif.datos;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Superclase de todos los DAO de SARIF. Acá escribo una sola vez el recorrido JDBC que antes repetía cada DAO:
 * preparar la sentencia, cargar los parámetros en orden, ejecutarla, recorrer el resultado y cerrar todo con
 * try-with-resources. Cada DAO aporta solo lo que cambia: el SQL, los valores de los "?" y, al leer, cómo se
 * arma un objeto con una fila (un {@link MapeadorFila}). Es el patrón Template Method aplicado dentro del
 * patrón DAO: el algoritmo queda fijo en la superclase y las subclases completan los pasos variables.
 * <p>
 * Si MySQL rechaza una operación, la SQLException se traduce a una {@link DatosException} con un mensaje
 * entendible. Como DatosException extiende SQLException, los servicios siguen haciendo rollback igual que antes.
 * <p>
 * La clase es abstracta porque no representa ninguna tabla: no tiene sentido crear un DaoBase, solo heredarlo.
 * Recibe la conexión por constructor, igual que los DAO, para que el servicio decida la transacción.
 */
public abstract class DaoBase {

    protected final Connection cn;

    protected DaoBase(Connection cn) {
        this.cn = cn;
    }

    /**
     * Ejecuta un SELECT y arma un objeto por fila. Devuelvo un ArrayList porque no sé de antemano cuántas filas
     * va a traer la consulta; los parámetros, en cambio, llegan como arreglo porque su cantidad la fija el SQL.
     */
    protected <T> List<T> consultar(String sql, MapeadorFila<T> mapeador, Object... parametros) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            cargar(ps, parametros);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> filas = new ArrayList<>();
                while (rs.next()) {
                    filas.add(mapeador.mapear(rs));
                }
                return filas;
            }
        } catch (SQLException e) {
            throw DatosException.traducir(e);
        }
    }

    /** Como consultar, para las consultas que devuelven a lo sumo una fila (por clave, o con LIMIT 1). */
    protected <T> Optional<T> consultarUno(String sql, MapeadorFila<T> mapeador, Object... parametros) throws SQLException {
        List<T> filas = consultar(sql, mapeador, parametros);
        return filas.isEmpty() ? Optional.empty() : Optional.ofNullable(filas.get(0));
    }

    /** Primera columna de la primera fila como entero: para COUNT(*), MAX(id) y similares. Sin filas, 0. */
    protected int entero(String sql, Object... parametros) throws SQLException {
        return consultarUno(sql, rs -> rs.getInt(1), parametros).orElse(0);
    }

    /** Indica si un SELECT COUNT(*) da más de cero; lo uso para los controles de "ya existe". */
    protected boolean existe(String sql, Object... parametros) throws SQLException {
        return entero(sql, parametros) > 0;
    }

    /** Ejecuta un INSERT, UPDATE o DELETE y devuelve la cantidad de filas afectadas. */
    protected int actualizar(String sql, Object... parametros) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            cargar(ps, parametros);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw DatosException.traducir(e);
        }
    }

    /** Ejecuta un INSERT en una tabla con clave autoincremental y devuelve el id que le asignó MySQL. */
    protected long insertarConClave(String sql, Object... parametros) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            cargar(ps, parametros);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) {
                    throw new SQLException("La base no devolvió el identificador del registro insertado.");
                }
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw DatosException.traducir(e);
        }
    }

    /**
     * Ejecuta la misma sentencia para varias filas en un solo envío (addBatch). Cada fila es un arreglo con los
     * valores en el orden de los "?": el arreglo tiene tamaño fijo, que es la cantidad de columnas, y la lista
     * crece con la cantidad de filas. Devuelvo el arreglo con las filas afectadas por cada sentencia del lote.
     */
    protected int[] lote(String sql, List<Object[]> filas) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            for (Object[] fila : filas) {
                cargar(ps, fila);
                ps.addBatch();
            }
            return ps.executeBatch();
        } catch (SQLException e) {
            throw DatosException.traducir(e);
        }
    }

    /**
     * Valor para una columna DECIMAL que admite NULL: redondeado a la escala de la columna, o null si falta.
     * Un dato que no vino tiene que guardarse como NULL y no como 0 (0 mm de lluvia no es lo mismo que no saber).
     */
    protected static BigDecimal decimal(Double valor, int escala) {
        return valor == null ? null : ConexionBD.decimal(valor, escala);
    }

    /**
     * Carga los valores en los "?" de la sentencia, en orden: el elemento 0 del arreglo va en el primer "?".
     * Los null van como NULL de SQL y los enumerados por su nombre, que es como están guardados en la base.
     */
    private static void cargar(PreparedStatement ps, Object[] parametros) throws SQLException {
        for (int i = 0; i < parametros.length; i++) {
            Object valor = parametros[i];
            if (valor == null) {
                ps.setNull(i + 1, Types.NULL);
            } else if (valor instanceof Enum<?> constante) {
                ps.setString(i + 1, constante.name());
            } else {
                ps.setObject(i + 1, valor);
            }
        }
    }
}
