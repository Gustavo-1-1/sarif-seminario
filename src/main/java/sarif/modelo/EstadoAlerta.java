package sarif.modelo;

/**
 * Estados del ciclo de vida de una alerta (diagrama de estados, figura 8).
 * Puse la regla de transiciones acá, en el propio enum, para que cualquier servicio que cambie
 * el estado de una alerta tenga que pasar por la misma validación.
 */
public enum EstadoAlerta {
    PENDIENTE, NOTIFICADA, CERRADA, DESCARTADA;

    /**
     * Indica si se puede pasar de este estado al nuevo. Transiciones permitidas:
     * PENDIENTE -> NOTIFICADA -> CERRADA; PENDIENTE o NOTIFICADA -> DESCARTADA.
     * CERRADA y DESCARTADA son estados finales, por eso caen en el default y devuelven false.
     */
    public boolean puedePasarA(EstadoAlerta nuevo) {
        switch (this) {
            case PENDIENTE:
                return nuevo == NOTIFICADA || nuevo == DESCARTADA;
            case NOTIFICADA:
                return nuevo == CERRADA || nuevo == DESCARTADA;
            default:
                return false;
        }
    }
}
