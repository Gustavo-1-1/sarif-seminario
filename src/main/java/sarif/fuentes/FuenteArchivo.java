package sarif.fuentes;

import sarif.modelo.FocoCalor;
import sarif.modelo.OrigenDatos;
import sarif.modelo.RegistroMeteo;
import sarif.modelo.Zona;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Fuente de archivo, pensada para el modo sin conexión (RNF06): leo archivos CSV que tienen el mismo
 * formato que devuelven FIRMS y Open-Meteo, así reutilizo los mismos lectores. Si querés probar el
 * sistema sin internet, usá esta fuente.
 * Los focos del archivo no los filtro por área: devuelvo todos y es el servicio el que verifica si
 * cada uno cae dentro de la zona (igual que hace con los de la fuente remota).
 */
public class FuenteArchivo extends FuenteDeDatos {

    private final Path archivoFocos;
    private final Path archivoMeteo;

    /** Recibo las rutas de los dos CSV; alguna puede ser null si solo voy a usar uno (por ejemplo, en la importación histórica). */
    public FuenteArchivo(Path archivoFocos, Path archivoMeteo) {
        this.archivoFocos = archivoFocos;
        this.archivoMeteo = archivoMeteo;
    }

    /** Devuelvo los focos del archivo dentro del rango de fechas; el parámetro zona no lo uso a propósito. */
    @Override
    public List<FocoCalor> obtenerFocos(Zona zona, int dias, LocalDate hasta) throws FuenteNoDisponibleException {
        return filtrarPorFechas(obtenerTodosLosFocos(), dias, hasta);
    }

    /** Devuelvo todas las detecciones del archivo, sin filtrar por fecha; lo uso en la importación histórica (RFS08). */
    public List<FocoCalor> obtenerTodosLosFocos() throws FuenteNoDisponibleException {
        return LectorCsvFirms.leer(leer(archivoFocos));
    }

    /**
     * Devuelvo la meteorología de la zona para el día anterior, el de referencia y el siguiente.
     * Acá sí filtro por fechas porque el archivo puede traer más días que los que pediría a Open-Meteo.
     */
    @Override
    public List<RegistroMeteo> obtenerMeteo(Zona zona, LocalDate fechaReferencia) throws FuenteNoDisponibleException {
        LocalDate desde = fechaReferencia.minusDays(1);
        LocalDate hasta = fechaReferencia.plusDays(1);
        return LectorCsvOpenMeteo.leer(leer(archivoMeteo), zona, fechaReferencia).stream()
                .filter(m -> !m.fecha().isBefore(desde) && !m.fecha().isAfter(hasta))
                .toList();
    }

    @Override
    public OrigenDatos getOrigen() {
        return OrigenDatos.ARCHIVO;
    }

    /**
     * Leo el archivo completo en UTF-8. Si no se indicó o no se puede leer, lo informo como fuente
     * no disponible, igual que una falla de red, para que el servicio lo maneje del mismo modo.
     */
    private static String leer(Path archivo) throws FuenteNoDisponibleException {
        if (archivo == null) {
            throw new FuenteNoDisponibleException("No se indicó el archivo de datos.");
        }
        try {
            return Files.readString(archivo, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new FuenteNoDisponibleException("No se pudo leer el archivo " + archivo.toAbsolutePath(), e);
        }
    }
}
