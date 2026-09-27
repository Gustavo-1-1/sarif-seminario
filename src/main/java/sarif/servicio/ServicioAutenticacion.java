package sarif.servicio;

import sarif.datos.ConexionBD;
import sarif.datos.UsuarioDAO;
import sarif.modelo.Usuario;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Implementa el RFS01 Iniciar sesión. En la base no guardo la clave en texto plano sino el hash
 * SHA-256 de (sal + clave): la sal es distinta para cada usuario, así dos usuarios con la misma clave
 * no tienen el mismo hash.
 */
public class ServicioAutenticacion {

    /** Uso el mismo mensaje para usuario inexistente y clave incorrecta, para no dar pistas sobre qué dato falló. */
    private static final String MENSAJE_ERROR = "Usuario o clave incorrectos.";

    // SecureRandom y no Random: la sal tiene que ser impredecible.
    private static final SecureRandom AZAR = new SecureRandom();

    /** Si hay conexión con la base. La pantalla de ingreso lo pregunta acá y no a ConexionBD, para no saltear la capa de servicio. */
    public boolean baseDisponible() {
        return ConexionBD.disponible();
    }

    /** Verifico las credenciales y, si son correctas, dejo al usuario guardado en la Sesion. */
    public Usuario ingresar(String nombreUsuario, String clave) throws SQLException, ValidacionException {
        if (nombreUsuario == null || nombreUsuario.isBlank() || clave == null || clave.isEmpty()) {
            throw new ValidacionException("Ingrese el usuario y la clave.");
        }
        try (Connection cn = ConexionBD.obtener()) {
            Optional<UsuarioDAO.Credencial> credencial = new UsuarioDAO(cn).buscarCredencial(nombreUsuario.trim());
            // Rechazo si el usuario no existe, está inactivo o el hash no coincide, siempre con el mismo mensaje.
            if (credencial.isEmpty() || !credencial.get().usuario().activo()
                    || !hash(credencial.get().sal(), clave).equals(credencial.get().claveHash())) {
                throw new ValidacionException(MENSAJE_ERROR);
            }
            Usuario usuario = credencial.get().usuario();
            Sesion.iniciar(usuario);
            return usuario;
        }
    }

    /** Calculo el SHA-256 de la sal seguida de la clave y lo devuelvo en hexadecimal (así lo guardo en la base). */
    public static String hash(String sal, String clave) {
        try {
            byte[] resumen = MessageDigest.getInstance("SHA-256").digest((sal + clave).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(resumen);
        } catch (NoSuchAlgorithmException e) {
            // Toda JVM está obligada a traer SHA-256, así que esto no debería pasar nunca.
            throw new IllegalStateException("SHA-256 no está disponible en esta JVM.", e);
        }
    }

    /** Sal nueva de 16 bytes al azar, en hexadecimal (32 caracteres, como la columna usuario.sal). */
    static String nuevaSal() {
        byte[] sal = new byte[16];
        AZAR.nextBytes(sal);
        return HexFormat.of().formatHex(sal);
    }
}
