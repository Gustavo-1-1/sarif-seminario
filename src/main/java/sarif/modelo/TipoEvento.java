package sarif.modelo;

/**
 * Tipos de hito de la cronología de la guardia (reporte del CU13). Cada uno sale de una tabla distinta:
 * las alertas (generación, notificación y cierre o descarte), el historial de niveles, las asignaciones y lo
 * que la guardia marcó en el mapa (ubicaciones de recursos y marcas operativas).
 */
public enum TipoEvento {
    ALERTA_GENERADA("Alerta generada"),
    ALERTA_NOTIFICADA("Alerta notificada"),
    ALERTA_CERRADA("Alerta cerrada"),
    ALERTA_DESCARTADA("Alerta descartada"),
    CAMBIO_NIVEL("Cambio de nivel de alerta"),
    ASIGNACION("Asignación de recurso"),
    RECURSO_UBICADO("Recurso ubicado en el mapa"),
    MARCA_AGREGADA("Marca agregada al mapa"),
    MARCA_QUITADA("Marca quitada del mapa");

    private final String texto;

    TipoEvento(String texto) {
        this.texto = texto;
    }

    public String texto() {
        return texto;
    }
}
