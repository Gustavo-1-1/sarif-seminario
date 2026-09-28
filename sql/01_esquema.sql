-- =====================================================================
-- SARIF - Script 01: creación de la base, tablas, índices, vistas y
-- usuario de aplicación. Ejecutar con un usuario administrador.
--   mysql -u root -p < sql/01_esquema.sql
-- En este script armo todo el esquema físico de SARIF a partir del modelo
-- de datos del informe. Lo escribí para que se pueda correr las veces que
-- haga falta: primero borro la base si existe y la vuelvo a crear desde
-- cero, así cada ejecución deja el mismo punto de partida. Tené en cuenta
-- que eso borra todos los datos cargados, así que si querés recrear la
-- base, corré este script con root (o con otro usuario administrador) y
-- después corré 02_datos.sql para tener los datos de la jornada de prueba.
-- Todas las tablas usan InnoDB porque es el motor de MySQL que respeta
-- las claves foráneas y las transacciones, y las dos cosas las necesito
-- (integridad referencial y la asignación de recursos de I-4).
-- =====================================================================

-- Borro y recreo la base. Uso utf8mb4 para guardar sin problemas tildes,
-- eñes y cualquier carácter que venga de los servicios externos, y la
-- intercalación utf8mb4_0900_ai_ci (la de MySQL 8) para que las
-- comparaciones de texto no distingan mayúsculas ni acentos.
DROP DATABASE IF EXISTS sarif;
CREATE DATABASE sarif
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;
USE sarif;

-- ---------------------------------------------------------------------
-- Catálogos y configuración
-- ---------------------------------------------------------------------

-- Roles del sistema (administrador, jefe de guardia y operador). Lo hago
-- tabla y no ENUM porque cada usuario apunta a un rol con clave foránea
-- y porque le quiero guardar una descripción. El nombre es UNIQUE porque
-- es la clave natural con la que la aplicación reconoce cada rol.
CREATE TABLE rol (
    id_rol      TINYINT UNSIGNED NOT NULL AUTO_INCREMENT,
    nombre      VARCHAR(30)      NOT NULL,
    descripcion VARCHAR(150)     NULL,
    CONSTRAINT pk_rol PRIMARY KEY (id_rol),
    CONSTRAINT uq_rol_nombre UNIQUE (nombre)
) ENGINE = InnoDB;

-- Usuarios que ingresan a SARIF. Nunca guardo la clave en texto plano:
-- guardo una sal por usuario y el hash SHA-256 de (sal + clave), así dos
-- usuarios con la misma clave tienen hashes distintos. El nombre de
-- usuario es UNIQUE porque es lo que se escribe en la pantalla de
-- ingreso. La FK al rol es ON DELETE RESTRICT para que no se pueda borrar
-- un rol que todavía tiene usuarios asignados; en lugar de borrar
-- usuarios los desactivo con la columna "activo", porque sus registros
-- quedan referenciados en relevamientos, asignaciones y cambios de nivel.
CREATE TABLE usuario (
    id_usuario     INT UNSIGNED     NOT NULL AUTO_INCREMENT,
    nombre_usuario VARCHAR(30)      NOT NULL,
    nombre         VARCHAR(60)      NOT NULL,
    apellido       VARCHAR(60)      NOT NULL,
    clave_hash     CHAR(64)         NOT NULL,   -- SHA-256 en hexadecimal de (sal + clave)
    sal            CHAR(32)         NOT NULL,   -- distinta para cada usuario (32 caracteres hexadecimales)
    id_rol         TINYINT UNSIGNED NOT NULL,
    activo         BOOLEAN          NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_usuario PRIMARY KEY (id_usuario),
    CONSTRAINT uq_usuario_nombre UNIQUE (nombre_usuario),
    CONSTRAINT fk_usuario_rol FOREIGN KEY (id_rol) REFERENCES rol (id_rol)
        ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE = InnoDB;

-- Parámetros configurables del sistema en formato clave-valor (pesos del
-- índice, ventana de días, clave de FIRMS, frecuencia de sincronización,
-- etc.). Los dejo en la base y no en el código para que el administrador
-- los pueda ajustar sin recompilar. La clave es UNIQUE porque la
-- aplicación busca cada parámetro por su nombre.
CREATE TABLE parametro (
    id_parametro SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
    clave        VARCHAR(50)       NOT NULL,
    valor        VARCHAR(100)      NOT NULL,
    descripcion  VARCHAR(200)      NULL,
    CONSTRAINT pk_parametro PRIMARY KEY (id_parametro),
    CONSTRAINT uq_parametro_clave UNIQUE (clave)
) ENGINE = InnoDB;

-- Niveles de riesgo en que clasifico el índice (BAJO, MODERADO, ALTO y
-- EXTREMO) con sus umbrales y el color que muestra el tablero. Lo hago
-- tabla de catálogo y no ENUM porque cada nivel tiene datos propios
-- (umbrales, orden y color) que se pueden ajustar sin tocar el esquema.
-- El nombre y el orden son UNIQUE para que no haya dos niveles iguales ni
-- dos niveles en la misma posición, y el CHECK asegura que los umbrales
-- estén dentro de la escala de 0 a 100 y que el mínimo sea menor que el
-- máximo.
CREATE TABLE nivel_riesgo (
    id_nivel_riesgo TINYINT UNSIGNED NOT NULL AUTO_INCREMENT,
    nombre          VARCHAR(20)      NOT NULL,
    umbral_min      DECIMAL(5,2)     NOT NULL,   -- incluido
    umbral_max      DECIMAL(5,2)     NOT NULL,   -- excluido, salvo el nivel superior
    orden           TINYINT UNSIGNED NOT NULL,
    color           CHAR(7)          NOT NULL,   -- color hexadecimal (#RRGGBB)
    CONSTRAINT pk_nivel_riesgo PRIMARY KEY (id_nivel_riesgo),
    CONSTRAINT uq_nivel_riesgo_nombre UNIQUE (nombre),
    CONSTRAINT uq_nivel_riesgo_orden UNIQUE (orden),
    CONSTRAINT ck_nivel_riesgo_umbral CHECK (umbral_min >= 0 AND umbral_max <= 100 AND umbral_min < umbral_max)
) ENGINE = InnoDB;

-- Niveles de alerta operativa que decide la guardia (NORMAL, ATENCION,
-- ALERTA y EMERGENCIA). No hay que confundirlos con el nivel de riesgo:
-- el riesgo lo calcula el sistema y la alerta la decide una persona con
-- un fundamento (CU12). Por la misma razón que el nivel de riesgo, lo
-- hago catálogo con nombre y orden únicos.
CREATE TABLE nivel_alerta (
    id_nivel_alerta TINYINT UNSIGNED NOT NULL AUTO_INCREMENT,
    nombre          VARCHAR(20)      NOT NULL,
    orden           TINYINT UNSIGNED NOT NULL,
    descripcion     VARCHAR(150)     NULL,
    CONSTRAINT pk_nivel_alerta PRIMARY KEY (id_nivel_alerta),
    CONSTRAINT uq_nivel_alerta_nombre UNIQUE (nombre),
    CONSTRAINT uq_nivel_alerta_orden UNIQUE (orden)
) ENGINE = InnoDB;

-- Tipos de combustible vegetal con su factor de inflamabilidad, que uso
-- en la componente de combustible (C) del índice. Pongo el CHECK entre 0
-- y 1 porque el factor multiplica a la carga relativa y no puede
-- amplificarla ni dar negativo.
CREATE TABLE tipo_combustible (
    id_tipo_combustible   TINYINT UNSIGNED NOT NULL AUTO_INCREMENT,
    nombre                VARCHAR(60)      NOT NULL,
    factor_inflamabilidad DECIMAL(3,2)     NOT NULL,
    CONSTRAINT pk_tipo_combustible PRIMARY KEY (id_tipo_combustible),
    CONSTRAINT uq_tipo_combustible_nombre UNIQUE (nombre),
    CONSTRAINT ck_tipo_combustible_factor CHECK (factor_inflamabilidad BETWEEN 0 AND 1)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------
-- Zonas y datos que dependen de ellas
-- ---------------------------------------------------------------------

-- Zonas de vigilancia, cada una definida como un rectángulo de latitud y
-- longitud. Guardo las coordenadas en DECIMAL(8,5) y no en FLOAT/DOUBLE
-- porque así se almacenan exactas: cinco decimales equivalen a
-- aproximadamente un metro, que es más precisión de la que dan los
-- satélites, y con un valor exacto la comparación de igualdad (por
-- ejemplo, para detectar focos duplicados) no falla por redondeo. Los
-- CHECK validan los rangos geográficos y que el mínimo sea menor que el
-- máximo. El nombre es UNIQUE porque es como la guardia identifica cada
-- zona. Las zonas se dan de alta inactivas por defecto: la vigilancia se
-- activa después, en forma explícita (CU04).
-- A propósito no guardo acá el combustible ni el nivel de alerta
-- "vigentes": esos datos tienen historial en sus propias tablas y los
-- obtengo con las vistas del final. Si los copiara en la zona tendría el
-- mismo dato en dos lugares y se podrían desincronizar.
CREATE TABLE zona_vigilancia (
    id_zona           INT UNSIGNED NOT NULL AUTO_INCREMENT,
    nombre            VARCHAR(80)  NOT NULL,
    descripcion       VARCHAR(250) NULL,
    latitud_min       DECIMAL(8,5) NOT NULL,
    latitud_max       DECIMAL(8,5) NOT NULL,
    longitud_min      DECIMAL(8,5) NOT NULL,
    longitud_max      DECIMAL(8,5) NOT NULL,
    vigilancia_activa BOOLEAN      NOT NULL DEFAULT FALSE,
    fecha_alta        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_zona_vigilancia PRIMARY KEY (id_zona),
    CONSTRAINT uq_zona_nombre UNIQUE (nombre),
    CONSTRAINT ck_zona_latitud CHECK (latitud_min BETWEEN -90 AND 90 AND latitud_max BETWEEN -90 AND 90 AND
                                      latitud_min < latitud_max),
    CONSTRAINT ck_zona_longitud CHECK (longitud_min BETWEEN -180 AND 180 AND longitud_max BETWEEN -180 AND 180 AND
                                       longitud_min < longitud_max)
) ENGINE = InnoDB;

-- Vértices de las zonas dibujadas como polígono en el mapa. Una zona sin
-- filas acá es un rectángulo (las cuatro columnas de arriba); una zona
-- con vértices es un polígono, y en ese caso las columnas latitud_min a
-- longitud_max guardan el rectángulo que lo encierra, que calculo en Java
-- a partir de los vértices. Lo mantengo porque FIRMS y Sentinel se
-- consultan por rectángulo y porque sirve de filtro rápido.
-- El orden es parte de la clave: los vértices se recorren en ese orden y
-- el último se une con el primero. La FK es ON DELETE CASCADE porque un
-- vértice no tiene sentido sin su zona. Los CHECK validan los rangos y
-- que el orden empiece en 1; que haya al menos 3 vértices y que los
-- bordes no se crucen lo controla Zona.validar(), porque un CHECK solo
-- puede mirar la fila que se inserta.
CREATE TABLE zona_vertice (
    id_zona  INT UNSIGNED      NOT NULL,
    orden    SMALLINT UNSIGNED NOT NULL,
    latitud  DECIMAL(8,5)      NOT NULL,
    longitud DECIMAL(8,5)      NOT NULL,
    CONSTRAINT pk_zona_vertice PRIMARY KEY (id_zona, orden),
    CONSTRAINT fk_vertice_zona FOREIGN KEY (id_zona) REFERENCES zona_vigilancia (id_zona)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT ck_vertice_orden CHECK (orden >= 1),
    CONSTRAINT ck_vertice_coordenadas CHECK (latitud BETWEEN -90 AND 90 AND longitud BETWEEN -180 AND 180)
) ENGINE = InnoDB;

-- Relevamientos de combustible de cada zona (tipo y carga en toneladas
-- por hectárea). Cada relevamiento nuevo es una fila nueva: no piso el
-- anterior, así queda el historial para la consulta C-7 (RFS21), y el
-- vigente lo saco con la vista v_combustible_vigente. La FK a la zona es
-- ON DELETE CASCADE porque un relevamiento no tiene sentido sin su zona;
-- en cambio, las FK al tipo de combustible y al usuario son RESTRICT para
-- no perder la referencia a un catálogo o a quien hizo el relevamiento.
CREATE TABLE relevamiento_combustible (
    id_relevamiento     INT UNSIGNED     NOT NULL AUTO_INCREMENT,
    id_zona             INT UNSIGNED     NOT NULL,
    id_tipo_combustible TINYINT UNSIGNED NOT NULL,
    carga_t_ha          DECIMAL(5,2)     NOT NULL,
    fecha_relevamiento  DATE             NOT NULL,
    id_usuario          INT UNSIGNED     NOT NULL,
    observaciones       VARCHAR(250)     NULL,
    CONSTRAINT pk_relevamiento PRIMARY KEY (id_relevamiento),
    CONSTRAINT ck_relevamiento_carga CHECK (carga_t_ha > 0),
    CONSTRAINT fk_relevamiento_zona FOREIGN KEY (id_zona) REFERENCES zona_vigilancia (id_zona)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_relevamiento_tipo FOREIGN KEY (id_tipo_combustible) REFERENCES tipo_combustible (id_tipo_combustible)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_relevamiento_usuario FOREIGN KEY (id_usuario) REFERENCES usuario (id_usuario)
        ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE = InnoDB;

-- Activos protegidos de cada zona (poblaciones, infraestructura, áreas
-- protegidas y zonas productivas) con su ubicación y la distancia a la
-- que un foco dispara la alerta de activo en riesgo. El tipo es ENUM y no
-- tabla porque es una lista corta y fija que no tiene datos propios.
-- El par (zona, nombre) es UNIQUE para no cargar dos veces el mismo
-- activo en una zona. La FK a la zona es ON DELETE RESTRICT: no quiero
-- que borrar una zona se lleve puestos sus activos (y las alertas que
-- los mencionan) sin que nadie se dé cuenta; eso lo verifico en B-4.
CREATE TABLE activo_protegido (
    id_activo           INT UNSIGNED NOT NULL AUTO_INCREMENT,
    id_zona             INT UNSIGNED NOT NULL,
    nombre              VARCHAR(80)  NOT NULL,
    tipo                ENUM('POBLACION','INFRAESTRUCTURA','AREA_PROTEGIDA','PRODUCTIVO') NOT NULL,
    latitud             DECIMAL(8,5) NOT NULL,
    longitud            DECIMAL(8,5) NOT NULL,
    distancia_alerta_km DECIMAL(5,2) NOT NULL DEFAULT 5.00,
    CONSTRAINT pk_activo_protegido PRIMARY KEY (id_activo),
    CONSTRAINT uq_activo_zona_nombre UNIQUE (id_zona, nombre),
    CONSTRAINT ck_activo_coordenadas CHECK (latitud BETWEEN -90 AND 90 AND longitud BETWEEN -180 AND 180),
    CONSTRAINT ck_activo_distancia CHECK (distancia_alerta_km > 0),
    CONSTRAINT fk_activo_zona FOREIGN KEY (id_zona) REFERENCES zona_vigilancia (id_zona)
        ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE = InnoDB;

-- Auditoría de cada sincronización con las fuentes externas (focos o
-- meteorología), tanto las exitosas como las fallidas: de dónde vinieron
-- los datos, cuándo, cuántos registros se leyeron y cuántos eran nuevos.
-- Uso ENUM para tipo, origen y resultado porque son valores fijos que
-- maneja el propio código. El usuario puede ser nulo porque las
-- sincronizaciones programadas no las dispara ninguna persona. La FK es
-- RESTRICT para no perder quién hizo cada sincronización.
CREATE TABLE sincronizacion (
    id_sincronizacion INT UNSIGNED NOT NULL AUTO_INCREMENT,
    tipo              ENUM('FOCOS','METEO','NDVI','FWI') NOT NULL,
    origen            ENUM('REMOTA','ARCHIVO','MANUAL') NOT NULL,
    fecha_hora_inicio DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fecha_hora_fin    DATETIME     NULL,
    resultado         ENUM('OK','FALLIDA') NOT NULL,
    registros_leidos  INT UNSIGNED NOT NULL DEFAULT 0,
    registros_nuevos  INT UNSIGNED NOT NULL DEFAULT 0,
    detalle           VARCHAR(250) NULL,
    id_usuario        INT UNSIGNED NULL,          -- nulo en las sincronizaciones programadas
    CONSTRAINT pk_sincronizacion PRIMARY KEY (id_sincronizacion),
    CONSTRAINT fk_sincronizacion_usuario FOREIGN KEY (id_usuario) REFERENCES usuario (id_usuario)
        ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE = InnoDB;

-- Focos de calor satelitales (NASA FIRMS) que caen dentro de alguna zona.
-- Uso BIGINT para el id porque es la tabla que más crece. Cada foco
-- recuerda en qué sincronización entró, para poder auditarlo. La clave
-- natural de una detección es (latitud, longitud, fecha y hora, satélite)
-- y la hago UNIQUE: así, si el mismo foco llega dos veces (por ejemplo,
-- porque las consultas a FIRMS se superponen en días, o porque la guardia
-- ya lo había cargado a mano), la base lo rechaza y la sincronización lo
-- cuenta como duplicado en lugar de guardarlo dos veces. Las dos FK son
-- RESTRICT porque los focos son la evidencia de las alertas y del
-- histórico: no se tienen que perder por borrar una zona o una
-- sincronización.
CREATE TABLE foco_calor (
    id_foco           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    id_zona           INT UNSIGNED    NOT NULL,
    id_sincronizacion INT UNSIGNED    NOT NULL,
    latitud           DECIMAL(8,5)    NOT NULL,
    longitud          DECIMAL(8,5)    NOT NULL,
    fecha_hora_utc    DATETIME        NOT NULL,
    satelite          VARCHAR(10)     NOT NULL,
    instrumento       ENUM('MODIS','VIIRS') NOT NULL,
    potencia_frp_mw   DECIMAL(8,2)    NULL,
    confianza         ENUM('BAJA','NOMINAL','ALTA') NOT NULL,
    CONSTRAINT pk_foco_calor PRIMARY KEY (id_foco),
    CONSTRAINT uq_foco_deteccion UNIQUE (latitud, longitud, fecha_hora_utc, satelite),
    CONSTRAINT fk_foco_zona FOREIGN KEY (id_zona) REFERENCES zona_vigilancia (id_zona)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_foco_sincronizacion FOREIGN KEY (id_sincronizacion) REFERENCES sincronizacion (id_sincronizacion)
        ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE = InnoDB;

-- Índice para la búsqueda más frecuente sobre los focos: los de una zona
-- en un rango de fechas (componente histórica del índice, C-3 y C-6).
CREATE INDEX ix_foco_zona_fecha ON foco_calor (id_zona, fecha_hora_utc);

-- Datos meteorológicos diarios por zona (Open-Meteo): temperatura
-- máxima, humedad mínima, viento máximo y precipitación, que son las
-- variables de la componente meteorológica (M). En la misma tabla guardo
-- el dato observado y el pronóstico, distinguidos por es_pronostico; por
-- eso la clave única es (zona, fecha, es_pronostico): puede haber un
-- pronóstico y un observado del mismo día, pero no dos de cada uno. Los
-- pronósticos que ya tienen su observado los depuro en B-2. La FK es
-- ON DELETE CASCADE porque estos datos no sirven sin su zona.
-- La humedad del suelo (0 a 10 cm, en m³/m³, RC06) es opcional: la trae
-- Open-Meteo, pero los archivos viejos o la carga manual pueden no tenerla.
-- Las columnas siguientes también son opcionales, por el mismo motivo, y no
-- entran en el índice: completan el vector del viento (ráfaga máxima y
-- dirección dominante, en grados desde donde sopla: 0 = norte, 90 = este)
-- y el pronóstico extendido (temperatura mínima, probabilidad de lluvia y
-- evapotranspiración de referencia FAO, que mide cuánta agua pierden el
-- suelo y la vegetación). Las guardo para analizar hacia dónde puede
-- avanzar un incendio y dónde se dan condiciones para focos nuevos.
-- Cuando la fecha de trabajo es la de hoy, la sincronización trae 16 días
-- de pronóstico: cada día futuro queda con es_pronostico = TRUE y se
-- reemplaza con el pronóstico más nuevo en cada sincronización.
CREATE TABLE registro_meteo (
    id_registro       INT UNSIGNED NOT NULL AUTO_INCREMENT,
    id_zona           INT UNSIGNED NOT NULL,
    fecha             DATE         NOT NULL,
    temperatura_max_c DECIMAL(4,1) NOT NULL,
    humedad_min_pct   DECIMAL(4,1) NOT NULL,
    viento_max_kmh    DECIMAL(5,1) NOT NULL,
    precipitacion_mm  DECIMAL(5,1) NOT NULL DEFAULT 0,
    humedad_suelo_m3m3 DECIMAL(4,3) NULL,
    temperatura_min_c DECIMAL(4,1) NULL,
    rafaga_max_kmh    DECIMAL(5,1) NULL,
    direccion_viento_grados SMALLINT UNSIGNED NULL,
    prob_precipitacion_pct  DECIMAL(4,1) NULL,
    evapotranspiracion_mm   DECIMAL(4,1) NULL,
    es_pronostico     BOOLEAN      NOT NULL,
    origen            ENUM('REMOTA','ARCHIVO','MANUAL') NOT NULL,
    fecha_hora_carga  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_registro_meteo PRIMARY KEY (id_registro),
    CONSTRAINT uq_meteo_zona_fecha_tipo UNIQUE (id_zona, fecha, es_pronostico),
    CONSTRAINT ck_meteo_humedad CHECK (humedad_min_pct BETWEEN 0 AND 100),
    CONSTRAINT ck_meteo_viento CHECK (viento_max_kmh >= 0),
    CONSTRAINT ck_meteo_precipitacion CHECK (precipitacion_mm >= 0),
    CONSTRAINT ck_meteo_suelo CHECK (humedad_suelo_m3m3 BETWEEN 0 AND 1),
    CONSTRAINT ck_meteo_rafaga CHECK (rafaga_max_kmh >= 0),
    CONSTRAINT ck_meteo_direccion CHECK (direccion_viento_grados BETWEEN 0 AND 360),
    CONSTRAINT ck_meteo_prob_lluvia CHECK (prob_precipitacion_pct BETWEEN 0 AND 100),
    CONSTRAINT ck_meteo_evapotranspiracion CHECK (evapotranspiracion_mm >= 0),
    CONSTRAINT fk_meteo_zona FOREIGN KEY (id_zona) REFERENCES zona_vigilancia (id_zona)
        ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE = InnoDB;

-- NDVI de cada zona obtenido de Sentinel-2 (Copernicus Data Space, RC05).
-- Es un dato informativo del estado de la vegetación: no entra en el índice.
-- Guardo la fecha de la imagen usada (la más reciente con suficientes
-- píxeles despejados) y cuántos píxeles válidos tuvo, porque con nubes el
-- promedio puede salir de muy pocos píxeles. La clave única evita guardar
-- dos veces la misma imagen de la misma zona.
CREATE TABLE registro_ndvi (
    id_ndvi           INT UNSIGNED NOT NULL AUTO_INCREMENT,
    id_zona           INT UNSIGNED NOT NULL,
    fecha_imagen      DATE         NOT NULL,
    ndvi_medio        DECIMAL(4,3) NOT NULL,
    pixeles_validos   INT UNSIGNED NOT NULL,
    porcentaje_valido DECIMAL(5,1) NOT NULL,
    fecha_hora_carga  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_registro_ndvi PRIMARY KEY (id_ndvi),
    CONSTRAINT uq_ndvi_zona_fecha UNIQUE (id_zona, fecha_imagen),
    CONSTRAINT ck_ndvi_rango CHECK (ndvi_medio BETWEEN -1 AND 1),
    CONSTRAINT ck_ndvi_porcentaje CHECK (porcentaje_valido BETWEEN 0 AND 100),
    CONSTRAINT fk_ndvi_zona FOREIGN KEY (id_zona) REFERENCES zona_vigilancia (id_zona)
        ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE = InnoDB;

-- Índice FWI de peligro meteorológico de incendios que SARIF trae ya calculado del sistema global de
-- información de incendios de Copernicus (GWIS), del modelo que indica la columna modelo. Como el NDVI,
-- es un dato informativo: no entra en el índice propio del sistema. Guardo un valor por zona y día, y la
-- clave única permite reemplazarlo cuando el servicio corrige el pronóstico de un día futuro. La FK es
-- ON DELETE CASCADE porque el dato no sirve sin su zona. El CHECK acota el índice a la escala publicada
-- (el FWI no tiene tope teórico, pero por encima de 150 sería un valor imposible o un error de lectura).
CREATE TABLE registro_fwi (
    id_fwi           INT UNSIGNED NOT NULL AUTO_INCREMENT,
    id_zona          INT UNSIGNED NOT NULL,
    fecha            DATE         NOT NULL,
    valor            DECIMAL(6,2) NOT NULL,
    modelo           VARCHAR(20)  NOT NULL,
    fecha_hora_carga DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_registro_fwi PRIMARY KEY (id_fwi),
    CONSTRAINT uq_fwi_zona_fecha UNIQUE (id_zona, fecha),
    CONSTRAINT ck_fwi_valor CHECK (valor BETWEEN 0 AND 150),
    CONSTRAINT fk_fwi_zona FOREIGN KEY (id_zona) REFERENCES zona_vigilancia (id_zona)
        ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE = InnoDB;

-- Índice de riesgo calculado para cada zona y fecha. Además del valor
-- final (0 a 100) guardo las tres componentes H, M y C (0 a 1) para poder
-- mostrar cómo se llegó al resultado (CU09, C-2). La clave única
-- (zona, fecha) garantiza un solo índice por zona y día: si se recalcula,
-- el valor se reemplaza en lugar de duplicarse (lo verifico en PI-07).
-- Guardo el nivel de riesgo que correspondía al momento del cálculo, con
-- FK RESTRICT hacia el catálogo; la FK a la zona es CASCADE porque el
-- índice no tiene sentido sin la zona.
CREATE TABLE indice_riesgo (
    id_indice          INT UNSIGNED     NOT NULL AUTO_INCREMENT,
    id_zona            INT UNSIGNED     NOT NULL,
    fecha              DATE             NOT NULL,
    comp_historica     DECIMAL(4,3)     NOT NULL,
    comp_meteorologica DECIMAL(4,3)     NOT NULL,
    comp_combustible   DECIMAL(4,3)     NOT NULL,
    valor_final        DECIMAL(5,2)     NOT NULL,
    id_nivel_riesgo    TINYINT UNSIGNED NOT NULL,
    fecha_hora_calculo DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_indice_riesgo PRIMARY KEY (id_indice),
    CONSTRAINT uq_indice_zona_fecha UNIQUE (id_zona, fecha),
    CONSTRAINT ck_indice_componentes CHECK (comp_historica BETWEEN 0 AND 1 AND comp_meteorologica BETWEEN 0 AND 1 AND
                                            comp_combustible BETWEEN 0 AND 1),
    CONSTRAINT ck_indice_valor CHECK (valor_final BETWEEN 0 AND 100),
    CONSTRAINT fk_indice_zona FOREIGN KEY (id_zona) REFERENCES zona_vigilancia (id_zona)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_indice_nivel FOREIGN KEY (id_nivel_riesgo) REFERENCES nivel_riesgo (id_nivel_riesgo)
        ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE = InnoDB;

-- Alertas que genera el sistema solo, sin que nadie las cargue. Hay cuatro
-- tipos:
--   FOCO_EN_ZONA          un foco cayó en una zona vigilada (CU14);
--   ACTIVO_EN_RIESGO      además quedó cerca de un activo protegido (CU15);
--   RIESGO_ELEVADO        el índice de la zona subió a ALTO o EXTREMO;
--   FOCO_CON_RIESGO_ALTO  hay focos confiables en una zona que ya tiene
--                         índice ALTO o EXTREMO (cruza las dos fuentes).
-- Las dos primeras salen del foco y las dos últimas del índice del día
-- (id_indice). Las de riesgo guardan además el nivel de alerta que el
-- sistema le sugiere a la guardia (id_nivel_alerta_sugerido); el cambio de
-- nivel lo sigue decidiendo una persona con fundamento (CU12).
-- El CHECK ck_alerta_activo asegura que solo la alerta de activo lleve
-- activo y distancia. La coherencia entre el tipo y id_foco/id_indice la
-- controla el código, porque MySQL no permite CHECK sobre columnas con FK
-- en CASCADE. La clave única (foco, tipo, activo) evita repetir la alerta
-- de un foco que se procesa de nuevo; la de las alertas de riesgo la
-- controlo al generarlas (una por índice, tipo y nivel sugerido).
-- El estado sigue el ciclo PENDIENTE, NOTIFICADA, CERRADA o DESCARTADA
-- (U-1 y B-1). Las FK al foco y al índice son CASCADE porque la alerta
-- depende de ellos; la FK al activo es RESTRICT para que no se pueda borrar
-- un activo que tiene alertas registradas.
-- Al notificarla guardo quién avisó, cuándo, a quién y por qué medio: SARIF
-- no manda mensajes, el aviso lo da una persona y acá queda la constancia.
-- ck_alerta_notificacion exige que esos cuatro datos estén todos o ninguno,
-- y ck_alerta_notificada que una alerta PENDIENTE no los tenga y una
-- NOTIFICADA o CERRADA sí (a CERRADA solo se llega desde NOTIFICADA). Una
-- DESCARTADA puede tenerlos o no, según se haya descartado antes o después
-- de avisar. Al cerrarla o descartarla guardo también quién y cuándo
-- (ck_alerta_resolucion: esos dos datos solo en CERRADA o DESCARTADA), así
-- el reporte de la guardia arma la cronología completa de cada alerta.
-- Las FK a usuario son RESTRICT también en el UPDATE porque MySQL no
-- permite CHECK sobre una columna con FK en CASCADE.
-- La alerta NIVEL_ELEVADO la genera el sistema cuando una persona sube a
-- mano el nivel de alerta de una zona (CU12): el cambio queda en el
-- historial y la alerta le pide a la guardia registrar a quién avisó. Sale
-- del cambio (id_cambio); la FK se agrega después de crear
-- cambio_nivel_alerta, más abajo, porque esa tabla todavía no existe acá.
CREATE TABLE alerta (
    id_alerta                INT UNSIGNED     NOT NULL AUTO_INCREMENT,
    id_foco                  BIGINT UNSIGNED  NULL,
    id_activo                INT UNSIGNED     NULL,
    id_indice                INT UNSIGNED     NULL,
    id_cambio                INT UNSIGNED     NULL,
    tipo                     ENUM('FOCO_EN_ZONA','ACTIVO_EN_RIESGO','RIESGO_ELEVADO','FOCO_CON_RIESGO_ALTO',
                                  'NIVEL_ELEVADO') NOT NULL,
    fecha_hora               DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    distancia_km             DECIMAL(6,2)     NULL,
    descripcion              VARCHAR(250)     NOT NULL,
    estado                   ENUM('PENDIENTE','NOTIFICADA','CERRADA','DESCARTADA') NOT NULL DEFAULT 'PENDIENTE',
    id_nivel_alerta_sugerido TINYINT UNSIGNED NULL,
    id_usuario_notifica      INT UNSIGNED     NULL,
    fecha_hora_notificacion  DATETIME         NULL,
    notificado_a             VARCHAR(120)     NULL,
    medio_notificacion       ENUM('RADIO','TELEFONO','MENSAJE','PRESENCIAL','SISTEMA') NULL,
    id_usuario_resolucion    INT UNSIGNED     NULL,
    fecha_hora_resolucion    DATETIME         NULL,
    CONSTRAINT pk_alerta PRIMARY KEY (id_alerta),
    CONSTRAINT uq_alerta_foco_activo_tipo UNIQUE (id_foco, tipo, id_activo),
    CONSTRAINT ck_alerta_activo CHECK ((tipo = 'ACTIVO_EN_RIESGO' AND id_activo IS NOT NULL AND distancia_km IS NOT NULL)
                                       OR (tipo <> 'ACTIVO_EN_RIESGO' AND id_activo IS NULL)),
    CONSTRAINT ck_alerta_notificacion CHECK ((id_usuario_notifica IS NULL AND fecha_hora_notificacion IS NULL
                                              AND notificado_a IS NULL AND medio_notificacion IS NULL)
                                             OR (id_usuario_notifica IS NOT NULL AND fecha_hora_notificacion IS NOT NULL
                                              AND CHAR_LENGTH(TRIM(notificado_a)) > 0 AND medio_notificacion IS NOT NULL)),
    CONSTRAINT ck_alerta_notificada CHECK ((estado = 'PENDIENTE' AND id_usuario_notifica IS NULL)
                                           OR (estado IN ('NOTIFICADA','CERRADA') AND id_usuario_notifica IS NOT NULL)
                                           OR estado = 'DESCARTADA'),
    CONSTRAINT ck_alerta_resolucion CHECK ((estado IN ('PENDIENTE','NOTIFICADA')
                                            AND id_usuario_resolucion IS NULL AND fecha_hora_resolucion IS NULL)
                                           OR (estado IN ('CERRADA','DESCARTADA')
                                            AND id_usuario_resolucion IS NOT NULL AND fecha_hora_resolucion IS NOT NULL)),
    CONSTRAINT fk_alerta_foco FOREIGN KEY (id_foco) REFERENCES foco_calor (id_foco)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_alerta_activo FOREIGN KEY (id_activo) REFERENCES activo_protegido (id_activo)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_alerta_indice FOREIGN KEY (id_indice) REFERENCES indice_riesgo (id_indice)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_alerta_nivel_sugerido FOREIGN KEY (id_nivel_alerta_sugerido) REFERENCES nivel_alerta (id_nivel_alerta)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_alerta_usuario_notifica FOREIGN KEY (id_usuario_notifica) REFERENCES usuario (id_usuario)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_alerta_usuario_resolucion FOREIGN KEY (id_usuario_resolucion) REFERENCES usuario (id_usuario)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------
-- Recursos y decisiones de la guardia
-- ---------------------------------------------------------------------

-- Recursos de combate (brigadas, autobombas y medios aéreos) con su
-- dotación y su estado. Tipo y estado son ENUM porque son pocos valores
-- fijos que usa la lógica de la sugerencia de despliegue (por ejemplo,
-- los aéreos van a las zonas EXTREMO y los FUERA_DE_SERVICIO no se
-- sugieren). La denominación es UNIQUE porque es como se nombra cada
-- recurso en la guardia. La zona base es opcional y la FK es
-- ON DELETE SET NULL: si se borra la zona, el recurso sigue existiendo,
-- simplemente se queda sin base operativa.
CREATE TABLE recurso (
    id_recurso   INT UNSIGNED      NOT NULL AUTO_INCREMENT,
    denominacion VARCHAR(60)       NOT NULL,
    tipo         ENUM('TERRESTRE','AEREO') NOT NULL,
    dotacion     SMALLINT UNSIGNED NOT NULL,
    estado       ENUM('DISPONIBLE','ASIGNADO','FUERA_DE_SERVICIO') NOT NULL DEFAULT 'DISPONIBLE',
    id_zona_base INT UNSIGNED      NULL,         -- zona de base operativa; nulo si no tiene
    CONSTRAINT pk_recurso PRIMARY KEY (id_recurso),
    CONSTRAINT uq_recurso_denominacion UNIQUE (denominacion),
    CONSTRAINT ck_recurso_dotacion CHECK (dotacion > 0),
    CONSTRAINT fk_recurso_zona_base FOREIGN KEY (id_zona_base) REFERENCES zona_vigilancia (id_zona)
        ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE = InnoDB;

-- Asignaciones de recursos a zonas que confirma el jefe de guardia (CU11).
-- La clave única (recurso, fecha) impide asignar el mismo recurso a dos
-- zonas el mismo día. Guardo el índice que fundamentó la decisión, pero
-- como referencia opcional con ON DELETE SET NULL: si ese índice se borra,
-- la decisión tomada se conserva igual. El recurso y el usuario son
-- RESTRICT para no perder quién decidió qué; la zona es CASCADE porque
-- la asignación no tiene sentido sin la zona.
CREATE TABLE asignacion (
    id_asignacion       INT UNSIGNED NOT NULL AUTO_INCREMENT,
    id_recurso          INT UNSIGNED NOT NULL,
    id_zona             INT UNSIGNED NOT NULL,
    id_usuario          INT UNSIGNED NOT NULL,
    id_indice           INT UNSIGNED NULL,       -- índice que fundamentó la decisión
    fecha               DATE         NOT NULL,
    fecha_hora_registro DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    observaciones       VARCHAR(250) NULL,
    CONSTRAINT pk_asignacion PRIMARY KEY (id_asignacion),
    CONSTRAINT uq_asignacion_recurso_fecha UNIQUE (id_recurso, fecha),
    CONSTRAINT fk_asignacion_recurso FOREIGN KEY (id_recurso) REFERENCES recurso (id_recurso)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_asignacion_zona FOREIGN KEY (id_zona) REFERENCES zona_vigilancia (id_zona)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_asignacion_usuario FOREIGN KEY (id_usuario) REFERENCES usuario (id_usuario)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_asignacion_indice FOREIGN KEY (id_indice) REFERENCES indice_riesgo (id_indice)
        ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE = InnoDB;

-- Historial de cambios del nivel de alerta de cada zona (CU12, RFS17).
-- Cada cambio es una fila con el nivel nuevo, cuándo, quién lo decidió y
-- el fundamento, que es obligatorio: el CHECK rechaza un fundamento vacío
-- o con solo espacios. No guardo el nivel anterior en una columna porque
-- sería un dato redundante (es el nivel de la fila previa de la misma
-- zona) y podría quedar inconsistente; cuando lo necesito lo derivo con
-- la función de ventana LAG, como en la consulta C-5. El nivel vigente
-- sale de la vista v_nivel_alerta_vigente. La FK a la zona es CASCADE
-- (el historial no sirve sin la zona) y las FK al nivel y al usuario son
-- RESTRICT.
CREATE TABLE cambio_nivel_alerta (
    id_cambio       INT UNSIGNED     NOT NULL AUTO_INCREMENT,
    id_zona         INT UNSIGNED     NOT NULL,
    id_nivel_alerta TINYINT UNSIGNED NOT NULL,
    fecha_hora      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fundamento      VARCHAR(250)     NOT NULL,
    id_usuario      INT UNSIGNED     NOT NULL,
    CONSTRAINT pk_cambio_nivel_alerta PRIMARY KEY (id_cambio),
    CONSTRAINT ck_cambio_fundamento CHECK (CHAR_LENGTH(TRIM(fundamento)) > 0),
    CONSTRAINT fk_cambio_zona FOREIGN KEY (id_zona) REFERENCES zona_vigilancia (id_zona)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_cambio_nivel FOREIGN KEY (id_nivel_alerta) REFERENCES nivel_alerta (id_nivel_alerta)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_cambio_usuario FOREIGN KEY (id_usuario) REFERENCES usuario (id_usuario)
        ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE = InnoDB;

-- Índice para encontrar rápido el último cambio de cada zona (vista del
-- nivel vigente) y recorrer su historial en orden.
CREATE INDEX ix_cambio_zona_fecha ON cambio_nivel_alerta (id_zona, fecha_hora);

-- La alerta de nivel elevado depende del cambio que la originó (CASCADE,
-- como las FK al foco y al índice).
ALTER TABLE alerta ADD CONSTRAINT fk_alerta_cambio FOREIGN KEY (id_cambio)
    REFERENCES cambio_nivel_alerta (id_cambio) ON UPDATE CASCADE ON DELETE CASCADE;

-- Ubicaciones de los recursos desplegados que la guardia marca en el mapa.
-- Cada vez que se mueve un recurso agrego una fila (no piso la anterior),
-- así queda el recorrido con quién lo informó y cuándo; en el mapa se ve
-- la última ubicación de cada recurso ASIGNADO. La zona es la que contiene
-- el punto y puede faltar (un recurso en camino, fuera de las zonas); si
-- se borra la zona, la ubicación queda sin zona (SET NULL). El recurso y
-- el usuario son RESTRICT, como en asignacion, para no perder el registro.
CREATE TABLE posicion_recurso (
    id_posicion   INT UNSIGNED  NOT NULL AUTO_INCREMENT,
    id_recurso    INT UNSIGNED  NOT NULL,
    id_zona       INT UNSIGNED  NULL,
    latitud       DECIMAL(8,5)  NOT NULL,
    longitud      DECIMAL(8,5)  NOT NULL,
    fecha_hora    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    observaciones VARCHAR(120)  NULL,
    id_usuario    INT UNSIGNED  NOT NULL,
    CONSTRAINT pk_posicion_recurso PRIMARY KEY (id_posicion),
    CONSTRAINT ck_posicion_coordenadas CHECK (latitud BETWEEN -90 AND 90 AND longitud BETWEEN -180 AND 180),
    CONSTRAINT fk_posicion_recurso FOREIGN KEY (id_recurso) REFERENCES recurso (id_recurso)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_posicion_zona FOREIGN KEY (id_zona) REFERENCES zona_vigilancia (id_zona)
        ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT fk_posicion_usuario FOREIGN KEY (id_usuario) REFERENCES usuario (id_usuario)
        ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE = InnoDB;

-- Índice para buscar rápido la última ubicación de cada recurso.
CREATE INDEX ix_posicion_recurso ON posicion_recurso (id_recurso, id_posicion);

-- Marcas operativas que la guardia pone en el mapa: puesto de comando,
-- punto de agua, acceso, punto de evacuación, peligro u otra. No se borran:
-- al quitarlas guardo quién y cuándo (ck_marca_baja exige los dos datos o
-- ninguno), así la cronología conserva lo que se usó durante el incidente.
-- La FK del usuario de la baja es RESTRICT también en el UPDATE porque la
-- columna está en un CHECK.
CREATE TABLE marca_mapa (
    id_marca        INT UNSIGNED  NOT NULL AUTO_INCREMENT,
    tipo            ENUM('PUESTO_COMANDO','PUNTO_AGUA','ACCESO','EVACUACION','PELIGRO','OTRA') NOT NULL,
    descripcion     VARCHAR(120)  NOT NULL,
    latitud         DECIMAL(8,5)  NOT NULL,
    longitud        DECIMAL(8,5)  NOT NULL,
    id_zona         INT UNSIGNED  NULL,
    fecha_hora      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    id_usuario      INT UNSIGNED  NOT NULL,
    fecha_hora_baja DATETIME      NULL,
    id_usuario_baja INT UNSIGNED  NULL,
    CONSTRAINT pk_marca_mapa PRIMARY KEY (id_marca),
    CONSTRAINT ck_marca_descripcion CHECK (CHAR_LENGTH(TRIM(descripcion)) > 0),
    CONSTRAINT ck_marca_coordenadas CHECK (latitud BETWEEN -90 AND 90 AND longitud BETWEEN -180 AND 180),
    CONSTRAINT ck_marca_baja CHECK ((fecha_hora_baja IS NULL AND id_usuario_baja IS NULL)
                                    OR (fecha_hora_baja IS NOT NULL AND id_usuario_baja IS NOT NULL)),
    CONSTRAINT fk_marca_zona FOREIGN KEY (id_zona) REFERENCES zona_vigilancia (id_zona)
        ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT fk_marca_usuario FOREIGN KEY (id_usuario) REFERENCES usuario (id_usuario)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_marca_usuario_baja FOREIGN KEY (id_usuario_baja) REFERENCES usuario (id_usuario)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------
-- Vistas: valores vigentes de cada zona (no se almacenan en la zona)
-- Prefiero resolver "lo vigente" con vistas antes que con columnas en
-- zona_vigilancia: el dato se calcula siempre a partir del historial, así
-- que no hay que acordarse de actualizar dos tablas cada vez que se carga
-- un relevamiento o se cambia un nivel, y nunca puede quedar desfasado.
-- ---------------------------------------------------------------------

-- Combustible vigente de cada zona: el relevamiento más reciente. Si hay
-- dos con la misma fecha, desempato por el id más alto (el último cargado).
CREATE VIEW v_combustible_vigente AS
SELECT r.id_zona,
       r.id_tipo_combustible,
       t.nombre                AS tipo_combustible,
       t.factor_inflamabilidad,
       r.carga_t_ha,
       r.fecha_relevamiento
FROM relevamiento_combustible r
JOIN tipo_combustible t ON t.id_tipo_combustible = r.id_tipo_combustible
WHERE r.id_relevamiento = (SELECT r2.id_relevamiento
                           FROM relevamiento_combustible r2
                           WHERE r2.id_zona = r.id_zona
                           ORDER BY r2.fecha_relevamiento DESC, r2.id_relevamiento DESC
                           LIMIT 1);

-- Nivel de alerta vigente de cada zona: el último cambio registrado, con
-- el mismo criterio de desempate por id.
CREATE VIEW v_nivel_alerta_vigente AS
SELECT c.id_zona,
       n.id_nivel_alerta,
       n.nombre     AS nivel_alerta,
       c.fecha_hora AS desde
FROM cambio_nivel_alerta c
JOIN nivel_alerta n ON n.id_nivel_alerta = c.id_nivel_alerta
WHERE c.id_cambio = (SELECT c2.id_cambio
                     FROM cambio_nivel_alerta c2
                     WHERE c2.id_zona = c.id_zona
                     ORDER BY c2.fecha_hora DESC, c2.id_cambio DESC
                     LIMIT 1);

-- Tablero de zonas (CU06, C-1): cada zona con su índice más reciente, el
-- nivel de riesgo de ese índice y el nivel de alerta vigente. Uso LEFT JOIN
-- para que también aparezcan las zonas que todavía no tienen índice
-- calculado o historial de alertas.
CREATE VIEW v_tablero_zonas AS
SELECT z.id_zona, z.nombre, z.vigilancia_activa,
       i.fecha       AS fecha_indice,
       i.valor_final AS indice,
       nr.nombre     AS nivel_riesgo,
       va.nivel_alerta
FROM zona_vigilancia z
LEFT JOIN indice_riesgo i
       ON i.id_zona = z.id_zona
      AND i.fecha = (SELECT MAX(i2.fecha) FROM indice_riesgo i2 WHERE i2.id_zona = z.id_zona)
LEFT JOIN nivel_riesgo nr ON nr.id_nivel_riesgo = i.id_nivel_riesgo
LEFT JOIN v_nivel_alerta_vigente va ON va.id_zona = z.id_zona;

-- ---------------------------------------------------------------------
-- Usuario de aplicación: solo lectura y escritura de datos
-- La aplicación no se conecta con root sino con sarif_app, al que le doy
-- únicamente SELECT, INSERT, UPDATE y DELETE sobre la base sarif. Aplico
-- el principio de mínimo privilegio: con esos permisos el programa puede
-- operar normalmente, pero no puede crear, modificar ni borrar tablas,
-- ni tocar otras bases o usuarios, así que un error o un uso indebido
-- desde la aplicación no puede dañar el esquema. Solo se conecta desde
-- localhost porque la base corre en la misma máquina que el programa.
-- La clave real no la subo al repositorio: antes de correr el script,
-- reemplazá CAMBIAR_ESTA_CLAVE por la clave que elijas y poné la misma en
-- config/sarif.local.properties (db.clave=...), que está en .gitignore.
-- Si sarif_app ya existe, el CREATE USER IF NOT EXISTS no le cambia la
-- clave: para cambiarla usá ALTER USER (ver el README).
-- ---------------------------------------------------------------------

CREATE USER IF NOT EXISTS 'sarif_app'@'localhost' IDENTIFIED BY 'CAMBIAR_ESTA_CLAVE';
GRANT SELECT, INSERT, UPDATE, DELETE ON sarif.* TO 'sarif_app'@'localhost';
FLUSH PRIVILEGES;
