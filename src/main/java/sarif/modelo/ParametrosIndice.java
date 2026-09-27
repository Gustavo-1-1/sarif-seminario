package sarif.modelo;

/**
 * Parámetros configurables del índice de riesgo (tabla parametro): los pesos de cada componente,
 * la ventana de días y la cantidad de temporadas para la componente histórica, y los valores de
 * saturación de focos y carga de combustible de referencia para normalizar.
 * Los agrupo en un record para pasarlos juntos al calculador en lugar de leerlos uno por uno.
 */
public record ParametrosIndice(double pesoHistorico, double pesoMeteorologico, double pesoCombustible,
                               int ventanaDias, int temporadas, double saturacionFocos, double cargaReferencia) {

    /**
     * Valores del diseño (tabla 4 del documento). Los uso cuando un parámetro no está cargado
     * en la base, así el cálculo nunca se queda sin un valor.
     */
    public static ParametrosIndice porDefecto() {
        return new ParametrosIndice(0.25, 0.45, 0.30, 15, 5, 30, 30);
    }
}
