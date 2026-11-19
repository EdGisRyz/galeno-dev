package com.intoglobe.clinicas.model;

/**
 * Planes contratables por una organización.
 *
 * <p>Las constantes son espejo EXACTO del CHECK {@code organizacion_plan_check} de la tabla
 * {@code organizacion} de la migración V1 (schema congelado, que ya incluía {@code ENTERPRISE}). No
 * se agregan valores aquí sin migrar primero dicho constraint.</p>
 *
 * <p><strong>Los límites de cada plan viven aquí y solo aquí.</strong> Son valores de producto —un
 * plan {@code SOLO} atiende un médico y un consultorio, uno {@code CLINICA} diez y seis, uno
 * {@code ENTERPRISE} no tiene tope— y el catálogo público del onboarding los publica tal como están
 * declarados en este enum. No se escriben en el formulario ni se reciben del cliente: quien se
 * registra elige el plan, no sus límites. Cuando el negocio los cambie, se cambian en esta lista y
 * el catálogo y la pantalla los toman de ahí.</p>
 *
 * <p>Un límite {@code null} significa «sin tope». Se publica como {@code null} en el contrato en
 * lugar de un número enorme, para que «sin límite» no se confunda con un límite concreto.</p>
 */
public enum PlanTipo {

    /** Organización de un solo profesional: un médico y un consultorio. */
    SOLO("Solo (un profesional)", 1, 1),

    /** Clínica con varios profesionales: hasta diez médicos y seis consultorios. */
    CLINICA("Clínica (varios profesionales)", 10, 6),

    /** Organización con alcance empresarial o multi-sede: sin límite declarado. */
    ENTERPRISE("Empresarial (varias sedes)", null, null);

    private final String etiqueta;
    private final Integer maxMedicos;
    private final Integer maxConsultorios;

    /**
     * Declara un plan con su etiqueta legible y sus límites de producto.
     *
     * @param etiqueta        nombre del plan para la interfaz
     * @param maxMedicos      médicos permitidos, o {@code null} si no hay tope
     * @param maxConsultorios consultorios permitidos, o {@code null} si no hay tope
     */
    PlanTipo(String etiqueta, Integer maxMedicos, Integer maxConsultorios) {
        this.etiqueta = etiqueta;
        this.maxMedicos = maxMedicos;
        this.maxConsultorios = maxConsultorios;
    }

    /**
     * Nombre del plan para la interfaz.
     *
     * @return etiqueta legible en español
     */
    public String getEtiqueta() {
        return etiqueta;
    }

    /**
     * Médicos que permite el plan.
     *
     * @return el máximo, o {@code null} si no hay tope
     */
    public Integer getMaxMedicos() {
        return maxMedicos;
    }

    /**
     * Consultorios que permite el plan.
     *
     * @return el máximo, o {@code null} si no hay tope
     */
    public Integer getMaxConsultorios() {
        return maxConsultorios;
    }

    /**
     * Indica si el plan tiene tope de médicos y de consultorios.
     *
     * @return {@code true} si ambos límites están declarados
     */
    public boolean tieneLimites() {
        return maxMedicos != null && maxConsultorios != null;
    }
}
