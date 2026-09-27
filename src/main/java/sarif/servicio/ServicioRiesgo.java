package sarif.servicio;

import sarif.datos.ConexionBD;
import sarif.datos.ConfiguracionDAO;
import sarif.datos.FocoDAO;
import sarif.datos.IndiceDAO;
import sarif.datos.MeteoDAO;
import sarif.datos.RelevamientoDAO;
import sarif.datos.ZonaDAO;
import sarif.modelo.IndiceRiesgo;
import sarif.modelo.NivelRiesgo;
import sarif.modelo.ParametrosIndice;
import sarif.modelo.RegistroMeteo;
import sarif.modelo.Relevamiento;
import sarif.modelo.Zona;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Implementa el CU09 Calcular índice de riesgo diario. Esta clase se encarga de juntar los datos de la
 * base (parámetros, focos históricos, meteorología y relevamientos) y de guardar los resultados; la
 * cuenta en sí la delega en CalculadorRiesgo. Se ejecuta a mano o automáticamente al terminar el CU08,
 * y al final genera solo las alertas de riesgo del día.
 */
public class ServicioRiesgo {

    /**
     * Resultado del cálculo: los índices que registré, las zonas que omití por falta de datos (RFS20) y
     * cuántas alertas de riesgo nuevas generó el cálculo.
     */
    public record ResultadoCalculo(List<IndiceRiesgo> indices, List<String> zonasSinDatos, int alertasRiesgo) {

        /** Texto corto para mostrarle al operador cuántas zonas se calcularon y cuáles quedaron sin datos. */
        public String resumen() {
            String texto = "Índice calculado para " + indices.size() + " zona(s).";
            if (!zonasSinDatos.isEmpty()) {
                texto += " Sin datos meteorológicos: " + String.join(", ", zonasSinDatos) + ".";
            }
            if (alertasRiesgo > 0) {
                texto += " Alertas de riesgo nuevas: " + alertasRiesgo + ".";
            }
            return texto;
        }
    }

    private final ServicioAlertas servicioAlertas = new ServicioAlertas();

    /**
     * Calculo y guardo el índice de cada zona activa para la fecha indicada, en una sola transacción.
     * Si los pesos están mal configurados, CalculadorRiesgo lanza ValidacionException y no se guarda nada.
     */
    public ResultadoCalculo calcularIndices(LocalDate fecha) throws SQLException, ValidacionException {
        try (Connection cn = ConexionBD.obtener()) {
            // 1) Abro la transacción: o se guardan los índices de todas las zonas o de ninguna.
            cn.setAutoCommit(false);
            try {
                // 2) Leo los parámetros y los niveles de riesgo de la base. Al crear el calculador se validan los pesos.
                ConfiguracionDAO conf = new ConfiguracionDAO(cn);
                ParametrosIndice p = conf.parametrosIndice();
                CalculadorRiesgo calculador = new CalculadorRiesgo(p);
                List<NivelRiesgo> niveles = conf.nivelesRiesgo();
                FocoDAO focos = new FocoDAO(cn);
                MeteoDAO meteo = new MeteoDAO(cn);
                RelevamientoDAO relevamientos = new RelevamientoDAO(cn);
                IndiceDAO indices = new IndiceDAO(cn);

                // 3) Recorro cada zona activa.
                List<IndiceRiesgo> calculados = new ArrayList<>();
                List<String> sinDatos = new ArrayList<>();
                for (Zona zona : new ZonaDAO(cn).listarActivas()) {
                    // Sin meteorología del día no calculo: la anoto como zona sin datos (RFS20) y sigo con la próxima.
                    Optional<RegistroMeteo> registro = meteo.obtener(zona.getId(), fecha);
                    if (registro.isEmpty()) {
                        sinDatos.add(zona.getNombre());
                        continue;
                    }
                    // Focos de la ventana histórica (±ventanaDias alrededor de la fecha en las temporadas anteriores)
                    // y el relevamiento de combustible vigente, que puede no existir.
                    int cantidadFocos = focos.contarVentanaHistorica(zona.getId(), fecha, p.ventanaDias(), p.temporadas());
                    Relevamiento combustible = relevamientos.vigente(zona.getId(), fecha).orElse(null);

                    // 4) Calculo las tres componentes, el valor final y el nivel que le corresponde.
                    double h = calculador.componenteHistorica(cantidadFocos);
                    double m = calculador.componenteMeteorologica(registro.get());
                    double c = calculador.componenteCombustible(combustible);
                    double valor = calculador.valorFinal(h, m, c);
                    NivelRiesgo nivel = calculador.clasificar(valor, niveles);

                    // 5) Guardo el índice de la zona.
                    IndiceRiesgo indice = new IndiceRiesgo(null, zona.getId(), zona.getNombre(), fecha, h, m, c, valor,
                            nivel.nombre());
                    indices.guardar(indice, nivel);
                    calculados.add(indice);
                }
                // 6) Con los índices ya guardados, cruzo los datos y genero las alertas de riesgo (zona que
                //    subió a ALTO o EXTREMO, focos en zona de riesgo alto) dentro de la misma transacción.
                int alertas = servicioAlertas.generarAlertasDeRiesgo(cn, fecha);
                // 7) Si todo salió bien, confirmo la transacción.
                cn.commit();
                return new ResultadoCalculo(calculados, sinDatos, alertas);
            } catch (SQLException | ValidacionException e) {
                // Ante cualquier error revierto lo que se haya guardado y relanzo para que lo vea el operador.
                cn.rollback();
                throw e;
            }
        }
    }

    /** Devuelvo los índices ya calculados para una fecha (los usan el tablero y el despliegue). */
    public List<IndiceRiesgo> listarIndices(LocalDate fecha) throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return new IndiceDAO(cn).listarPorFecha(fecha);
        }
    }
}
