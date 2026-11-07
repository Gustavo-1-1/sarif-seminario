package sarif.servicio;

import sarif.modelo.FilaMes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Estacionalidad de los focos de calor: cuántos focos hubo en cada mes del año y cuál es la temporada crítica,
 * es decir, los meses consecutivos que concentran más focos. Le sirve a la guardia para planificar cuándo
 * reforzar la vigilancia y el despliegue preventivo (complementa el histórico de RFS18).
 * <p>
 * Los focos por mes se guardan en un arreglo de 12 posiciones porque el calendario tiene tamaño fijo y cada mes
 * se ubica directamente por su índice (0 = enero); las filas que se muestran en pantalla se arman en un ArrayList.
 * No accede a la base: recibe el arreglo ya leído por ReporteDAO, así se prueba con JUnit sin MySQL.
 */
public final class Estacionalidad {

    private static final int MESES_DEL_ANIO = 12;
    // Nombres de los meses en el mismo orden que el arreglo de focos. Es privado: un arreglo público y estático
    // se podría modificar desde afuera aunque sea final (final protege la referencia, no el contenido).
    private static final String[] MESES = {"Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio",
            "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"};

    private final int[] focosPorMes;
    private final int anios;
    private final int total;

    /**
     * @param focosPorMes focos de cada mes: [0] enero ... [11] diciembre
     * @param anios       cantidad de años con focos registrados, para el promedio anual (con 0 tomo 1)
     */
    public Estacionalidad(int[] focosPorMes, int anios) {
        if (focosPorMes == null || focosPorMes.length != MESES_DEL_ANIO) {
            throw new IllegalArgumentException("Se esperan los focos de los 12 meses del año.");
        }
        int suma = 0;
        for (int focos : focosPorMes) {
            if (focos < 0) {
                throw new IllegalArgumentException("La cantidad de focos de un mes no puede ser negativa.");
            }
            suma += focos;
        }
        // Copia defensiva: si quien me pasó el arreglo lo modifica después, no cambia este objeto.
        this.focosPorMes = focosPorMes.clone();
        this.anios = Math.max(anios, 1);
        this.total = suma;
    }

    public int total() {
        return total;
    }

    public int anios() {
        return anios;
    }

    /** Focos del mes indicado, de 1 (enero) a 12 (diciembre). */
    public int focos(int mes) {
        return focosPorMes[indice(mes)];
    }

    /** Nombre del mes, de 1 (enero) a 12 (diciembre). */
    public static String nombreMes(int mes) {
        return MESES[indice(mes)];
    }

    /**
     * Focos de los {@code largo} meses consecutivos que empiezan en {@code mesInicio}. La ventana es circular:
     * después de diciembre sigue enero, porque en la Patagonia la temporada de incendios cruza el fin de año.
     * El operador % devuelve el índice al principio del arreglo cuando se pasa del final.
     */
    public int focosEnVentana(int mesInicio, int largo) {
        validarLargo(largo);
        int inicio = indice(mesInicio);
        int suma = 0;
        for (int k = 0; k < largo; k++) {
            suma += focosPorMes[(inicio + k) % MESES_DEL_ANIO];
        }
        return suma;
    }

    /**
     * Mes (1 a 12) en que empieza la temporada crítica: la ventana de {@code largo} meses con más focos.
     * Pruebo los doce comienzos posibles y me quedo con el mayor; ante un empate, con el primero.
     */
    public int inicioTemporadaCritica(int largo) {
        int mejorMes = 1;
        int mejorSuma = -1;
        for (int mes = 1; mes <= MESES_DEL_ANIO; mes++) {
            int suma = focosEnVentana(mes, largo);
            if (suma > mejorSuma) {
                mejorSuma = suma;
                mejorMes = mes;
            }
        }
        return mejorMes;
    }

    /** Filas para la tabla y el gráfico: una por mes, con los meses de la temporada crítica marcados. */
    public List<FilaMes> filas(int largoTemporada) {
        int inicio = inicioTemporadaCritica(largoTemporada);
        List<FilaMes> filas = new ArrayList<>();
        for (int i = 0; i < MESES_DEL_ANIO; i++) {
            // Distancia circular desde el inicio de la temporada: si es menor que el largo, el mes está adentro.
            boolean critico = total > 0 && (i - (inicio - 1) + MESES_DEL_ANIO) % MESES_DEL_ANIO < largoTemporada;
            double porcentaje = total == 0 ? 0 : redondear(100.0 * focosPorMes[i] / total);
            filas.add(new FilaMes(MESES[i], focosPorMes[i], redondear((double) focosPorMes[i] / anios), porcentaje, critico));
        }
        return filas;
    }

    /** Frase para la pantalla, por ejemplo "Diciembre a febrero concentra 180 de 240 focos (75,0 %)". */
    public String resumen(int largoTemporada) {
        if (total == 0) {
            return "No hay focos registrados para calcular la estacionalidad. Importe el histórico desde Sincronización.";
        }
        int inicio = inicioTemporadaCritica(largoTemporada);
        int fin = (inicio - 1 + largoTemporada - 1) % MESES_DEL_ANIO + 1;
        int focos = focosEnVentana(inicio, largoTemporada);
        return String.format(Locale.forLanguageTag("es-AR"),
                "Temporada crítica: %s a %s concentra %d de %d focos (%.1f %%) en %d %s de registros. "
                        + "En esos meses conviene reforzar la vigilancia y el despliegue preventivo.",
                nombreMes(inicio), nombreMes(fin).toLowerCase(Locale.ROOT), focos, total, 100.0 * focos / total,
                anios, anios == 1 ? "año" : "años");
    }

    private static int indice(int mes) {
        if (mes < 1 || mes > MESES_DEL_ANIO) {
            throw new IllegalArgumentException("El mes debe estar entre 1 y 12: " + mes);
        }
        return mes - 1;
    }

    private static void validarLargo(int largo) {
        if (largo < 1 || largo > MESES_DEL_ANIO) {
            throw new IllegalArgumentException("La temporada debe tener entre 1 y 12 meses: " + largo);
        }
    }

    private static double redondear(double valor) {
        return Math.round(valor * 10) / 10.0;
    }
}
