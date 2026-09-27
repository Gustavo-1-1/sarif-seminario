package sarif.servicio;

import sarif.modelo.NivelRiesgo;
import sarif.modelo.ParametrosIndice;
import sarif.modelo.RegistroMeteo;
import sarif.modelo.Relevamiento;

import java.util.List;

/**
 * Hace el cálculo del índice de riesgo según la tabla 4 del informe:
 *
 *   I = 100 · (pH·H + pM·M + pC·C), con H, M y C entre 0 y 1 (por defecto 0,25·H + 0,45·M + 0,30·C).
 *
 * Separé el cálculo de ServicioRiesgo a propósito: esta clase no accede a la base de datos,
 * así que la puedo probar con JUnit en forma aislada, sin tener MySQL levantado.
 */
public class CalculadorRiesgo {

    private final ParametrosIndice parametros;

    /**
     * Valido los pesos al construir el calculador: si no suman 1 el índice no tendría sentido,
     * así que lo trato como error de configuración (excepción del CU09). Dejo una tolerancia
     * de 0,001 por los errores de redondeo de los double.
     */
    public CalculadorRiesgo(ParametrosIndice parametros) throws ValidacionException {
        double suma = parametros.pesoHistorico() + parametros.pesoMeteorologico() + parametros.pesoCombustible();
        if (Math.abs(suma - 1.0) > 0.001) {
            throw new ValidacionException("Error de configuración: los pesos del índice suman " + suma + " y deben sumar 1.");
        }
        this.parametros = parametros;
    }

    /**
     * H: focos de la ventana estacional divididos por el valor de saturación, con tope 1.
     * La ventana son ±15 días alrededor de la misma fecha en las 5 temporadas anteriores, y la
     * saturación por defecto es 30 focos (con 30 o más, H ya vale 1).
     */
    public double componenteHistorica(int focos) {
        return limitar(focos / parametros.saturacionFocos());
    }

    /**
     * M: índice meteorológico simplificado, inspirado en el FWI canadiense (no es el FWI).
     * Normalizo temperatura, humedad y viento entre 0 y 1, los combino con pesos y después
     * atenúo el resultado si llovió, porque la lluvia humedece el combustible.
     * <p>
     * RC06: si el registro trae la humedad del suelo, le doy el 15 % de M para reflejar el estrés
     * hídrico de la vegetación. Si no la trae, calculo M como en la primera versión, así los datos
     * sin ese dato (la jornada de prueba, la carga manual) dan exactamente el mismo índice.
     */
    public double componenteMeteorologica(RegistroMeteo m) {
        double fTemperatura = limitar((m.temperaturaMaxC() - 10.0) / 25.0); // 10 °C -> 0 ; 35 °C -> 1
        double fHumedad = limitar((70.0 - m.humedadMinPct()) / 55.0);       // 70 % -> 0 ; 15 % -> 1
        double fViento = limitar(m.vientoMaxKmh() / 50.0);                  // 0 km/h -> 0 ; 50 km/h -> 1
        double valor = 0.35 * fTemperatura + 0.35 * fHumedad + 0.30 * fViento;
        if (m.humedadSueloM3m3() != null) {
            valor = 0.85 * valor + 0.15 * factorSuelo(m.humedadSueloM3m3());
        }
        // Atenuación por lluvia: con 5 mm o más me quedo con el 30 %; entre 2 y 5 mm, con el 60 %.
        if (m.precipitacionMm() >= 5.0) {
            valor *= 0.3;
        } else if (m.precipitacionMm() >= 2.0) {
            valor *= 0.6;
        }
        return limitar(valor);
    }

    /**
     * Sequedad del suelo entre 0 y 1. Con 0,35 m³/m³ o más (suelo cerca de capacidad de campo, típico
     * del deshielo) vale 0; con 0,10 m³/m³ o menos (cerca del punto de marchitez) vale 1.
     */
    public double factorSuelo(double humedadSueloM3m3) {
        return limitar((0.35 - humedadSueloM3m3) / 0.25);
    }

    /**
     * C: factor de inflamabilidad × (carga / carga de referencia), con tope 1 (la carga de referencia
     * por defecto es 30 t/ha). Si la zona no tiene relevamiento vale 0: prefiero no inventar un dato.
     */
    public double componenteCombustible(Relevamiento relevamiento) {
        if (relevamiento == null) {
            return 0;
        }
        return limitar(relevamiento.tipo().factorInflamabilidad() * relevamiento.cargaTHa() / parametros.cargaReferencia());
    }

    /** Combino las tres componentes con sus pesos y devuelvo el índice final entre 0 y 100, redondeado a dos decimales. */
    public double valorFinal(double h, double m, double c) {
        double valor = 100.0 * (parametros.pesoHistorico() * h + parametros.pesoMeteorologico() * m
                + parametros.pesoCombustible() * c);
        return Math.round(valor * 100.0) / 100.0;
    }

    /**
     * Devuelvo el nivel cuyo rango contiene el valor: [mínimo, máximo), salvo el nivel superior, que incluye
     * su máximo para que un índice de 100 no quede afuera. Con los niveles de la base queda:
     * BAJO [0,25), MODERADO [25,50), ALTO [50,75) y EXTREMO [75,100].
     */
    public NivelRiesgo clasificar(double valor, List<NivelRiesgo> niveles) {
        for (int i = 0; i < niveles.size(); i++) {
            NivelRiesgo n = niveles.get(i);
            boolean ultimo = i == niveles.size() - 1;
            if (valor >= n.umbralMin() && (valor < n.umbralMax() || (ultimo && valor <= n.umbralMax()))) {
                return n;
            }
        }
        throw new IllegalArgumentException("El valor " + valor + " no corresponde a ningún nivel de riesgo.");
    }

    /** Recorto el valor al intervalo [0, 1]; así ninguna componente puede pasarse del tope. */
    private static double limitar(double valor) {
        return Math.max(0.0, Math.min(1.0, valor));
    }
}
