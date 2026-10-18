-- =====================================================================
-- SARIF - Script 03: inserción y actualización de registros (sección 6).
-- Se ejecuta después de la jornada de prueba del prototipo (importación
-- histórica, sincronización de focos y de meteorología del 20/01/2026).
-- Acá muestro las operaciones de alta y modificación que hace la guardia
-- a mano o que el prototipo hace por detrás, escritas directamente en SQL.
-- Algunas sentencias dependen de datos que genera la jornada (por ejemplo,
-- el índice del 20/01 de Caviahue o las alertas de activos en riesgo), así
-- que si querés reproducir los resultados del informe, primero corré la
-- jornada desde la aplicación (o las pruebas de integración) y después
-- este script.
-- =====================================================================

USE sarif;

-- ---------------------------------------------------------------------
-- 6.1 Inserción
-- ---------------------------------------------------------------------

-- I-1. Alta de un recurso de combate (RFS04 / CU03).
--      Agrego una brigada con base en Junín de los Andes (zona 6), que en
--      los datos iniciales no tenía ninguna.
INSERT INTO recurso (denominacion, tipo, dotacion, estado, id_zona_base)
VALUES ('Brigada Junín', 'TERRESTRE', 7, 'DISPONIBLE', 6);

-- I-2. Alta de un activo protegido en una zona existente (RFS03 / CU02).
--      Una antena en Caviahue - Copahue (zona 4), con una distancia de
--      alerta de 3 km en lugar de los 5 km por defecto.
INSERT INTO activo_protegido (id_zona, nombre, tipo, latitud, longitud, distancia_alerta_km)
VALUES (4, 'Antena de comunicaciones Copahue', 'INFRAESTRUCTURA', -37.80500, -71.08000, 3.00);

-- I-3. Nuevo relevamiento de combustible: se agrega una fila y se conserva el historial (RFS21).
--      No actualizo el relevamiento anterior de Villa La Angostura: inserto
--      uno nuevo, que pasa a ser el vigente en la vista v_combustible_vigente.
--      Uso INSERT ... SELECT para buscar el tipo de combustible por su nombre
--      en lugar de escribir el id a mano.
INSERT INTO relevamiento_combustible (id_zona, id_tipo_combustible, carga_t_ha, fecha_relevamiento, id_usuario,
                                      observaciones)
SELECT 3, id_tipo_combustible, 25.50, '2026-01-18', 2, 'Relevamiento de mitad de temporada'
FROM tipo_combustible WHERE nombre = 'Bosque de coihue y ciprés';

-- I-4. Registro de la asignación decidida (CU11) y cambio de estado del recurso,
--      como una única transacción: o se registran ambas operaciones o ninguna.
--      Durante la jornada el prototipo ya asignó los siete recursos operativos
--      (entre ellos el Helicóptero HB-1). La Brigada Junín, dada de alta en I-1,
--      quedó disponible, así que la mando de refuerzo a Caviahue tomando el
--      índice del 20/01 como fundamento. Si el INSERT o el UPDATE fallan, no
--      quiero que quede una asignación con el recurso todavía DISPONIBLE (ni al revés).
START TRANSACTION;
INSERT INTO asignacion (id_recurso, id_zona, id_usuario, id_indice, fecha, observaciones)
SELECT r.id_recurso, i.id_zona, 2, i.id_indice, i.fecha, 'Refuerzo por índice EXTREMO'
FROM recurso r
JOIN indice_riesgo i ON i.id_zona = 4 AND i.fecha = '2026-01-20'
WHERE r.denominacion = 'Brigada Junín' AND r.estado = 'DISPONIBLE';
UPDATE recurso SET estado = 'ASIGNADO' WHERE denominacion = 'Brigada Junín';
COMMIT;

-- I-5. Cambio del nivel de alerta operativa con fundamento (CU12).
--      Caviahue ya pasó a ALERTA durante la jornada (Aplicar nivel sugerido).
--      Acá la jefa de guardia sube San Martín de los Andes - Lolog (zona 1) de
--      NORMAL a ATENCION. No toco la zona: agrego una fila al historial, y el
--      nivel vigente lo resuelve la vista v_nivel_alerta_vigente. La fecha y la
--      hora las pone el servidor, igual que cuando el cambio se hace desde la pantalla.
INSERT INTO cambio_nivel_alerta (id_zona, id_nivel_alerta, fundamento, id_usuario)
SELECT 1, id_nivel_alerta, 'Índice ALTO (73,78) y foco a 3,17 km de Barrio Lolog', 2
FROM nivel_alerta WHERE nombre = 'ATENCION';

-- I-6. Ubicación en el mapa del recurso desplegado.
--      El Helicóptero HB-1 que asignó el prototipo opera sobre el foco de
--      Caviahue. Uso INSERT ... SELECT con la condición estado = 'ASIGNADO'
--      (igual que el prototipo): si el recurso no estuviera desplegado, no se
--      inserta nada. La zona es la que contiene el punto.
INSERT INTO posicion_recurso (id_recurso, id_zona, latitud, longitud, observaciones, id_usuario)
SELECT id_recurso, 4, -37.84200, -71.06000, 'Descargas sobre el flanco norte del foco', 2
FROM recurso WHERE denominacion = 'Helicóptero HB-1' AND estado = 'ASIGNADO';

-- I-7. Marcas operativas en el mapa. El puesto de comando ya lo marcó la
--      guardia desde el mapa; acá agrego un punto de carga de agua para el
--      helicóptero en el lago Caviahue y el punto de reunión para evacuar la villa.
INSERT INTO marca_mapa (tipo, descripcion, latitud, longitud, id_zona, id_usuario)
VALUES ('PUNTO_AGUA', 'Carga de agua del helicóptero (lago Caviahue)', -37.86000, -71.03500, 4, 2),
       ('EVACUACION', 'Punto de reunión para evacuar la villa', -37.87500, -71.04800, 4, 2);

-- I-8. Carga manual de meteorología con el vector del viento (CU08, RFS12).
--      El observado del 20/01 y el pronóstico del 21/01 de Caviahue ya los
--      trajo la sincronización de la jornada (la clave única zona-fecha-tipo
--      no admite repetirlos). Agrego el pronóstico del 22/01 que la guardia
--      recibió por otro medio: viento del oeste (278°), que empuja el fuego
--      hacia el este. La humedad del suelo queda en NULL (sin dato).
INSERT INTO registro_meteo (id_zona, fecha, temperatura_max_c, humedad_min_pct, viento_max_kmh, precipitacion_mm,
                            temperatura_min_c, rafaga_max_kmh, direccion_viento_grados, prob_precipitacion_pct,
                            evapotranspiracion_mm, es_pronostico, origen)
VALUES (4, '2026-01-22', 30.4, 18.0, 38.0, 0.0, 11.8, 61.0, 278, 3.0, 7.3, TRUE, 'MANUAL');

-- Verificación de la asignación registrada.
-- Con esta consulta compruebo que la transacción de I-4 dejó la asignación
-- y el recurso en estado ASIGNADO.
SELECT r.denominacion, r.estado, z.nombre AS zona_asignada, a.fecha, a.observaciones
FROM asignacion a
JOIN recurso r USING (id_recurso)
JOIN zona_vigilancia z ON z.id_zona = a.id_zona
WHERE r.denominacion = 'Brigada Junín';

-- ---------------------------------------------------------------------
-- 6.2 Actualización
-- ---------------------------------------------------------------------

-- U-1. La guardia confirma y notifica las alertas de activos en riesgo.
--      Paso de PENDIENTE a NOTIFICADA solo las alertas de activos, que son
--      las que implican avisar a la población o a quien corresponda. Junto
--      con el estado registro quién avisó (la jefa de guardia), cuándo, a
--      quién y por qué medio: el CHECK ck_alerta_notificada no deja pasar
--      una alerta a NOTIFICADA sin esos datos.
UPDATE alerta
SET estado = 'NOTIFICADA', id_usuario_notifica = 2, fecha_hora_notificacion = NOW(),
    notificado_a = 'Defensa Civil de la localidad', medio_notificacion = 'TELEFONO'
WHERE tipo = 'ACTIVO_EN_RIESGO' AND estado = 'PENDIENTE';

-- U-2. Activación de la vigilancia de una zona (CU04).
--      Junín de los Andes estaba inactiva desde el alta; a partir de acá
--      entra en el cálculo del índice y en la generación de alertas.
UPDATE zona_vigilancia SET vigilancia_activa = TRUE WHERE nombre = 'Junín de los Andes - Huechulafquen';

-- U-3. Se quita del mapa el punto de agua de I-7 (el helicóptero cambió de
--      fuente). No borro la fila: registro quién y cuándo la quitó, así la
--      cronología conserva la marca. El CHECK ck_marca_baja exige los dos
--      datos juntos.
UPDATE marca_mapa
SET fecha_hora_baja = NOW(), id_usuario_baja = 2
WHERE tipo = 'PUNTO_AGUA' AND fecha_hora_baja IS NULL;
