package sarif.modelo;

/**
 * Una fila del reporte de desempeño (RFS19, CU13): para cada nivel de riesgo previsto, cuántos días-zona
 * se calcularon con ese nivel y en cuántos de ellos hubo focos. Si el índice anticipa bien, el porcentaje
 * de días con focos tiene que crecer de BAJO a EXTREMO.
 */
public record FilaDesempeno(String nivel, int diasZona, int diasConFocos, int focos) {

    /** Porcentaje de días-zona con al menos un foco; null si no hubo días con ese nivel. */
    public Double porcentajeConFocos() {
        return diasZona == 0 ? null : Math.round(1000.0 * diasConFocos / diasZona) / 10.0;
    }
}
