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
--      Asigno el Helicóptero HB-1 a Caviahue tomando el índice del 20/01 como
--      fundamento; si el INSERT o el UPDATE fallan, no quiero que quede una
--      asignación con el recurso todavía DISPONIBLE (ni al revés).
START TRANSACTION;
INSERT INTO asignacion (id_recurso, id_zona, id_usuario, id_indice, fecha, observaciones)
SELECT r.id_recurso, i.id_zona, 2, i.id_indice, i.fecha, 'Refuerzo por índice EXTREMO'
FROM recurso r
JOIN indice_riesgo i ON i.id_zona = 4 AND i.fecha = '2026-01-20'
WHERE r.denominacion = 'Helicóptero HB-1';
UPDATE recurso SET estado = 'ASIGNADO' WHERE denominacion = 'Helicóptero HB-1';
COMMIT;

-- I-5. Cambio del nivel de alerta operativa con fundamento (CU12).
--      La jefa de guardia sube Caviahue de ATENCION a ALERTA. No toco la
--      zona: agrego una fila al historial, y el nivel vigente lo resuelve la
--      vista v_nivel_alerta_vigente.
INSERT INTO cambio_nivel_alerta (id_zona, id_nivel_alerta, fecha_hora, fundamento, id_usuario)
SELECT 4, id_nivel_alerta, '2026-01-20 19:10:00', 'Índice EXTREMO y foco a 2,72 km de Villa Caviahue', 2
FROM nivel_alerta WHERE nombre = 'ALERTA';

-- I-6. Ubicación en el mapa del recurso desplegado.
--      El Helicóptero HB-1 que se asignó en I-4 opera sobre el foco de
--      Caviahue. Uso INSERT ... SELECT con la condición estado = 'ASIGNADO'
--      (igual que el prototipo): si el recurso no estuviera desplegado, no se
--      inserta nada. La zona es la que contiene el punto.
INSERT INTO posicion_recurso (id_recurso, id_zona, latitud, longitud, fecha_hora, observaciones, id_usuario)
SELECT id_recurso, 4, -37.84200, -71.06000, '2026-01-20 19:20:00', 'Descargas sobre el flanco norte del foco', 2
FROM recurso WHERE denominacion = 'Helicóptero HB-1' AND estado = 'ASIGNADO';

-- I-7. Marcas operativas en el mapa: el puesto de comando en la villa y un
--      punto de carga de agua para el helicóptero en el lago Caviahue.
INSERT INTO marca_mapa (tipo, descripcion, latitud, longitud, id_zona, fecha_hora, id_usuario)
VALUES ('PUESTO_COMANDO', 'Puesto de comando en la delegación municipal', -37.87200, -71.05300, 4, '2026-01-20 19:15:00', 2),
       ('PUNTO_AGUA', 'Carga de agua del helicóptero (lago Caviahue)', -37.86000, -71.03500, 4, '2026-01-20 19:16:00', 2);

-- I-8. Meteorología de Caviahue con el vector del viento (CU08): el dato
--      observado del 20/01 y el pronóstico del 21/01, con los valores del
--      archivo de respaldo. Viento del oesnoroeste (292°): empuja el fuego
--      hacia el estesudeste, donde está la villa. La humedad del suelo queda
--      en NULL (sin dato), igual que en el archivo.
INSERT INTO registro_meteo (id_zona, fecha, temperatura_max_c, humedad_min_pct, viento_max_kmh, precipitacion_mm,
                            temperatura_min_c, rafaga_max_kmh, direccion_viento_grados, prob_precipitacion_pct,
                            evapotranspiracion_mm, es_pronostico, origen)
VALUES (4, '2026-01-20', 31.5, 15.0, 46.5, 0.0, 12.6, 73.1, 292, 1.0, 7.6, FALSE, 'ARCHIVO'),
       (4, '2026-01-21', 29.8, 19.0, 35.5, 0.0, 12.0, 57.0, 284, 4.0, 7.0, TRUE,  'ARCHIVO');

-- Verificación de la asignación registrada.
-- Con esta consulta compruebo que la transacción de I-4 dejó la asignación
-- y el recurso en estado ASIGNADO.
SELECT r.denominacion, r.estado, z.nombre AS zona_asignada, a.fecha, a.observaciones
FROM asignacion a
JOIN recurso r USING (id_recurso)
JOIN zona_vigilancia z ON z.id_zona = a.id_zona;

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
SET estado = 'NOTIFICADA', id_usuario_notifica = 2, fecha_hora_notificacion = '2026-01-20 19:05:00',
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
SET fecha_hora_baja = '2026-01-20 21:30:00', id_usuario_baja = 2
WHERE tipo = 'PUNTO_AGUA' AND fecha_hora_baja IS NULL;
