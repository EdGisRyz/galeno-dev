package com.intoglobe.clinicas.model;

/**
 * Planes contratables por una organización.
 *
 * <p>Las constantes son espejo EXACTO del CHECK {@code organizacion_plan_check}
 * de la tabla {@code organizacion} de la migración V1 (schema congelado). No se
 * agregan valores aquí sin migrar primero dicho constraint.</p>
 */
public enum PlanTipo {

    /** Organización de un solo profesional. */
    SOLO,

    /** Clínica con varios profesionales y consultorios. */
    CLINICA,

    /** Organización con alcance empresarial o multi-sede. */
    ENTERPRISE
}
