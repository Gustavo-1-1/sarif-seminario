package sarif.servicio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sarif.modelo.NivelRiesgo;
import sarif.modelo.ParametrosIndice;
import sarif.modelo.RegistroMeteo;
import sarif.modelo.Relevamiento;
import sarif.modelo.TipoCombustible;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pruebas unitarias del cálculo del índice de riesgo (PU-01 a PU-06 y PU-23, CU09).
 *
 * <p>Verifico por separado cada componente del índice (histórica H, meteorológica M y de combustible C),
 * el valor final ponderado, la clasificación en niveles y el rechazo de pesos mal configurados. Uso como
 * caso de referencia la zona de Caviahue en la jornada del 20/01/2026, que tiene que dar 79,13, el mismo
 * valor que presento en el informe. CalculadorRiesgo no accede a la base (los parámetros y los niveles se
 * los paso armados en memoria), así que estas pruebas corren sin MySQL.</p>
 */
class CalculadorRiesgoTest {

    /** Los mismos cuatro niveles que carga sql/02_datos.sql, para no depender de la base. */
    private static final List<NivelRiesgo> NIVELES = List.of(
            new NivelRiesgo(1, "BAJO", 0, 25, 1, ""),
            new NivelRiesgo(2, "MODERADO", 25, 50, 2, ""),
            new NivelRiesgo(3, "ALTO", 50, 75, 3, ""),
            new NivelRiesgo(4, "EXTREMO", 75, 100, 4, ""));

    private CalculadorRiesgo calculador;

    /** Creo un calculador nuevo antes de cada prueba con los parámetros por defecto (0,25 / 0,45 / 0,30). */
    @BeforeEach
    void crear() throws ValidacionException {
        calculador = new CalculadorRiesgo(ParametrosIndice.porDefecto());
    }

    /** Atajo para armar un registro meteorológico observado con temperatura, humedad, viento y lluvia. */
    private static RegistroMeteo meteo(double t, double hr, double v, double lluvia) {
        return new RegistroMeteo(1, LocalDate.of(2026, 1, 20), t, hr, v, lluvia, false);
    }

    @Test
    @DisplayName("PU-01 Componente histórica proporcional y acotada en 1")
    void componenteHistorica() {
        // H crece en proporción a los focos de la ventana (sobre la saturación de 30): 0 da 0, 15 da
        // la mitad y 28 (el caso de Caviahue) da 28/30.
        assertEquals(0.0, calculador.componenteHistorica(0), 1e-9);
        assertEquals(0.5, calculador.componenteHistorica(15), 1e-9);
        assertEquals(28 / 30.0, calculador.componenteHistorica(28), 1e-9);
        // Desde 30 focos queda en 1 y no sigue subiendo, aunque haya muchos más.
        assertEquals(1.0, calculador.componenteHistorica(30), 1e-9);
        assertEquals(1.0, calculador.componenteHistorica(90), 1e-9);
    }

    @Test
    @DisplayName("PU-02 Componente meteorológica: 1 en condiciones extremas y 0 en condiciones benignas")
    void componenteMeteorologicaExtremos() {
        // Los dos extremos: calor, sequedad y viento por encima de los topes dan 1; frío, humedad
        // alta y calma dan 0.
        assertEquals(1.0, calculador.componenteMeteorologica(meteo(38, 10, 60, 0)), 1e-9);
        assertEquals(0.0, calculador.componenteMeteorologica(meteo(8, 85, 0, 0)), 1e-9);
        // Jornada de prueba en Caviahue: 31,5 °C, 15 % y 46,5 km/h.
        assertEquals(0.93, calculador.componenteMeteorologica(meteo(31.5, 15, 46.5, 0)), 1e-9);
    }

    @Test
    @DisplayName("PU-03 La precipitación atenúa la componente meteorológica (x0,6 y x0,3)")
    void precipitacionAtenua() {
        // Tomo como base el mismo día sin lluvia y pruebo los dos cortes de la atenuación justo en
        // sus bordes: por debajo de 2 mm no cambia nada, de 2 a menos de 5 mm queda el 60 % y
        // desde 5 mm queda el 30 %.
        double seco = calculador.componenteMeteorologica(meteo(30, 20, 40, 0));
        assertEquals(seco, calculador.componenteMeteorologica(meteo(30, 20, 40, 1.9)), 1e-9);
        assertEquals(seco * 0.6, calculador.componenteMeteorologica(meteo(30, 20, 40, 2.0)), 1e-9);
        assertEquals(seco * 0.6, calculador.componenteMeteorologica(meteo(30, 20, 40, 4.9)), 1e-9);
        assertEquals(seco * 0.3, calculador.componenteMeteorologica(meteo(30, 20, 40, 5.0)), 1e-9);
    }

    @Test
    @DisplayName("PU-04 Componente de combustible y zona sin relevamiento")
    void componenteCombustible() {
        TipoCombustible pastizal = new TipoCombustible(1, "Pastizal", 0.90);
        TipoCombustible coniferas = new TipoCombustible(3, "Plantación de coníferas", 0.95);
        // Pastizal curado de Caviahue: 0,90 × 15,5 / 30 = 0,465.
        assertEquals(0.465, calculador.componenteCombustible(new Relevamiento(4, pastizal, 15.5, LocalDate.now())), 1e-9);
        // Una carga muy alta daría más de 1, pero la componente queda acotada en 1.
        assertEquals(1.0, calculador.componenteCombustible(new Relevamiento(1, coniferas, 45, LocalDate.now())), 1e-9);
        // Sin relevamiento, C vale 0: prefiero no inventar un dato que no tengo.
        assertEquals(0.0, calculador.componenteCombustible(null), 1e-9);
    }

    @Test
    @DisplayName("PU-05 Valor final ponderado y clasificación en los bordes de cada umbral")
    void valorFinalYClasificacion() {
        // Índice de Caviahue en la jornada con H = 28/30, M = 0,93 y C = 0,465: tiene que dar 79,13,
        // y con las tres componentes al máximo, exactamente 100.
        assertEquals(79.13, calculador.valorFinal(28 / 30.0, 0.93, 0.465), 1e-9);
        assertEquals(100.0, calculador.valorFinal(1, 1, 1), 1e-9);
        // Clasificación justo en los bordes: el mínimo de cada nivel está incluido y el máximo no,
        // salvo en EXTREMO, que también incluye el 100.
        assertEquals("BAJO", calculador.clasificar(24.99, NIVELES).nombre());
        assertEquals("MODERADO", calculador.clasificar(25, NIVELES).nombre());
        assertEquals("ALTO", calculador.clasificar(74.99, NIVELES).nombre());
        assertEquals("EXTREMO", calculador.clasificar(75, NIVELES).nombre());
        assertEquals("EXTREMO", calculador.clasificar(100, NIVELES).nombre());
    }

    @Test
    @DisplayName("PU-06 Pesos que no suman 1 son rechazados")
    void pesosInvalidos() {
        // 0,30 + 0,45 + 0,30 = 1,05: el calculador tiene que negarse a crearse, porque con esos pesos
        // el índice podría pasar de 100 (error de configuración del CU09).
        ParametrosIndice mal = new ParametrosIndice(0.30, 0.45, 0.30, 15, 5, 30, 30);
        assertThrows(ValidacionException.class, () -> new CalculadorRiesgo(mal));
    }

    @Test
    @DisplayName("PU-23 Humedad del suelo (RC06): 15 % de M si hay dato; sin dato, M no cambia")
    void humedadDelSuelo() {
        // Sequedad del suelo: 0,35 m³/m³ o más es 0, 0,10 o menos es 1 y en el medio es proporcional.
        assertEquals(0.0, calculador.factorSuelo(0.40), 1e-9);
        assertEquals(0.5, calculador.factorSuelo(0.225), 1e-9);
        assertEquals(1.0, calculador.factorSuelo(0.05), 1e-9);
        // Caviahue el 20/01 sin dato de suelo sigue dando 0,93, así el índice de 79,13 no cambia.
        assertEquals(0.93, calculador.componenteMeteorologica(meteo(31.5, 15, 46.5, 0)), 1e-9);
        // El mismo día con suelo seco sube a 0,85 · 0,93 + 0,15 = 0,9405; con suelo húmedo baja a 0,7905.
        RegistroMeteo seco = new RegistroMeteo(1, LocalDate.of(2026, 1, 20), 31.5, 15, 46.5, 0, false, 0.08);
        RegistroMeteo humedo = new RegistroMeteo(1, LocalDate.of(2026, 1, 20), 31.5, 15, 46.5, 0, false, 0.38);
        assertEquals(0.9405, calculador.componenteMeteorologica(seco), 1e-9);
        assertEquals(0.7905, calculador.componenteMeteorologica(humedo), 1e-9);
        // La lluvia se aplica después: con 5 mm queda el 30 % del valor con suelo.
        RegistroMeteo secoConLluvia = new RegistroMeteo(1, LocalDate.of(2026, 1, 20), 31.5, 15, 46.5, 5, false, 0.08);
        assertEquals(0.9405 * 0.3, calculador.componenteMeteorologica(secoConLluvia), 1e-9);
    }
}
