-- =====================================================================
-- SARIF - Script 02: datos de prueba (jornada del 20/01/2026).
-- Datos ficticios: la geografía de las zonas es real, pero los focos y
-- las variables meteorológicas se generaron con el formato de los servicios.
--   mysql -u root -p < sql/02_datos.sql
-- Este script carga el estado de la base el día anterior a la jornada de
-- prueba, es decir, lo que la guardia ya tendría registrado la noche del
-- 19/01/2026. Hay que correrlo justo después de 01_esquema.sql, sobre la
-- base recién creada, porque los INSERT usan ids fijos (1, 2, 3...) que
-- dependen de que los AUTO_INCREMENT arranquen de cero.
-- Elegí los datos pensando en el resto del trabajo: con estos valores,
-- más los archivos de la carpeta datos/, el índice de la jornada del
-- 20/01/2026 da exactamente los resultados que presento en el informe y
-- que verifican las pruebas de integración (PI-01 a PI-09).
-- =====================================================================

USE sarif;

-- Catálogos ------------------------------------------------------------

-- Los tres roles del sistema, en este orden para que tengan los ids 1, 2
-- y 3 que uso más abajo al cargar los usuarios.
INSERT INTO rol (nombre, descripcion) VALUES
    ('ADMINISTRADOR', 'Administra usuarios, parámetros y catálogos'),
    ('JEFE_GUARDIA',  'Decide cambios de nivel de alerta y asignaciones'),
    ('OPERADOR',      'Opera la guardia: sincroniza datos y consulta el tablero');

-- Clave de los tres usuarios: sarif2026. Hash = SHA-256(sal + clave) en hexadecimal.
-- Cargo un usuario por rol: admin (administrador), mruiz (Marcela Ruiz,
-- jefa de guardia, id 2) y pnahuel (Pedro Nahuel, operador, id 3). Cada uno
-- tiene su propia sal, por eso los hashes son distintos aunque la clave
-- sea la misma. Los hashes los calculé con el mismo método que usa
-- ServicioAutenticacion, así el ingreso de PI-01 funciona.
INSERT INTO usuario (nombre_usuario, nombre, apellido, clave_hash, sal, id_rol) VALUES
    ('admin',   'Administrador', 'SARIF',
     '879a7973580e0e9aea604765c7b2e60d6e4857f45f4207a9b35e89ae4ddfb091', 'a1f3c9e2b7d04f6a8c5e1b9d2f7a3c60', 1),
    ('mruiz',   'Marcela',       'Ruiz',
     '7ed6c936f52fe0eed3fac0b0170ea79fc238b385fc9ab84b541a13fa96b14a38', 'b2e4d0f3c8e15a7b9d6f2c0e3a8b4d71', 2),
    ('pnahuel', 'Pedro',         'Nahuel',
     '62e7cc6a0e67a3aa01424bcccd988e7ffc54792cdf110091f9d54e3e590c9469', 'c3f5e1a4d9f26b8c0e7a3d1f4b9c5e82', 3);

-- Parámetros del índice y de la sincronización. Los pesos de H, M y C
-- suman 1 (0,25 + 0,45 + 0,30); si no sumaran 1, CalculadorRiesgo los
-- rechaza (PU-06). Le doy más peso a la meteorología porque es lo que más
-- cambia de un día a otro. La clave de FIRMS queda vacía a propósito: no
-- la publico en el repositorio, y para la jornada de prueba uso la fuente
-- de archivo.
INSERT INTO parametro (clave, valor, descripcion) VALUES
    ('peso_historico',        '0.25', 'Peso de la componente histórica (H)'),
    ('peso_meteorologico',    '0.45', 'Peso de la componente meteorológica (M)'),
    ('peso_combustible',      '0.30', 'Peso de la componente de combustible (C)'),
    ('ventana_dias',          '15',   'Días antes y después de la fecha para la componente histórica'),
    ('temporadas_historicas', '5',    'Cantidad de temporadas anteriores consideradas'),
    ('saturacion_focos',      '30',   'Cantidad de focos que satura la componente histórica'),
    ('carga_referencia',      '30',   'Carga de combustible de referencia (t/ha)'),
    ('firms_clave',           '',     'Clave de acceso (MAP_KEY) del servicio NASA FIRMS'),
    ('firms_producto',        'VIIRS_SNPP_NRT', 'Producto satelital consultado'),
    ('sincronizacion_minutos','180',  'Frecuencia de la sincronización programada de focos'),
    ('dias_sincronizacion',   '2',    'Días consultados en cada sincronización de focos');

-- Niveles de riesgo: cuatro tramos iguales de 25 puntos. El mínimo está
-- incluido y el máximo excluido (salvo en EXTREMO, que incluye el 100), así
-- ningún valor queda sin nivel ni cae en dos; los bordes los pruebo en PU-05.
INSERT INTO nivel_riesgo (nombre, umbral_min, umbral_max, orden, color) VALUES
    ('BAJO',      0.00,  25.00, 1, '#2E7D32'),
    ('MODERADO', 25.00,  50.00, 2, '#F9A825'),
    ('ALTO',     50.00,  75.00, 3, '#EF6C00'),
    ('EXTREMO',  75.00, 100.00, 4, '#C62828');

-- Niveles de alerta operativa que puede decidir la guardia (CU12).
INSERT INTO nivel_alerta (nombre, orden, descripcion) VALUES
    ('NORMAL',     1, 'Vigilancia de rutina'),
    ('ATENCION',   2, 'Condiciones favorables al fuego: se refuerza la vigilancia'),
    ('ALERTA',     3, 'Recursos en apresto y comunicación a la población'),
    ('EMERGENCIA', 4, 'Incendio declarado o riesgo inminente');

-- Tipos de combustible típicos de la cordillera neuquina. Los factores
-- de inflamabilidad son estimaciones mías para el prototipo: el pastizal y
-- las plantaciones de coníferas arden con mucha más facilidad que el
-- bosque nativo húmedo.
INSERT INTO tipo_combustible (nombre, factor_inflamabilidad) VALUES
    ('Pastizal',                      0.90),
    ('Matorral',                      0.80),
    ('Plantación de coníferas',       0.95),
    ('Bosque nativo de lenga y ñire', 0.60),
    ('Bosque de coihue y ciprés',     0.55);

-- Combustibles que no son vegetación pero que en Neuquén conviven con ella:
-- los fósiles de la actividad petrolera y gasífera (pozos, piletas, ductos,
-- plantas y tanques de Vaca Muerta y la meseta), los rastrojos y basurales
-- de la zona productiva y periurbana, y el mallín como contraste (casi no
-- arde mientras tiene agua). A los fósiles les doy el factor máximo porque
-- un incendio que los alcanza escala enseguida. En estos tipos la carga en
-- t/ha se carga como equivalente estimado por el relevador.
INSERT INTO tipo_combustible (nombre, factor_inflamabilidad) VALUES
    ('Hidrocarburos líquidos (petróleo y derivados)', 1.00),
    ('Gas natural y GLP (instalaciones)',             1.00),
    ('Rastrojo y cultivos secos',                     0.85),
    ('Residuos y basurales',                          0.75),
    ('Mallín o humedal',                              0.20);

-- Zonas de vigilancia (norte de la cordillera neuquina) -----------------

-- Tomé seis zonas reales del norte de Neuquén, con rectángulos de
-- coordenadas aproximados a cada localidad y su entorno (lagos, parques
-- nacionales, plantaciones). Las cinco primeras tienen la vigilancia
-- activa; Junín de los Andes queda inactiva a propósito, para mostrar
-- que el sistema no calcula índice ni genera alertas en zonas inactivas
-- (por eso las pruebas hablan siempre de 5 zonas) y para poder activarla
-- después con U-2.
INSERT INTO zona_vigilancia (nombre, descripcion, latitud_min, latitud_max, longitud_min, longitud_max,
                             vigilancia_activa, fecha_alta) VALUES
    ('San Martín de los Andes - Lolog', 'Ejido urbano, lago Lolog y plantaciones de coníferas',
     -40.20000, -40.02000, -71.50000, -71.25000, TRUE,  '2025-10-01 09:00:00'),
    ('Aluminé - Lanín Norte', 'Valle del río Aluminé y sector norte del Parque Nacional Lanín',
     -39.40000, -39.10000, -71.40000, -70.85000, TRUE,  '2025-10-01 09:00:00'),
    ('Villa La Angostura', 'Ejido de Villa La Angostura y península de Quetrihué',
     -40.85000, -40.65000, -71.80000, -71.55000, TRUE,  '2025-10-01 09:00:00'),
    ('Caviahue - Copahue', 'Villa Caviahue, lago Caviahue y área termal de Copahue',
     -37.95000, -37.75000, -71.20000, -70.95000, TRUE,  '2025-10-01 09:00:00'),
    ('Villa Pehuenia - Moquehue', 'Lagos Aluminé y Moquehue, bosque de araucaria, lenga y ñire',
     -39.05000, -38.80000, -71.35000, -71.05000, TRUE,  '2025-10-01 09:00:00'),
    ('Junín de los Andes - Huechulafquen', 'Junín de los Andes y lagos Huechulafquen y Paimún',
     -40.00000, -39.70000, -71.50000, -71.00000, FALSE, '2025-10-01 09:00:00');

-- Relevamientos de combustible. Las seis primeras filas son el
-- relevamiento inicial de octubre de 2025; las dos últimas son
-- relevamientos de enero que reemplazan al inicial como vigentes (pero
-- sin borrarlo, así C-7 muestra la evolución). Los armé para el índice
-- de la jornada: el pastizal curado de Caviahue (15,5 t/ha, factor 0,90)
-- da C = 0,465, el valor que uso en PU-04 y PU-05, y el matorral seco de
-- Aluminé sube a 24 t/ha.
INSERT INTO relevamiento_combustible (id_zona, id_tipo_combustible, carga_t_ha, fecha_relevamiento, id_usuario, observaciones) VALUES
    (1, 3, 26.00, '2025-10-16', 2, 'Relevamiento inicial'),
    (2, 2, 18.50, '2025-10-15', 2, 'Relevamiento inicial'),
    (3, 5, 22.00, '2025-10-17', 2, 'Relevamiento inicial'),
    (4, 1, 12.00, '2025-10-18', 2, 'Relevamiento inicial'),
    (5, 4, 20.00, '2025-10-19', 2, 'Relevamiento inicial'),
    (6, 2, 16.00, '2025-10-20', 2, 'Relevamiento inicial'),
    (2, 2, 24.00, '2026-01-10', 2, 'Matorral seco tras tres semanas sin lluvias'),
    (4, 1, 15.50, '2026-01-12', 2, 'Pastizal curado');

-- Activos protegidos. Las ubicaciones son aproximadas a las reales. La
-- de Villa Caviahue la elegí para que el foco de alta confianza de la
-- jornada (-37,8455; -71,055) le quede a 2,72 km, y la de Barrio Lolog para
-- que el foco de Lolog le quede a 3,17 km: son las dos alertas de activo
-- en riesgo que verifico en PI-04. Las áreas protegidas tienen una
-- distancia de alerta menor (3 km) que las poblaciones (5 km).
INSERT INTO activo_protegido (id_zona, nombre, tipo, latitud, longitud, distancia_alerta_km) VALUES
    (1, 'Barrio Lolog',                  'POBLACION',       -40.10000, -71.33000, 5.00),
    (1, 'Cerro Chapelco',                'PRODUCTIVO',      -40.19000, -71.27000, 5.00),
    (2, 'Villa Aluminé',                 'POBLACION',       -39.23700, -70.91900, 5.00),
    (2, 'Seccional Rucachoroi',          'AREA_PROTEGIDA',  -39.23000, -71.17000, 3.00),
    (3, 'Villa La Angostura',            'POBLACION',       -40.76200, -71.64600, 5.00),
    (3, 'Parque Nacional Los Arrayanes', 'AREA_PROTEGIDA',  -40.80000, -71.65000, 3.00),
    (4, 'Villa Caviahue',                'POBLACION',       -37.87000, -71.05500, 5.00),
    (5, 'Villa Pehuenia',                'POBLACION',       -38.88000, -71.17000, 5.00);

-- Recursos de combate: una brigada con base en cada zona activa, más una
-- autobomba y dos medios aéreos sin base fija que la sugerencia reparte
-- según el índice. La Brigada Pehuenia está FUERA_DE_SERVICIO a propósito,
-- para comprobar que la sugerencia no la tiene en cuenta (PI-08). La zona
-- 6 (Junín) no tiene brigada: la agrego en I-1.
INSERT INTO recurso (denominacion, tipo, dotacion, estado, id_zona_base) VALUES
    ('Brigada Lolog',       'TERRESTRE', 8, 'DISPONIBLE',        1),
    ('Brigada Aluminé',     'TERRESTRE', 7, 'DISPONIBLE',        2),
    ('Brigada Angostura',   'TERRESTRE', 7, 'DISPONIBLE',        3),
    ('Brigada Caviahue',    'TERRESTRE', 7, 'DISPONIBLE',        4),
    ('Brigada Pehuenia',    'TERRESTRE', 6, 'FUERA_DE_SERVICIO', 5),
    ('Autobomba AB-1',      'TERRESTRE', 7, 'DISPONIBLE',        NULL),
    ('Helicóptero HB-1',    'AEREO',     3, 'DISPONIBLE',        NULL),
    ('Avión hidrante AH-1', 'AEREO',     1, 'DISPONIBLE',        NULL);

-- Historial de niveles de alerta: nivel inicial de cada zona -----------

-- Cada zona arranca en NORMAL el día del alta, así todas tienen un nivel
-- vigente desde el principio. La última fila sube Caviahue a ATENCION el
-- 12/01 con su fundamento: ese es el nivel con el que llega a la jornada,
-- por eso en PI-09 volver a pedir ATENCION se rechaza como nivel repetido,
-- y en C-5 se ve el historial NORMAL, ATENCION y ALERTA (esta última de I-5).
INSERT INTO cambio_nivel_alerta (id_zona, id_nivel_alerta, fecha_hora, fundamento, id_usuario) VALUES
    (1, 1, '2025-10-01 09:30:00', 'Alta de la zona', 1),
    (2, 1, '2025-10-01 09:30:00', 'Alta de la zona', 1),
    (3, 1, '2025-10-01 09:30:00', 'Alta de la zona', 1),
    (4, 1, '2025-10-01 09:30:00', 'Alta de la zona', 1),
    (5, 1, '2025-10-01 09:30:00', 'Alta de la zona', 1),
    (6, 1, '2025-10-01 09:30:00', 'Alta de la zona', 1),
    (4, 2, '2026-01-12 18:30:00', 'Pastizal curado según relevamiento del 12/01 y pronóstico sin lluvias', 2);

-- Focos cargados en forma manual por la guardia antes de la jornada -----

-- Todo foco tiene que pertenecer a una sincronización, así que primero
-- registro la carga manual que hizo el operador la noche del 19/01.
INSERT INTO sincronizacion (tipo, origen, fecha_hora_inicio, fecha_hora_fin, resultado,
                            registros_leidos, registros_nuevos, detalle, id_usuario) VALUES
    ('FOCOS', 'MANUAL', '2026-01-19 20:00:00', '2026-01-19 20:05:00', 'OK', 3, 3,
     'Carga manual de focos informados por la guardia', 3);

-- Los tres focos manuales. Varío zona, satélite y confianza (el de Villa
-- La Angostura tiene confianza BAJA) para que las consultas tengan
-- casos distintos que mostrar. El de Aluminé (19/01 a las 05:12 UTC,
-- satélite N) lo puse a propósito con los mismos datos que una fila del
-- archivo datos/focos_jornada_20260120.csv: cuando se sincronizan los
-- focos de la jornada, ese foco vuelve a llegar y la clave única
-- uq_foco_deteccion lo rechaza, por eso PI-04 informa 1 duplicado. Estos
-- tres focos son también los que cuenta la prueba de integración para
-- saber si la base está recién creada.
INSERT INTO foco_calor (id_zona, id_sincronizacion, latitud, longitud, fecha_hora_utc, satelite, instrumento,
                        potencia_frp_mw, confianza) VALUES
    (4, 1, -37.90100, -71.12000, '2026-01-10 18:05:00', 'N20', 'VIIRS', 9.80, 'NOMINAL'),
    (3, 1, -40.70500, -71.70000, '2026-01-15 17:40:00', 'N',   'VIIRS', 4.20, 'BAJA'),
    (2, 1, -39.30250, -71.10500, '2026-01-19 05:12:00', 'N',   'VIIRS', 6.40, 'NOMINAL');

-- Pronósticos del día anterior para la jornada de prueba ----------------

-- Pronósticos para el 20/01 de dos zonas, cargados la noche anterior.
-- Cuando se sincroniza la meteorología de la jornada llega el dato
-- observado de esas mismas zonas y fecha (la clave única lo permite
-- porque es_pronostico es distinto), y a partir de ahí estos pronósticos
-- quedan de más: son los que depura B-2 en 05_borrado.sql.
INSERT INTO registro_meteo (id_zona, fecha, temperatura_max_c, humedad_min_pct, viento_max_kmh, precipitacion_mm,
                            es_pronostico, origen, fecha_hora_carga) VALUES
    (1, '2026-01-20', 26.0, 20.0, 30.0, 0.0, TRUE, 'REMOTA', '2026-01-19 21:00:00'),
    (4, '2026-01-20', 30.0, 18.0, 40.0, 0.0, TRUE, 'REMOTA', '2026-01-19 21:00:00');
