package sarif.servicio;

import sarif.datos.ConexionBD;
import sarif.datos.RecursoDAO;
import sarif.modelo.EstadoRecurso;
import sarif.modelo.Recurso;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * CU03 Registrar recurso y el cambio de estado de los recursos de combate. Lo separé de ServicioDespliegue
 * porque el alta y el mantenimiento de los recursos no forman parte de la sugerencia: son datos que el
 * despliegue después usa.
 */
public class ServicioRecursos {

    /** CU03: doy de alta el recurso, siempre DISPONIBLE. */
    public void registrarRecurso(Recurso recurso) throws SQLException, ValidacionException {
        List<String> errores = validarRecurso(recurso);
        if (!errores.isEmpty()) {
            throw new ValidacionException(errores);
        }
        try (Connection cn = ConexionBD.obtener()) {
            RecursoDAO recursos = new RecursoDAO(cn);
            if (recursos.existeDenominacion(recurso.denominacion())) {
                throw new ValidacionException("Ya existe un recurso llamado " + recurso.denominacion().trim() + ".");
            }
            recursos.insertar(recurso);
        }
    }

    /**
     * Reglas del alta, estáticas y sin base para probarlas con JUnit. La dotación la limito a 500 personas
     * solo para frenar errores de tipeo.
     */
    public static List<String> validarRecurso(Recurso r) {
        List<String> errores = new ArrayList<>();
        if (r.denominacion() == null || r.denominacion().isBlank()) {
            errores.add("La denominación del recurso es obligatoria.");
        } else if (r.denominacion().trim().length() > 60) {
            errores.add("La denominación no puede superar los 60 caracteres.");
        }
        if (r.tipo() == null) {
            errores.add("Debe indicar si el recurso es terrestre o aéreo.");
        }
        if (r.dotacion() <= 0 || r.dotacion() > 500) {
            errores.add("La dotación debe ser de 1 a 500 personas.");
        }
        return errores;
    }

    public List<Recurso> listar() throws SQLException {
        try (Connection cn = ConexionBD.obtener()) {
            return new RecursoDAO(cn).listar();
        }
    }

    /**
     * Cambio el estado de un recurso: lo libero (DISPONIBLE) cuando vuelve de una asignación, o lo marco
     * FUERA_DE_SERVICIO. ASIGNADO no se elige a mano: lo pone la confirmación del despliegue (CU11).
     */
    public void cambiarEstado(Recurso recurso, EstadoRecurso nuevo) throws SQLException, ValidacionException {
        if (nuevo == EstadoRecurso.ASIGNADO) {
            throw new ValidacionException("Un recurso queda ASIGNADO solo al confirmar un despliegue.");
        }
        if (recurso.estado() == nuevo) {
            throw new ValidacionException("El recurso ya está " + nuevo.name().replace('_', ' ') + ".");
        }
        try (Connection cn = ConexionBD.obtener()) {
            new RecursoDAO(cn).cambiarEstado(recurso.id(), nuevo);
        }
    }
}
