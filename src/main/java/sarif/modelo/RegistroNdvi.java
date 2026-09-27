package sarif.modelo;

import java.time.LocalDate;

/**
 * NDVI medio de una zona calculado sobre una imagen de Sentinel-2 (RC05). El NDVI va de -1 a 1:
 * la vegetación sana y densa ronda 0,6 a 0,9, la vegetación seca o rala 0,2 a 0,4 y el suelo desnudo,
 * la roca o la nieve quedan cerca de 0 o por debajo. Guardo cuántos píxeles despejados tuvo la imagen
 * para saber qué tan representativo es el promedio.
 */
public record RegistroNdvi(int idZona, LocalDate fechaImagen, double ndviMedio, int pixelesValidos, double porcentajeValido) {

    public RegistroNdvi conZona(int idZona) {
        return new RegistroNdvi(idZona, fechaImagen, ndviMedio, pixelesValidos, porcentajeValido);
    }
}
