package sarif.modelo;

/**
 * Asignación de un recurso de combate a una zona. Mientras el operador no la confirma es solo una
 * sugerencia (CU10); cuando la confirma la registro junto con el índice que la fundamentó (CU11),
 * así después se puede justificar por qué se mandó ese recurso ahí.
 * Guardo también el nombre de la zona, el valor del índice y el nivel para mostrarlos en la tabla de sugerencias
 * sin volver a consultar la base. idIndice e indice pueden ser null cuando la zona no tiene índice calculado
 * para la fecha (el operador igual puede mandarle un recurso a mano).
 */
public record Asignacion(Recurso recurso, int idZona, String nombreZona, Integer idIndice, Double indice,
                         String nivel, String motivo) {
}
