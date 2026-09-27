package sarif.fuentes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sarif.modelo.RegistroNdvi;
import sarif.modelo.Vertice;
import sarif.modelo.Zona;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas unitarias de la lectura de las respuestas de Copernicus para el NDVI de Sentinel-2 (PU-24).
 *
 * <p>Como leo el JSON sin bibliotecas, pruebo con respuestas armadas con la misma forma que devuelve la
 * API: el token del servidor de identidad y los bloques diarios de la API de estadísticas, con días sin
 * imagen (NaN), días nublados y días despejados. No necesita red ni credenciales.</p>
 */
class FuenteSentinelTest {

    /** Cuatro días: uno nublado (95 % descartado), uno sin imagen (NaN), uno despejado y uno nublado más reciente. */
    private static final String ESTADISTICAS = """
            {"data":[
             {"interval":{"from":"2026-01-08T00:00:00Z","to":"2026-01-09T00:00:00Z"},
              "outputs":{"ndvi":{"bands":{"B0":{"stats":{"min":-0.2,"max":0.9,"mean":0.61,"stDev":0.1,"sampleCount":40000,"noDataCount":38000}}}}}},
             {"interval":{"from":"2026-01-11T00:00:00Z","to":"2026-01-12T00:00:00Z"},
              "outputs":{"ndvi":{"bands":{"B0":{"stats":{"min":"NaN","max":"NaN","mean":"NaN","stDev":"NaN","sampleCount":40000,"noDataCount":40000}}}}}},
             {"interval":{"from":"2026-01-13T00:00:00Z","to":"2026-01-14T00:00:00Z"},
              "outputs":{"ndvi":{"bands":{"B0":{"stats":{"min":-0.1,"max":0.88,"mean":0.4172,"stDev":0.15,"sampleCount":40000,"noDataCount":8000}}}}}},
             {"interval":{"from":"2026-01-18T00:00:00Z","to":"2026-01-19T00:00:00Z"},
              "outputs":{"ndvi":{"bands":{"B0":{"stats":{"min":0.1,"max":0.7,"mean":0.35,"stDev":0.1,"sampleCount":40000,"noDataCount":34000}}}}}}
            ],"status":"OK"}
            """;

    @Test
    @DisplayName("PU-24 NDVI de Sentinel-2: imagen despejada más reciente, token y respuestas inválidas")
    void lecturaRespuestas() throws FuenteNoDisponibleException {
        // Me tengo que quedar con el 13/01: el 18 es más reciente pero solo tiene 15 % despejado (menos del
        // 20 % mínimo), el 11 no tiene imagen y el 8 está nublado.
        Optional<RegistroNdvi> r = FuenteSentinel.leerEstadisticas(ESTADISTICAS);
        assertTrue(r.isPresent());
        assertEquals(LocalDate.of(2026, 1, 13), r.get().fechaImagen());
        assertEquals(0.4172, r.get().ndviMedio(), 1e-9);
        assertEquals(32000, r.get().pixelesValidos());
        assertEquals(80.0, r.get().porcentajeValido(), 1e-9);

        // Si todo el período estuvo nublado no hay NDVI, pero no es un error de la fuente.
        String nublado = """
                {"data":[{"interval":{"from":"2026-01-11T00:00:00Z"},"outputs":{"ndvi":{"bands":{"B0":{"stats":
                {"mean":"NaN","sampleCount":100,"noDataCount":100}}}}}}],"status":"OK"}
                """;
        assertTrue(FuenteSentinel.leerEstadisticas(nublado).isEmpty());
        // Una respuesta que no es la de estadísticas sí es un error.
        assertThrows(FuenteNoDisponibleException.class, () -> FuenteSentinel.leerEstadisticas("{\"error\":\"invalid\"}"));

        // Token: lo saco del JSON del servidor de identidad; sin access_token, las credenciales están mal.
        assertEquals("eyJabc.def", FuenteSentinel.leerToken(
                "{\"access_token\":\"eyJabc.def\",\"expires_in\":600,\"token_type\":\"Bearer\"}"));
        assertThrows(FuenteNoDisponibleException.class, () -> FuenteSentinel.leerToken(
                "{\"error\":\"unauthorized_client\",\"error_description\":\"Invalid client secret\"}"));
    }

    @Test
    @DisplayName("PU-29 Pedido de la imagen NDVI: límites del rectángulo o del polígono y tamaño proporcional")
    void pedidoImagen() {
        // Rectángulo: solo va el bbox (longitud primero, como pide Sentinel Hub).
        Zona caviahue = new Zona(4, "Caviahue - Copahue", null, -37.95, -37.75, -71.20, -70.95, true);
        String limites = FuenteSentinel.limites(caviahue);
        assertTrue(limites.startsWith("{\"bbox\":[-71.20000,-37.95000,-70.95000,-37.75000]"));
        assertFalse(limites.contains("geometry"));

        // Polígono: además va el contorno en GeoJSON, cerrado repitiendo el primer vértice.
        Zona triangulo = new Zona();
        triangulo.setVertices(List.of(new Vertice(-37.95, -71.20), new Vertice(-37.95, -70.95), new Vertice(-37.75, -71.20)));
        assertTrue(FuenteSentinel.limites(triangulo).contains(
                "\"coordinates\":[[[-71.20000,-37.95000],[-70.95000,-37.95000],[-71.20000,-37.75000],[-71.20000,-37.95000]]]"));

        // Caviahue mide 0,25° de ancho por 0,20° de alto, pero a -37,85° un grado de longitud es un 79 % de uno
        // de latitud: en kilómetros es más alta que ancha, así que el alto lleva los 1024 píxeles.
        int[] tamano = FuenteSentinel.tamanoImagen(caviahue);
        assertEquals(1024, tamano[1]);
        assertEquals(1011, tamano[0], 2);
    }
}
