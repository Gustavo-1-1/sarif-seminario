package sarif.fuentes;

import sarif.modelo.Confianza;
import sarif.modelo.FocoCalor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lee el CSV del servicio NASA FIRMS (productos VIIRS y MODIS) y lo convierte en objetos FocoCalor.
 * Ubico las columnas por nombre y no por posición, así el orden en el archivo no importa (VIIRS y
 * MODIS traen columnas distintas). Cualquier formato raro lo informo como FuenteNoDisponibleException,
 * que corresponde al flujo S2 del CU07. Es una clase utilitaria, por eso es final y con constructor privado.
 */
public final class LectorCsvFirms {

    private static final String[] OBLIGATORIAS = {"latitude", "longitude", "acq_date", "acq_time", "satellite", "confidence"};

    private LectorCsvFirms() {
    }

    /** Convierto el texto CSV completo en la lista de focos; si falta una columna obligatoria o una línea está mal, lanzo la excepción. */
    public static List<FocoCalor> leer(String csv) throws FuenteNoDisponibleException {
        // 1) Separo en líneas (\R acepta tanto \n como \r\n) y controlo que haya algo.
        String[] lineas = csv == null ? new String[0] : csv.strip().split("\\R");
        if (lineas.length == 0 || lineas[0].isBlank()) {
            throw new FuenteNoDisponibleException("Formato inesperado de la respuesta satelital: respuesta vacía.");
        }
        // 2) Verifico que estén todas las columnas obligatorias. Si el servicio devuelve un mensaje de
        //    error en texto plano (por ejemplo, clave inválida), cae acá y lo muestro resumido.
        List<String> encabezado = Arrays.asList(lineas[0].trim().toLowerCase().split(","));
        for (String columna : OBLIGATORIAS) {
            if (!encabezado.contains(columna)) {
                throw new FuenteNoDisponibleException("Formato inesperado de la respuesta satelital: falta la columna "
                        + columna + ". Respuesta: " + resumir(lineas[0]));
            }
        }
        // 3) Busco la posición de cada columna. instrument y frp son opcionales (quedan en -1 si no están).
        int iLat = encabezado.indexOf("latitude");
        int iLon = encabezado.indexOf("longitude");
        int iFecha = encabezado.indexOf("acq_date");
        int iHora = encabezado.indexOf("acq_time");
        int iSatelite = encabezado.indexOf("satellite");
        int iConfianza = encabezado.indexOf("confidence");
        int iInstrumento = encabezado.indexOf("instrument");
        int iFrp = encabezado.indexOf("frp");
        // type solo viene en el archivo histórico (productos _SP): 0 es fuego de vegetación, 1 volcán activo,
        // 2 otra fuente fija en tierra (por ejemplo, una industria) y 3 una fuente en el mar.
        int iTipo = encabezado.indexOf("type");
        // Si no viene la columna instrument, lo deduzco del encabezado: solo VIIRS trae bright_ti4.
        String instrumentoPorEncabezado = encabezado.contains("bright_ti4") ? "VIIRS" : "MODIS";

        // 4) Recorro las líneas de datos y armo un foco por cada una.
        List<FocoCalor> focos = new ArrayList<>();
        for (int n = 1; n < lineas.length; n++) {
            if (lineas[n].isBlank()) {
                continue;
            }
            String[] c = lineas[n].split(",", -1);
            // Descarto lo que FIRMS ya identificó como algo que no es un incendio. Me importa sobre todo
            // por el volcán Copahue, que está dentro de la zona Caviahue: si lo guardara, generaría alertas
            // y le subiría la componente histórica al índice sin que haya fuego.
            if (iTipo >= 0 && iTipo < c.length && !c[iTipo].isBlank() && !c[iTipo].trim().equals("0")) {
                continue;
            }
            try {
                String instrumento = iInstrumento >= 0 ? c[iInstrumento].trim().toUpperCase() : instrumentoPorEncabezado;
                // La confianza viene distinto según el instrumento: VIIRS usa letras (l/n/h) y MODIS un porcentaje.
                Confianza confianza = "VIIRS".equals(instrumento)
                        ? Confianza.desdeViirs(c[iConfianza])
                        : Confianza.desdeModis(Integer.parseInt(c[iConfianza].trim()));
                LocalDateTime fechaHora = LocalDateTime.of(LocalDate.parse(c[iFecha].trim()), hora(c[iHora]));
                Double frp = iFrp >= 0 && !c[iFrp].isBlank() ? Double.parseDouble(c[iFrp].trim()) : null;
                // El id y la zona los dejo sin asignar: los completa el servicio cuando verifica la pertenencia y lo guarda.
                focos.add(new FocoCalor(null, 0, Double.parseDouble(c[iLat].trim()), Double.parseDouble(c[iLon].trim()),
                        fechaHora, c[iSatelite].trim(), instrumento, frp, confianza));
            } catch (RuntimeException e) {
                // Agarro cualquier error de conversión (número mal escrito, fecha inválida, columnas de menos)
                // y lo informo indicando la línea, para que se sepa dónde está el problema.
                throw new FuenteNoDisponibleException("Formato inesperado en la línea " + (n + 1) + " de la respuesta satelital: "
                        + resumir(lineas[n]), e);
            }
        }
        return focos;
    }

    /**
     * La hora de adquisición viene como HHMM sin ceros iniciales: 436 equivale a las 04:36.
     * Por eso la relleno con ceros a la izquierda hasta tener 4 dígitos antes de separar hora y minutos.
     */
    static LocalTime hora(String valor) {
        String hhmm = String.format("%4s", valor.trim()).replace(' ', '0');
        return LocalTime.of(Integer.parseInt(hhmm.substring(0, 2)), Integer.parseInt(hhmm.substring(2, 4)));
    }

    /** Recorto el texto a 80 caracteres para que los mensajes de error no queden gigantes en pantalla. */
    private static String resumir(String texto) {
        return texto.length() > 80 ? texto.substring(0, 80) + "..." : texto;
    }
}
