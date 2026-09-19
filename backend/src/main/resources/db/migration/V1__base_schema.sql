-- Esquema inicial
-- Flyway V1

-- ─────────────────────────────────────────
-- 1. ORGANIZACION
-- ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS organizacion (
                                            id                UUID                     DEFAULT gen_random_uuid() NOT NULL,
    nombre            VARCHAR(120)             NOT NULL,
    plan              VARCHAR(20)              NOT NULL,
    subscription_id   VARCHAR(100),
    trial_hasta       TIMESTAMP WITH TIME ZONE,
    activo            BOOLEAN                  DEFAULT TRUE NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    CONSTRAINT organizacion_pkey PRIMARY KEY (id),
    CONSTRAINT organizacion_plan_check CHECK (plan IN ('SOLO', 'CLINICA', 'ENTERPRISE'))
    );

-- ─────────────────────────────────────────
-- 2. CONFIGURACION_TENANT
-- ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS configuracion_tenant (
                                                    id                  UUID                     DEFAULT gen_random_uuid() NOT NULL,
    organizacion_id     UUID                     NOT NULL,
    notif_api_key       TEXT,
    storage_api_key     TEXT,
    payment_api_key     TEXT,
    audit_api_key       TEXT,
    whatsapp_conectado  BOOLEAN                  DEFAULT FALSE NOT NULL,
    whatsapp_numero     VARCHAR(20),
    created_at          TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    CONSTRAINT configuracion_tenant_pkey PRIMARY KEY (id),
    CONSTRAINT configuracion_tenant_organizacion_id_key UNIQUE (organizacion_id),
    CONSTRAINT configuracion_tenant_organizacion_id_fkey
    FOREIGN KEY (organizacion_id) REFERENCES organizacion (id) ON DELETE CASCADE
    );

-- ─────────────────────────────────────────
-- 3. CONSULTORIO
-- ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS consultorio (
                                           id               UUID                     DEFAULT gen_random_uuid() NOT NULL,
    organizacion_id  UUID                     NOT NULL,
    nombre           VARCHAR(120)             NOT NULL,
    direccion        TEXT,
    telefono         VARCHAR(20),
    activo           BOOLEAN                  DEFAULT TRUE NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    CONSTRAINT consultorio_pkey PRIMARY KEY (id),
    CONSTRAINT consultorio_organizacion_id_fkey
    FOREIGN KEY (organizacion_id) REFERENCES organizacion (id) ON DELETE CASCADE
    );

-- ─────────────────────────────────────────
-- 4. MEDICO
-- ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS medico (
                                      id               UUID           DEFAULT gen_random_uuid() NOT NULL,
    organizacion_id  UUID           NOT NULL,
    consultorio_id   UUID           NOT NULL,
    nombre           VARCHAR(120)   NOT NULL,
    especialidad     VARCHAR(80),
    cedula           VARCHAR(30),
    telefono         VARCHAR(20),
    tarifa_base      NUMERIC(10, 2),
    activo           BOOLEAN        DEFAULT TRUE NOT NULL,
    CONSTRAINT medico_pkey PRIMARY KEY (id),
    CONSTRAINT medico_organizacion_id_fkey
    FOREIGN KEY (organizacion_id) REFERENCES organizacion (id) ON DELETE CASCADE,
    CONSTRAINT medico_consultorio_id_fkey
    FOREIGN KEY (consultorio_id) REFERENCES consultorio (id)
    );

-- ─────────────────────────────────────────
-- 5. HORARIO_MEDICO
-- ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS horario_medico (
                                              id                 UUID      DEFAULT gen_random_uuid() NOT NULL,
    medico_id          UUID      NOT NULL,
    dia_semana         SMALLINT  NOT NULL,
    hora_inicio        TIME      NOT NULL,
    hora_fin           TIME      NOT NULL,
    duracion_consulta  SMALLINT  NOT NULL,
    CONSTRAINT horario_medico_pkey PRIMARY KEY (id),
    CONSTRAINT horario_medico_medico_id_fkey
    FOREIGN KEY (medico_id) REFERENCES medico (id) ON DELETE CASCADE,
    CONSTRAINT horario_medico_dia_semana_check
    CHECK (dia_semana >= 0 AND dia_semana <= 6),
    CONSTRAINT horario_medico_duracion_consulta_check
    CHECK (duracion_consulta IN (20, 30, 45, 60))
    );

-- ─────────────────────────────────────────
-- 6. PACIENTE
-- ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS paciente (
                                        id               UUID                     DEFAULT gen_random_uuid() NOT NULL,
    organizacion_id  UUID                     NOT NULL,
    nombre           VARCHAR(160)             NOT NULL,
    telefono         VARCHAR(20),
    fecha_nacimiento DATE,
    sexo             VARCHAR(1),
    email            VARCHAR(180),
    notas            TEXT,
    activo           BOOLEAN                  DEFAULT TRUE NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    CONSTRAINT paciente_pkey PRIMARY KEY (id),
    CONSTRAINT paciente_organizacion_id_fkey
    FOREIGN KEY (organizacion_id) REFERENCES organizacion (id) ON DELETE CASCADE,
    CONSTRAINT paciente_sexo_check
    CHECK (sexo IN ('M', 'F', 'O'))
    );

-- ─────────────────────────────────────────
-- 7. USUARIO (Incluye ADMIN, MEDICO y PACIENTE)
-- ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS usuario (
                                       id               UUID         DEFAULT gen_random_uuid() NOT NULL,
    organizacion_id  UUID         NOT NULL,
    medico_id        UUID,
    email            VARCHAR(180) NOT NULL,
    rol              VARCHAR(20)  NOT NULL,
    password_hash    TEXT         NOT NULL,
    activo           BOOLEAN      DEFAULT TRUE NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    CONSTRAINT usuario_pkey PRIMARY KEY (id),
    CONSTRAINT usuario_email_key UNIQUE (email),
    CONSTRAINT usuario_organizacion_id_fkey
    FOREIGN KEY (organizacion_id) REFERENCES organizacion (id) ON DELETE CASCADE,
    CONSTRAINT usuario_medico_id_fkey
    FOREIGN KEY (medico_id) REFERENCES medico (id) ON DELETE SET NULL,
    CONSTRAINT usuario_rol_check
    CHECK (rol IN ('ADMIN', 'MEDICO', 'PACIENTE'))
    );

-- ─────────────────────────────────────────
-- 8. CITA
-- ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS cita (
                                    id               UUID                     DEFAULT gen_random_uuid() NOT NULL,
    organizacion_id  UUID                     NOT NULL,
    paciente_id      UUID                     NOT NULL,
    medico_id        UUID                     NOT NULL,
    consultorio_id   UUID                     NOT NULL,
    fecha_hora       TIMESTAMP WITH TIME ZONE NOT NULL,
                                   duracion_min     SMALLINT                 NOT NULL,
                                   estado           VARCHAR(20)              DEFAULT 'SIN_CONFIRMAR' NOT NULL,
    motivo           TEXT,
    cancelada_en     TIMESTAMP WITH TIME ZONE,
                                   cancelada_por    VARCHAR(20),
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    CONSTRAINT cita_pkey PRIMARY KEY (id),
    CONSTRAINT cita_organizacion_id_fkey
    FOREIGN KEY (organizacion_id) REFERENCES organizacion (id) ON DELETE CASCADE,
    CONSTRAINT cita_paciente_id_fkey
    FOREIGN KEY (paciente_id) REFERENCES paciente (id),
    CONSTRAINT cita_medico_id_fkey
    FOREIGN KEY (medico_id) REFERENCES medico (id),
    CONSTRAINT cita_consultorio_id_fkey
    FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT cita_estado_check
    CHECK (estado IN ('SIN_CONFIRMAR', 'CONFIRMADA', 'CANCELADA', 'REAGENDADA', 'NO_ASISTIO')),
    CONSTRAINT cita_cancelada_por_check
    CHECK (cancelada_por IN ('PACIENTE', 'ADMIN', 'MEDICO'))
    );

-- ─────────────────────────────────────────
-- 9. PAGO
-- ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS pago (
                                    id               UUID                     DEFAULT gen_random_uuid() NOT NULL,
    organizacion_id  UUID                     NOT NULL,
    cita_id          UUID                     NOT NULL,
    monto            NUMERIC(10, 2)           NOT NULL,
    metodo           VARCHAR(20)              NOT NULL,
    concepto         VARCHAR(200),
    estado           VARCHAR(20)              DEFAULT 'PENDIENTE' NOT NULL,
    referencia       VARCHAR(100),
    pagado_en        TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    CONSTRAINT pago_pkey PRIMARY KEY (id),
    CONSTRAINT pago_organizacion_id_fkey
    FOREIGN KEY (organizacion_id) REFERENCES organizacion (id) ON DELETE CASCADE,
    CONSTRAINT pago_cita_id_fkey
    FOREIGN KEY (cita_id) REFERENCES cita (id),
    CONSTRAINT pago_metodo_check
    CHECK (metodo IN ('EFECTIVO', 'TRANSFERENCIA', 'TARJETA')),
    CONSTRAINT pago_estado_check
    CHECK (estado IN ('PAGADO', 'PENDIENTE'))
    );

-- ─────────────────────────────────────────
-- 10. ADJUNTO
-- ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS adjunto (
                                       id               UUID                     DEFAULT gen_random_uuid() NOT NULL,
    organizacion_id  UUID                     NOT NULL,
    paciente_id      UUID                     NOT NULL,
    cita_id          UUID,
    subido_por       UUID                     NOT NULL,
    tipo             VARCHAR(30)              NOT NULL,
    nombre_archivo   VARCHAR(200)             NOT NULL,
    url_archivo      TEXT                     NOT NULL,
    mime_type        VARCHAR(80),
    notificar        BOOLEAN                  DEFAULT FALSE NOT NULL,
    notificado_en    TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    CONSTRAINT adjunto_pkey PRIMARY KEY (id),
    CONSTRAINT adjunto_organizacion_id_fkey
    FOREIGN KEY (organizacion_id) REFERENCES organizacion (id) ON DELETE CASCADE,
    CONSTRAINT adjunto_paciente_id_fkey
    FOREIGN KEY (paciente_id) REFERENCES paciente (id),
    CONSTRAINT adjunto_cita_id_fkey
    FOREIGN KEY (cita_id) REFERENCES cita (id),
    CONSTRAINT adjunto_subido_por_fkey
    FOREIGN KEY (subido_por) REFERENCES usuario (id)
    );

-- ─────────────────────────────────────────
-- 11. NOTIFICACION
-- ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS notificacion (
                                            id               UUID                     DEFAULT gen_random_uuid() NOT NULL,
    organizacion_id  UUID                     NOT NULL,
    cita_id          UUID                     NOT NULL,
    adjunto_id       UUID,
    canal            VARCHAR(20)              DEFAULT 'WHATSAPP' NOT NULL,
    tipo             VARCHAR(40)              NOT NULL,
    estado           VARCHAR(20)              DEFAULT 'PENDIENTE' NOT NULL,
    enviada_en       TIMESTAMP WITH TIME ZONE,
    respuesta        TEXT,
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    CONSTRAINT notificacion_pkey PRIMARY KEY (id),
    CONSTRAINT notificacion_organizacion_id_fkey
    FOREIGN KEY (organizacion_id) REFERENCES organizacion (id) ON DELETE CASCADE,
    CONSTRAINT notificacion_cita_id_fkey
    FOREIGN KEY (cita_id) REFERENCES cita (id),
    CONSTRAINT notificacion_adjunto_id_fkey
    FOREIGN KEY (adjunto_id) REFERENCES adjunto (id),
    CONSTRAINT notificacion_tipo_check
    CHECK (tipo IN ('RECORDATORIO_48H', 'RECORDATORIO_24H', 'ADJUNTO')),
    CONSTRAINT notificacion_estado_check
    CHECK (estado IN ('PENDIENTE', 'ENVIADO', 'FALLIDO'))
    );

-- ─────────────────────────────────────────
-- 12. DIAGNOSTICO
-- ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS diagnostico (
                                           id            UUID                     DEFAULT gen_random_uuid() NOT NULL,
    cita_id       UUID                     NOT NULL,
    codigo_cie10  VARCHAR(10),
    descripcion   TEXT                     NOT NULL,
    tipo          VARCHAR(20),
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    CONSTRAINT diagnostico_pkey PRIMARY KEY (id),
    CONSTRAINT diagnostico_cita_id_fkey
    FOREIGN KEY (cita_id) REFERENCES cita (id),
    CONSTRAINT diagnostico_tipo_check
    CHECK (tipo IN ('PRINCIPAL', 'SECUNDARIO'))
    );

-- ─────────────────────────────────────────
-- INDICES
-- ─────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_consultorio_org  ON consultorio (organizacion_id);
CREATE INDEX IF NOT EXISTS idx_medico_org       ON medico (organizacion_id);
CREATE INDEX IF NOT EXISTS idx_paciente_org     ON paciente (organizacion_id);
CREATE INDEX IF NOT EXISTS idx_usuario_org      ON usuario (organizacion_id);
CREATE INDEX IF NOT EXISTS idx_cita_org         ON cita (organizacion_id);
CREATE INDEX IF NOT EXISTS idx_cita_paciente    ON cita (paciente_id);
CREATE INDEX IF NOT EXISTS idx_cita_medico      ON cita (medico_id);
CREATE INDEX IF NOT EXISTS idx_cita_fecha       ON cita (fecha_hora);
CREATE INDEX IF NOT EXISTS idx_pago_org         ON pago (organizacion_id);
CREATE INDEX IF NOT EXISTS idx_pago_cita        ON pago (cita_id);
CREATE INDEX IF NOT EXISTS idx_adjunto_paciente ON adjunto (paciente_id);
CREATE INDEX IF NOT EXISTS idx_notif_cita       ON notificacion (cita_id);
CREATE INDEX IF NOT EXISTS idx_diagnostico_cita ON diagnostico (cita_id);