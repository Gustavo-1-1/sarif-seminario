package sarif.fuentes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sarif.modelo.RegistroFwi;
import sarif.modelo.Zona;

import javax.imageio.ImageIO;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas unitarias del índice FWI que SARIF trae ya calculado del servicio global de Copernicus (PU-38).
 *
 * <p>Verifico las tres cosas que puedo probar sin red: que la consulta al WMS quede bien armada (capa,
 * formato de datos, rectángulo de la zona con longitud primero y fecha del día), que el promedio de las
 * celdas del GeoTIFF descarte las celdas sin dato, y que la escala de peligro de GWIS clasifique cada
 * valor en su clase. El GeoTIFF de prueba lo armo en memoria con ImageIO, el mismo lector que usa la
 * fuente, así la prueba no depende del servicio.</p>
 */
class FuenteFwiTest {

    private static Zona zonaDePrueba() {
        Zona z = new Zona();
        z.setId(4);
        z.setNombre("Caviahue");
        z.setLatitudMin(-37.90);
        z.setLatitudMax(-37.80);
        z.setLongitudMin(-71.10);
        z.setLongitudMax(-70.95);
        return z;
    }

    /** GeoTIFF de una banda de punto flotante, como el que devuelve el WMS con FORMAT=image/tiff. */
    private static byte[] tiff(float[][] valores) throws IOException {
        int alto = valores.length;
        int ancho = valores[0].length;
        ComponentColorModel modelo = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY),
                false, false, Transparency.OPAQUE, DataBuffer.TYPE_FLOAT);
        WritableRaster raster = modelo.createCompatibleWritableRaster(ancho, alto);
        for (int y = 0; y < alto; y++) {
            for (int x = 0; x < ancho; x++) {
                raster.setSample(x, y, 0, valores[y][x]);
            }
        }
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(modelo, raster, false, null), "TIFF", salida),
                "El JDK tiene que poder escribir TIFF de punto flotante para esta prueba.");
        return salida.toByteArray();
    }

    @Test
    @DisplayName("PU-38: la consulta a GWIS pide el dato del FWI de la zona para el día indicado")
    void armaLaConsulta() {
        String url = FuenteFwi.url(zonaDePrueba(), LocalDate.of(2026, 1, 20));
        assertTrue(url.startsWith(FuenteFwi.SERVIDOR + "?"), "la dirección tiene que ser la del WMS de GWIS");
        assertTrue(url.contains("LAYERS=" + FuenteFwi.CAPA), "tiene que pedir la capa del FWI del modelo ECMWF");
        // Sin image/tiff el servidor devolvería la imagen coloreada de la leyenda, no el valor.
        assertTrue(url.contains("FORMAT=image/tiff"), "tiene que pedir los datos y no una imagen de la leyenda");
        assertTrue(url.contains("SRS=EPSG:4326"), "las coordenadas van en grados");
        // BBOX del WMS en EPSG:4326 versión 1.1.1: longitud primero, con el margen mínimo de 0,02°.
        assertTrue(url.contains("BBOX=-71.12000,-37.92000,-70.93000,-37.78000"), "rectángulo inesperado: " + url);
        assertTrue(url.contains("TIME=2026-01-20"), "tiene que pedir el día de la jornada");
    }

    @Test
    @DisplayName("PU-38: el promedio de las celdas descarta las que vienen sin dato")
    void promediaLasCeldasConDato() throws IOException, FuenteNoDisponibleException {
        // Cuatro celdas con dato (22, 24, 26 y 28) y dos sentinelas de "sin dato" del servicio.
        Optional<Double> valor = FuenteFwi.promedio(tiff(new float[][]{{22f, 24f, -9999f}, {26f, 28f, Float.NaN}}));
        assertEquals(25.0, valor.orElseThrow(), 0.001);

        // Una fecha que el modelo todavía no cubre devuelve todas las celdas sin dato: no hay FWI.
        assertTrue(FuenteFwi.promedio(tiff(new float[][]{{-9999f, -9999f}, {-9999f, -9999f}})).isEmpty());

        // Si el WMS responde con una excepción XML en lugar del GeoTIFF, la fuente lo informa como caída.
        byte[] excepcion = "<ServiceExceptionReport><ServiceException>LayerNotDefined</ServiceException>"
                .getBytes(StandardCharsets.UTF_8);
        FuenteNoDisponibleException e = assertThrows(FuenteNoDisponibleException.class,
                () -> FuenteFwi.promedio(excepcion));
        assertTrue(e.getMessage().contains("LayerNotDefined"), "el motivo del servicio tiene que llegar al operador");
    }

    @Test
    @DisplayName("PU-38: la escala de peligro de GWIS clasifica el FWI en sus siete clases")
    void clasificaElPeligro() {
        assertEquals("MUY BAJO", RegistroFwi.clase(0.0));
        assertEquals("MUY BAJO", RegistroFwi.clase(5.19));
        assertEquals("BAJO", RegistroFwi.clase(5.2));
        assertEquals("MODERADO", RegistroFwi.clase(11.2));
        assertEquals("ALTO", RegistroFwi.clase(21.3));
        assertEquals("ALTO", RegistroFwi.clase(37.98));
        assertEquals("MUY ALTO", RegistroFwi.clase(38.0));
        assertEquals("EXTREMO", RegistroFwi.clase(50.0));
        assertEquals("EXTREMO", RegistroFwi.clase(70.0));
        assertEquals("MUY EXTREMO", RegistroFwi.clase(90.94));
    }
}
